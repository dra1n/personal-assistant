(ns pa.ui.app
  "Model and update for the terminal UI — the charm program's state and its
  transitions. Rendering and layout live in pa.ui.view; this namespace calls
  into it only to pre-render content for the scrollable viewports.

  Charm model:
    {:db           latest runtime state snapshot
     :db-ch        core.async channel — owned by pa.ui.core
     :dispatch!    runtime dispatch fn — the UI's only way to push events in
     :input        UI-local input buffer string
     :streaming    live text of the in-flight assistant turn (delta preview)
     :streaming-open? whether to accept incoming deltas — opened when the user
                   sends, closed when the assistant turn commits (so stragglers
                   buffered on delta-ch can't re-grow a ghost turn)
     :motd-fallback session's header MOTD when the user hasn't set one in
                   user.md — picked once at startup so it doesn't flicker
     :viewport     charm viewport: the scrollable conversation
     :log-viewport charm viewport: the scrollable log panel
     :logs         bounded vector of recent log entries {:level :msg :instant}
     :logs-open?   whether the log panel is expanded
     :focus        :input | :conversation | :logs — the highlighted region
     :log-ch       core.async channel feeding :log/appended messages
     :width :height terminal dimensions, tracked from :window-size}

  Three focusable regions: the input (where typing lands), the conversation,
  and the log panel. The focused region gets a thick border and is the target
  of the ↑/↓ scroll keys; input focus scrolls nothing (the conversation tails
  live). Tab cycles input → conversation → logs (when open) → input; Esc
  returns to the input. Typing and Enter always target the input and snap
  focus back to it. Runtime state is read only via pa.state.queries; the UI
  never mutates it."
  (:require [charm.components.viewport :as vp]
            [charm.message :as msg]
            [charm.program :as charm]
            [clojure.string :as str]
            [pa.state.db :as db]
            [pa.ui.input :as input]
            [pa.ui.subscribe :as subscribe]
            [pa.ui.view :as view]))

(def ^:private log-buffer-size 200)   ; ring-buffer cap for in-memory log entries

;; --- viewport refresh (pre-render content into the scroll viewports) --------

(defn- refresh-conversation
  "Rebuild the conversation viewport from db + size (plus any in-progress
  streamed response). Tails the latest turn unless the conversation is
  focused and the user has scrolled up — then the position is held so history
  can be read without being yanked away by new turns or streamed deltas."
  [{:keys [db streaming viewport focus] :as model}]
  (let [at-bottom? (or (nil? viewport) (vp/viewport-at-bottom? viewport))
        prev-off   (:y-offset viewport)
        vp0        (-> (or viewport (vp/viewport ""))
                       (vp/viewport-set-content
                        (view/conversation-content db (view/text-width model) streaming))
                       (vp/viewport-set-dimensions 0 (view/viewport-height model)))]
    (assoc model :viewport
           (if (and (= focus :conversation) (not at-bottom?))
             (vp/viewport-scroll-to vp0 (or prev-off 0))
             (vp/scroll-to-bottom vp0)))))

(defn- refresh-logs
  "Rebuild the log viewport from the ring buffer. Tails (scroll to bottom)
  unless the panel is focused and the user has scrolled up — then the current
  position is preserved so logs can be read without being yanked away."
  [{:keys [logs log-viewport focus] :as model}]
  (let [at-bottom? (or (nil? log-viewport) (vp/viewport-at-bottom? log-viewport))
        prev-off   (:y-offset log-viewport)
        vp0        (-> (or log-viewport (vp/viewport ""))
                       (vp/viewport-set-content (view/logs-content logs (view/text-width model)))
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
          :nav/index    nil
          :nav/draft    ""
          :streaming    ""
          :streaming-open? false
          :motd-fallback   (view/random-tip)
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
  "Apply scroll fn `f` to the viewport the focus drives: the log panel when
  focus is :logs, the conversation when :conversation. Input focus scrolls
  nothing — the conversation tails live until explicitly focused."
  [model f]
  (if-let [k (case (:focus model)
               :logs         :log-viewport
               :conversation :viewport
               nil)]
    (cond-> model (k model) (update k f))
    model))

(defn- next-focus
  "The next region when cycling focus with Tab: input → conversation → logs
  → input. The conversation is skipped while empty (it shows a borderless,
  unfocusable placeholder); the log panel only when collapsed."
  [{:keys [focus logs-open?] :as model}]
  (let [order (cond-> [:input]
                (not (view/conversation-empty? model)) (conj :conversation)
                logs-open?                             (conj :logs))]
    (->> (concat order order) (drop-while #(not= % focus)) second)))

(defn- focus-input
  "Return focus to the input when the user interacts with the input buffer
  while another region is focused. Refreshes the region being left so it
  resumes tailing once unfocused."
  [model]
  (case (:focus model)
    :logs         (refresh-logs (assoc model :focus :input))
    :conversation (refresh-conversation (assoc model :focus :input))
    model))

(defn update-model [model message]
  (cond
    (and (msg/key-press? message) (msg/key-match? message "ctrl+c"))
    [model charm/quit-cmd]

    ;; A committed conversation snapshot — clear the in-progress stream (the
    ;; assistant turn, if any, is now part of the conversation). Once the
    ;; assistant turn lands the stream is closed, so late deltas still buffered
    ;; on delta-ch are rejected rather than re-growing a ghost trailing turn.
    (= :runtime/db-updated (:type message))
    (let [new-db (:db message)
          committed? (= :assistant (:role (last (:conversation new-db))))]
      [(refresh-conversation (assoc model
                                    :db new-db
                                    :streaming ""
                                    :streaming-open? (and (:streaming-open? model)
                                                          (not committed?))))
       (subscribe/watch-db-cmd (:db-ch model))])

    (= :log/appended (:type message))
    [(refresh-logs (append-log model (:entry message)))
     (subscribe/watch-log-cmd (:log-ch model))]

    ;; A streamed response fragment — grow the live buffer and re-render, but
    ;; only while the stream is open. A delta arriving after the turn committed
    ;; is a straggler and is dropped (the channel is still drained/rescheduled).
    (= :llm/delta (:type message))
    [(cond-> model
       (:streaming-open? model) (-> (update :streaming str (:delta message))
                                    refresh-conversation))
     (subscribe/watch-delta-cmd (:delta-ch model))]

    (= :window-size (:type message))
    [(-> model
         (assoc :width (:width message) :height (:height message))
         refresh-conversation
         refresh-logs)
     nil]

    ;; Toggle the log panel; opening focuses it, closing returns focus to the
    ;; input only if the logs held it (a conversation focus is left intact).
    (and (msg/key-press? message) (msg/key-match? message "ctrl+l"))
    (let [opening? (not (:logs-open? model))]
      [(-> model
           (assoc :logs-open? opening?
                  :focus (cond opening?                 :logs
                               (= :logs (:focus model)) :input
                               :else                    (:focus model)))
           refresh-conversation
           refresh-logs)
       nil])

    ;; Tab cycles focus across regions; Esc returns to the input.
    (msg/key-match? message :tab)
    [(-> model (assoc :focus (next-focus model)) refresh-conversation refresh-logs) nil]

    (msg/key-match? message :escape)
    [(focus-input model) nil]

    ;; ↑/↓ navigate command history when input is focused; otherwise scroll.
    (msg/key-match? message :up)
    (if (= :input (:focus model))
      (let [nav     (select-keys model [:nav/index :nav/draft])
            history (get-in model [:db :ui/history] [])
            [nav' text] (input/navigate-back nav history (:input model))]
        [(merge model nav' {:input text}) nil])
      [(scroll-focused model vp/scroll-up) nil])

    (msg/key-match? message :down)
    (if (= :input (:focus model))
      (let [nav     (select-keys model [:nav/index :nav/draft])
            history (get-in model [:db :ui/history] [])
            [nav' text] (input/navigate-forward nav history)]
        [(merge model nav' {:input text}) nil])
      [(scroll-focused model vp/scroll-down) nil])

    ;; Enter — commit the buffer as a user message (ignored when blank).
    (msg/key-match? message :enter)
    (let [text (str/trim (:input model))]
      (if (str/blank? text)
        [(focus-input model) nil]
        ;; Open the stream so the assistant's deltas are accepted into the live
        ;; preview; it closes again when the assistant turn commits.
        [(focus-input (assoc model :input "" :nav/index nil :nav/draft "" :streaming-open? true))
         (dispatch-user-message (:dispatch! model) text)]))

    (msg/key-match? message :backspace)
    [(focus-input (update model :input backspace)) nil]

    ;; Space arrives as a special key, not a runes string.
    (msg/key-match? message :space)
    [(focus-input (update model :input str " ")) nil]

    ;; Printable characters arrive as a runes string; ignore modified chords.
    ;; While navigating history, exit navigation and incorporate the typed char.
    (and (msg/key-press? message)
         (string? (:key message))
         (not (msg/ctrl? message))
         (not (msg/alt? message)))
    (if (some? (:nav/index model))
      (let [[nav' text] (input/reset-navigation (select-keys model [:nav/index :nav/draft])
                                                (:input model)
                                                (:key message))]
        [(focus-input (merge model nav' {:input text})) nil])
      [(focus-input (update model :input str (:key message))) nil])

    :else
    [model nil]))
