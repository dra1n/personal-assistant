(ns pa.ui.app
  (:require [charm.components.viewport :as vp]
            [charm.message :as msg]
            [charm.program :as charm]
            [charm.style.core :as style]
            [clojure.string :as str]
            [pa.state.db :as db]
            [pa.state.queries :as queries]
            [pa.ui.subscribe :as subscribe]))

;; ---------------------------------------------------------------------------
;; Charm model
;;
;; {:db         <latest runtime state snapshot>
;;  :db-ch      <core.async channel — owned by pa.ui.core, passed in at init>
;;  :dispatch!  <runtime dispatch fn — UI's only way to push events inward>
;;  :input      <UI-local input buffer string>
;;  :viewport   <charm viewport holding the scrollable conversation>
;;  :logs       <bounded vector of recent log entries {:level :msg :instant}>
;;  :logs-open? <whether the log panel is expanded>
;;  :log-ch     <core.async channel feeding :log/appended messages>
;;  :width      <terminal width, tracked from :window-size>
;;  :height     <terminal height>}
;;
;; Runtime state is read only through pa.state.queries; the UI never mutates
;; it directly. User input becomes a :user/message event via :dispatch!.
;; ---------------------------------------------------------------------------

(def ^:private accent style/cyan)
(def ^:private log-buffer-size 200)   ; ring-buffer cap for in-memory log entries
(def ^:private log-content-lines 8)   ; visible log lines when the panel is expanded

;; --- conversation rendering -------------------------------------------------

(defn- wrap-line
  "Greedily word-wrap a single (ANSI-free) line to `width` columns. A word
  longer than `width` is left intact rather than hard-split."
  [line width]
  (if (<= (count line) width)
    [line]
    (loop [words (str/split line #"\s+") cur "" out []]
      (if-let [w (first words)]
        (let [cand (if (empty? cur) w (str cur " " w))]
          (if (<= (count cand) width)
            (recur (rest words) cand out)
            (recur (rest words) w (if (empty? cur) out (conj out cur)))))
        (if (empty? cur) out (conj out cur))))))

(defn- wrap-text [s width]
  (->> (str/split-lines s)
       (mapcat #(wrap-line % width))
       (str/join "\n")))

(defn- render-turn [{:keys [role content]} width]
  (let [label (case role
                :user      (style/styled "you"       :fg accent :bold true)
                :assistant (style/styled "assistant" :fg style/green :bold true)
                (style/styled (name (or role :system)) :faint true))]
    (str label "\n" (wrap-text (str content) (max 1 (or width 80))))))

(defn- conversation-content [db width]
  (let [turns (queries/conversation db)]
    (if (seq turns)
      (str/join "\n\n" (map #(render-turn % width) turns))
      (style/styled "Type a message and press Enter." :faint true))))

;; --- layout / viewport ------------------------------------------------------

(defn- panel-lines
  "Vertical lines the log panel occupies: a one-line summary when collapsed,
  a header plus the content rows when expanded."
  [logs-open?]
  (if logs-open? (inc log-content-lines) 1))

(defn- viewport-height
  "Lines available to the conversation viewport: terminal height minus the
  fixed chrome (header + blanks + input box + hint) and the log panel."
  [{:keys [height logs-open?]}]
  (max 3 (- (or height 24) (+ 7 (panel-lines logs-open?)))))

(defn- refresh-viewport
  "Rebuild the viewport's content and dimensions from the model's db + size,
  pinned to the bottom (latest turn visible)."
  [{:keys [db width viewport] :as model}]
  (assoc model :viewport
         (-> (or viewport (vp/viewport ""))
             (vp/viewport-set-content (conversation-content db width))
             (vp/viewport-set-dimensions 0 (viewport-height model))
             (vp/scroll-to-bottom))))

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn init
  "Return a charm init fn. Returns the initial model plus the startup commands."
  [{:keys [db-ch watch-cmd dispatch! log-ch watch-log-cmd]}]
  (fn []
    [(refresh-viewport {:db         (db/current-db)
                        :db-ch      db-ch
                        :dispatch!  dispatch!
                        :log-ch     log-ch
                        :logs       []
                        :logs-open? false
                        :input      ""
                        :width      80
                        :height     24
                        :viewport   (vp/viewport "" :height (viewport-height {:height 24}))})
     (charm/batch watch-cmd watch-log-cmd)]))

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

(defn- scroll [model f]
  (cond-> model (:viewport model) (update :viewport f)))

(defn- append-log [model entry]
  (update model :logs #(vec (take-last log-buffer-size (conj (or % []) entry)))))

(defn update-model [model message]
  (cond
    (and (msg/key-press? message) (msg/key-match? message "ctrl+c"))
    [model charm/quit-cmd]

    (= :runtime/db-updated (:type message))
    [(refresh-viewport (assoc model :db (:db message)))
     (subscribe/watch-db-cmd (:db-ch model))]

    (= :log/appended (:type message))
    [(append-log model (:entry message))
     (subscribe/watch-log-cmd (:log-ch model))]

    (= :window-size (:type message))
    [(refresh-viewport (assoc model :width (:width message) :height (:height message))) nil]

    ;; Toggle the log panel (recompute viewport height for the new chrome).
    (and (msg/key-press? message) (msg/key-match? message "ctrl+l"))
    [(refresh-viewport (update model :logs-open? not)) nil]

    ;; Conversation scrolling — dedicated keys that never conflict with typing.
    (msg/key-match? message :page-up)    [(scroll model vp/scroll-page-up) nil]
    (msg/key-match? message :page-down)  [(scroll model vp/scroll-page-down) nil]
    (msg/key-match? message "ctrl+u")    [(scroll model vp/scroll-half-page-up) nil]
    (msg/key-match? message "ctrl+d")    [(scroll model vp/scroll-half-page-down) nil]

    ;; Enter — commit the buffer as a user message (ignored when blank).
    (msg/key-match? message :enter)
    (let [text (str/trim (:input model))]
      (if (str/blank? text)
        [model nil]
        [(assoc model :input "")
         (dispatch-user-message (:dispatch! model) text)]))

    (msg/key-match? message :backspace)
    [(update model :input backspace) nil]

    ;; Space arrives as a special key, not a runes string.
    (msg/key-match? message :space)
    [(update model :input str " ") nil]

    ;; Printable characters arrive as a runes string; ignore modified chords.
    (and (msg/key-press? message)
         (string? (:key message))
         (not (msg/ctrl? message))
         (not (msg/alt? message)))
    [(update model :input str (:key message)) nil]

    :else
    [model nil]))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(def ^:private placeholder "Ask me anything…")

(defn- header []
  (style/styled "  personal assistant  " :bold true :fg style/black :bg accent))

(defn- conversation-view [model]
  (if-let [vp* (:viewport model)]
    (vp/viewport-view vp*)
    (conversation-content (:db model) (:width model))))

(defn- cursor []
  (style/styled " " :reverse true))

(defn- visible-window
  "Trailing window of `s` that fits in `avail` columns, prefixing an ellipsis
  when text scrolled off the left. The result is never wider than `avail`."
  [s avail]
  (if (<= (count s) avail)
    s
    (str "…" (subs s (- (count s) (dec avail))))))

(defn- input-view [{:keys [input width]}]
  ;; NOTE: charm's apply-text-style downgrades box-drawing edges (─ │) to
  ;; ASCII (- |) whenever a border :fg/:bg is set, so the border is left
  ;; uncoloured; the accent lives on the prompt glyph instead.
  (let [inner (max 20 (- (or width 80) 4))
        avail (max 1 (- inner 5))                 ; room for input text + cursor
        body  (if (str/blank? input)
                (str (cursor) (style/styled placeholder :faint true))
                (str (visible-window input avail) (cursor)))]
    (style/render
     (style/style :border  style/rounded-border
                  :padding [0 1]
                  :width   inner)
     (str (style/styled "›" :fg accent :bold true) " " body))))

(defn- hint []
  (style/styled "Enter to send · PgUp/PgDn to scroll · ^L logs · Ctrl+C to quit" :faint true))

;; --- log panel --------------------------------------------------------------

(defn- log-entry-line [{:keys [level msg]} width]
  (let [oneline (str (format "%-5s " (str/upper-case (name (or level :info))))
                     (str/replace (str msg) #"\s*\n\s*" " "))
        text    (style/truncate oneline width :tail "…")]
    (case level
      :error (style/render (style/style :fg style/red :bold true) text)
      :fatal (style/render (style/style :fg style/red :bold true) text)
      :warn  (style/render (style/style :fg style/yellow) text)
      :debug (style/render (style/style :faint true) text)
      text)))

(defn- log-panel [{:keys [logs logs-open? width]}]
  (let [w    (max 10 (or width 80))
        logs (or logs [])]
    (if logs-open?
      (let [shown (vec (take-last log-content-lines logs))
            rows  (concat (map #(log-entry-line % w) shown)
                          (repeat (- log-content-lines (count shown)) ""))]
        (str/join "\n" (cons (style/styled "▾ logs · ^L to collapse" :faint true) rows)))
      (let [n     (count logs)
            warns (count (filter #(#{:warn :error :fatal} (:level %)) logs))]
        (style/styled (str "▸ logs (" n (when (pos? warns) (str ", " warns " warn/err")) ") · ^L to expand")
                      :faint true)))))

(defn view [model]
  (style/join-vertical
   :left
   (header)
   ""
   (conversation-view model)
   ""
   (input-view model)
   (hint)
   (log-panel model)))
