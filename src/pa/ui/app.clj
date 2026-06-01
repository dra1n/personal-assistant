(ns pa.ui.app
  "Model and update for the terminal UI — the charm program's state and its
  transitions. Rendering and layout live in pa.ui.view; this namespace calls
  into it only to pre-render content for the scrollable viewports.

  Charm model:
    {:db           latest runtime state snapshot
     :db-ch        core.async channel — owned by pa.ui.core
     :dispatch!    runtime dispatch fn — the UI's only way to push events in
     :input        UI-local input buffer string
     :viewport     charm viewport: the scrollable conversation
     :log-viewport charm viewport: the scrollable log panel
     :logs         bounded vector of recent log entries {:level :msg :instant}
     :logs-open?   whether the log panel is expanded
     :focus        :input | :logs — which region is highlighted & scrolls
     :log-ch       core.async channel feeding :log/appended messages
     :width :height terminal dimensions, tracked from :window-size}

  Two focusable regions: the input (chat mode — scroll keys move the
  conversation) and the log panel (scroll keys move the logs). Typing and
  Enter always target the input regardless of focus. Runtime state is read
  only via pa.state.queries; the UI never mutates it."
  (:require [charm.components.viewport :as vp]
            [charm.message :as msg]
            [charm.program :as charm]
            [clojure.string :as str]
            [pa.state.db :as db]
            [pa.ui.subscribe :as subscribe]
            [pa.ui.view :as view]))

(def ^:private log-buffer-size 200)   ; ring-buffer cap for in-memory log entries

;; --- viewport refresh (pre-render content into the scroll viewports) --------

(defn- refresh-conversation
  "Rebuild the conversation viewport from db + size (plus any in-progress
  streamed response), pinned to the latest turn."
  [{:keys [db width streaming viewport] :as model}]
  (assoc model :viewport
         (-> (or viewport (vp/viewport ""))
             (vp/viewport-set-content (view/conversation-content db (or width 80) streaming))
             (vp/viewport-set-dimensions 0 (view/viewport-height model))
             (vp/scroll-to-bottom))))

(defn- refresh-logs
  "Rebuild the log viewport from the ring buffer. Tails (scroll to bottom)
  unless the panel is focused and the user has scrolled up — then the current
  position is preserved so logs can be read without being yanked away."
  [{:keys [logs log-viewport focus] :as model}]
  (let [at-bottom? (or (nil? log-viewport) (vp/viewport-at-bottom? log-viewport))
        prev-off   (:y-offset log-viewport)
        vp0        (-> (or log-viewport (vp/viewport ""))
                       (vp/viewport-set-content (view/logs-content logs (view/inner-width model)))
                       (vp/viewport-set-dimensions 0 view/log-content-lines))]
    (assoc model :log-viewport
           (if (and (= focus :logs) (not at-bottom?))
             (vp/viewport-scroll-to vp0 (or prev-off 0))
             (vp/scroll-to-bottom vp0)))))

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn init
  "Return a charm init fn. Returns the initial model plus the startup commands."
  [{:keys [db-ch watch-cmd dispatch! log-ch watch-log-cmd delta-ch watch-delta-cmd]}]
  (fn []
    [(-> {:db           (db/current-db)
          :db-ch        db-ch
          :dispatch!    dispatch!
          :log-ch       log-ch
          :delta-ch     delta-ch
          :logs         []
          :logs-open?   false
          :focus        :input
          :input        ""
          :streaming    ""
          :width        80
          :height       24
          :viewport     (vp/viewport "")
          :log-viewport (vp/viewport "")}
         refresh-conversation
         refresh-logs)
     (charm/batch watch-cmd watch-log-cmd watch-delta-cmd)]))

;; ---------------------------------------------------------------------------
;; Update
;; ---------------------------------------------------------------------------

(defn- dispatch-user-message
  "A charm command that pushes a :user/message event into the runtime. The
  command produces no follow-up charm message (returns nil)."
  [dispatch! text]
  (charm/cmd
   (fn []
     (dispatch! {:event/type :user/message :content text})
     nil)))

(defn- backspace [s]
  (if (seq s) (subs s 0 (dec (count s))) s))

(defn- append-log [model entry]
  (update model :logs #(vec (take-last log-buffer-size (conj (or % []) entry)))))

(defn- scroll-focused
  "Apply scroll fn `f` to whichever viewport the focus drives: the log panel
  when focus is :logs, otherwise the conversation."
  [model f]
  (let [k (if (= (:focus model) :logs) :log-viewport :viewport)]
    (cond-> model (k model) (update k f))))

(defn- focus-input
  "Return focus to the input (used when the user interacts with the input
  buffer while the log panel is focused). Refreshes the logs so they resume
  tailing once unfocused."
  [model]
  (if (= :logs (:focus model))
    (refresh-logs (assoc model :focus :input))
    model))

(defn update-model [model message]
  (cond
    (and (msg/key-press? message) (msg/key-match? message "ctrl+c"))
    [model charm/quit-cmd]

    ;; A committed conversation snapshot — clear the in-progress stream (the
    ;; assistant turn, if any, is now part of the conversation).
    (= :runtime/db-updated (:type message))
    [(refresh-conversation (assoc model :db (:db message) :streaming ""))
     (subscribe/watch-db-cmd (:db-ch model))]

    (= :log/appended (:type message))
    [(refresh-logs (append-log model (:entry message)))
     (subscribe/watch-log-cmd (:log-ch model))]

    ;; A streamed response fragment — grow the live buffer and re-render.
    (= :llm/delta (:type message))
    [(refresh-conversation (update model :streaming str (:delta message)))
     (subscribe/watch-delta-cmd (:delta-ch model))]

    (= :window-size (:type message))
    [(-> model
         (assoc :width (:width message) :height (:height message))
         refresh-conversation
         refresh-logs)
     nil]

    ;; Toggle the log panel; opening focuses it, closing returns focus to input.
    (and (msg/key-press? message) (msg/key-match? message "ctrl+l"))
    (let [opening? (not (:logs-open? model))]
      [(-> model
           (assoc :logs-open? opening? :focus (if opening? :logs :input))
           refresh-conversation
           refresh-logs)
       nil])

    ;; Tab switches focus between input and logs (only when the panel shows).
    (msg/key-match? message :tab)
    (if (:logs-open? model)
      [(refresh-logs (update model :focus #(if (= % :logs) :input :logs))) nil]
      [model nil])

    ;; Arrow keys scroll the focused region one line at a time.
    (msg/key-match? message :up)    [(scroll-focused model vp/scroll-up) nil]
    (msg/key-match? message :down)  [(scroll-focused model vp/scroll-down) nil]

    ;; Enter — commit the buffer as a user message (ignored when blank).
    (msg/key-match? message :enter)
    (let [text (str/trim (:input model))]
      (if (str/blank? text)
        [(focus-input model) nil]
        [(focus-input (assoc model :input ""))
         (dispatch-user-message (:dispatch! model) text)]))

    (msg/key-match? message :backspace)
    [(focus-input (update model :input backspace)) nil]

    ;; Space arrives as a special key, not a runes string.
    (msg/key-match? message :space)
    [(focus-input (update model :input str " ")) nil]

    ;; Printable characters arrive as a runes string; ignore modified chords.
    (and (msg/key-press? message)
         (string? (:key message))
         (not (msg/ctrl? message))
         (not (msg/alt? message)))
    [(focus-input (update model :input str (:key message))) nil]

    :else
    [model nil]))
