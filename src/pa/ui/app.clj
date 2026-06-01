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
;; {:db           <latest runtime state snapshot>
;;  :db-ch        <core.async channel — owned by pa.ui.core>
;;  :dispatch!    <runtime dispatch fn — UI's only way to push events inward>
;;  :input        <UI-local input buffer string>
;;  :viewport     <charm viewport: the scrollable conversation>
;;  :log-viewport <charm viewport: the scrollable log panel>
;;  :logs         <bounded vector of recent log entries {:level :msg :instant}>
;;  :logs-open?   <whether the log panel is expanded>
;;  :focus        <:input | :logs — which region is highlighted & scrolls>
;;  :log-ch       <core.async channel feeding :log/appended messages>
;;  :width :height <terminal dimensions, tracked from :window-size>}
;;
;; Two focusable regions: the input (chat mode — scroll keys move the
;; conversation) and the log panel (scroll keys move the logs). Typing and
;; Enter always target the input regardless of focus. Runtime state is read
;; only via pa.state.queries; the UI never mutates it.
;; ---------------------------------------------------------------------------

(def ^:private accent style/cyan)
(def ^:private log-buffer-size 200)   ; ring-buffer cap for in-memory log entries
(def ^:private log-content-lines 8)   ; log rows visible inside the expanded panel

(defn- inner-width
  "Content width inside a full-width bordered box (terminal width minus the
  two border columns)."
  [{:keys [width]}]
  (max 10 (- (or width 80) 2)))

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
    (str label "\n" (wrap-text (str content) (max 1 width)))))

(defn- conversation-content [db width]
  (let [turns (queries/conversation db)]
    (if (seq turns)
      (str/join "\n\n" (map #(render-turn % width) turns))
      (style/styled "Type a message and press Enter." :faint true))))

;; --- log rendering ----------------------------------------------------------

(defn- log-entry-line [{:keys [level msg]} width]
  (let [oneline (str (format "%-5s " (str/upper-case (name (or level :info))))
                     (str/replace (str msg) #"\s*\n\s*" " "))
        text    (style/truncate oneline width :tail "…")]
    (case level
      (:error :fatal) (style/render (style/style :fg style/red :bold true) text)
      :warn           (style/render (style/style :fg style/yellow) text)
      :debug          (style/render (style/style :faint true) text)
      text)))

(defn- logs-content [logs width]
  (if (seq logs)
    (str/join "\n" (map #(log-entry-line % width) logs))
    (style/styled "(no log entries yet)" :faint true)))

;; --- layout -----------------------------------------------------------------

(defn- panel-lines
  "Vertical lines the log panel occupies: one summary line when collapsed; a
  title line plus the bordered content box when expanded."
  [logs-open?]
  (if logs-open? (+ 1 log-content-lines 2) 1))

(defn- viewport-height
  "Lines available to the conversation viewport: terminal height minus fixed
  chrome (header + 2 blanks + input box + hint = 7) and the log panel. The
  conversation is unbordered, so no border lines are reserved for it."
  [{:keys [height logs-open?]}]
  (max 3 (- (or height 24) (+ 7 (panel-lines logs-open?)))))

;; --- viewport refresh -------------------------------------------------------

(defn- refresh-conversation
  "Rebuild the conversation viewport from db + size, pinned to the latest turn."
  [{:keys [db width viewport] :as model}]
  (assoc model :viewport
         (-> (or viewport (vp/viewport ""))
             (vp/viewport-set-content (conversation-content db (or width 80)))
             (vp/viewport-set-dimensions 0 (viewport-height model))
             (vp/scroll-to-bottom))))

(defn- refresh-logs
  "Rebuild the log viewport from the ring buffer. Tails (scroll to bottom)
  unless the panel is focused and the user has scrolled up — then the current
  position is preserved so logs can be read without being yanked away."
  [{:keys [logs log-viewport focus] :as model}]
  (let [at-bottom? (or (nil? log-viewport) (vp/viewport-at-bottom? log-viewport))
        prev-off   (:y-offset log-viewport)
        vp0        (-> (or log-viewport (vp/viewport ""))
                       (vp/viewport-set-content (logs-content logs (inner-width model)))
                       (vp/viewport-set-dimensions 0 log-content-lines))]
    (assoc model :log-viewport
           (if (and (= focus :logs) (not at-bottom?))
             (vp/viewport-scroll-to vp0 (or prev-off 0))
             (vp/scroll-to-bottom vp0)))))

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn init
  "Return a charm init fn. Returns the initial model plus the startup commands."
  [{:keys [db-ch watch-cmd dispatch! log-ch watch-log-cmd]}]
  (fn []
    [(-> {:db           (db/current-db)
          :db-ch        db-ch
          :dispatch!    dispatch!
          :log-ch       log-ch
          :logs         []
          :logs-open?   false
          :focus        :input
          :input        ""
          :width        80
          :height       24
          :viewport     (vp/viewport "")
          :log-viewport (vp/viewport "")}
         refresh-conversation
         refresh-logs)
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

(defn- append-log [model entry]
  (update model :logs #(vec (take-last log-buffer-size (conj (or % []) entry)))))

(defn- scroll-focused
  "Apply scroll fn `f` to whichever viewport the focus drives: the log panel
  when focus is :logs, otherwise the conversation."
  [model f]
  (let [k (if (= (:focus model) :logs) :log-viewport :viewport)]
    (cond-> model (k model) (update k f))))

(defn update-model [model message]
  (cond
    (and (msg/key-press? message) (msg/key-match? message "ctrl+c"))
    [model charm/quit-cmd]

    (= :runtime/db-updated (:type message))
    [(refresh-conversation (assoc model :db (:db message)))
     (subscribe/watch-db-cmd (:db-ch model))]

    (= :log/appended (:type message))
    [(refresh-logs (append-log model (:entry message)))
     (subscribe/watch-log-cmd (:log-ch model))]

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

    ;; Scroll keys drive the focused region.
    (msg/key-match? message :page-up)    [(scroll-focused model vp/scroll-page-up) nil]
    (msg/key-match? message :page-down)  [(scroll-focused model vp/scroll-page-down) nil]
    (msg/key-match? message "ctrl+u")    [(scroll-focused model vp/scroll-half-page-up) nil]
    (msg/key-match? message "ctrl+d")    [(scroll-focused model vp/scroll-half-page-down) nil]

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

;; A focused region gets a thick border; otherwise a rounded one. Borders are
;; never coloured — charm downgrades box-drawing edges to ASCII when a border
;; :fg/:bg is set.
(defn- border-for [focused?]
  (if focused? style/thick-border style/rounded-border))

(defn- conversation-view [model]
  (if-let [vp* (:viewport model)]
    (vp/viewport-view vp*)
    (conversation-content (:db model) (or (:width model) 80))))

(defn- cursor []
  (style/styled " " :reverse true))

(defn- visible-window
  "Trailing window of `s` that fits in `avail` columns, prefixing an ellipsis
  when text scrolled off the left. The result is never wider than `avail`."
  [s avail]
  (if (<= (count s) avail)
    s
    (str "…" (subs s (- (count s) (dec avail))))))

(defn- input-view [{:keys [input width focus]}]
  (let [inner (max 20 (- (or width 80) 4))
        avail (max 1 (- inner 5))                 ; room for input text + cursor
        body  (if (str/blank? input)
                (str (cursor) (style/styled placeholder :faint true))
                (str (visible-window input avail) (cursor)))]
    (style/render
     (style/style :border  (border-for (= :input focus))
                  :padding [0 1]
                  :width   inner)
     (str (style/styled "›" :fg accent :bold true) " " body))))

(defn- hint []
  (style/styled "Enter send · Tab focus · PgUp/PgDn scroll · ^L logs · ^C quit" :faint true))

(defn- log-panel [{:keys [logs logs-open? log-viewport focus] :as model}]
  (let [iw (inner-width model)]
    (if logs-open?
      (let [focused? (= focus :logs)
            title    (style/styled (str "▾ logs (" (count logs) ") · Tab focus · ^L collapse")
                                   :faint (not focused?) :bold focused? :fg (when focused? accent))
            content  (if log-viewport (vp/viewport-view log-viewport) (logs-content logs iw))]
        (str title "\n" (style/render (style/style :border (border-for focused?) :width iw) content)))
      (let [n     (count logs)
            warns (count (filter #(#{:warn :error :fatal} (:level %)) logs))]
        (style/styled (str "▸ logs (" n (when (pos? warns) (str ", " warns " warn/err")) ") · ^L expand")
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
