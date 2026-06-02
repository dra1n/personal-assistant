(ns pa.ui.view
  "Rendering and layout for the terminal UI.

  Pure presentation: turns the charm model (a plain data map) into the string
  charm displays. Holds no state and references no update logic — it only
  reads the model map. pa.ui.app depends on this namespace (its viewports
  store pre-rendered content); this namespace never depends back on app."
  (:require [charm.components.viewport :as vp]
            [charm.style.core :as style]
            [clojure.string :as str]
            [pa.state.queries :as queries]))

(def ^:private accent style/cyan)
(def log-content-lines 8)   ; log rows visible inside the expanded panel

(def ^:private box-padding
  "Inner horizontal padding for the bordered content boxes (1 column each
  side), so text doesn't sit flush against the side borders."
  [0 1])

(defn inner-width
  "Content width inside a full-width bordered box (terminal width minus the
  two border columns). This is the box's `:width`; the padding is carved out
  of it, leaving `text-width` for the text itself."
  [{:keys [width]}]
  (max 10 (- (or width 80) 2)))

(defn text-width
  "Wrappable width for text inside a padded, bordered box: the inner width
  minus the two horizontal padding columns."
  [model]
  (max 8 (- (inner-width model) 2)))

;; --- conversation content ---------------------------------------------------

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

(defn- render-turn [{:keys [role content]} width names]
  (let [label (case role
                :user      (style/styled (or (:user names) "You")            :fg accent :bold true)
                :assistant (style/styled (or (:assistant names) "Assistant") :fg style/green :bold true)
                (style/styled (name (or role :system)) :faint true))]
    ;; One blank line under the label so it stands out as a header; the gap
    ;; between turns (below) is wider, so the label groups with its own body.
    (str label "\n\n" (wrap-text (str content) (max 1 width)))))

(defn conversation-empty?
  "True when there are no committed turns and nothing streaming — the state in
  which the borderless empty-conversation placeholder is shown instead of the
  bordered, focusable pane."
  [{:keys [db streaming]}]
  (and (empty? (queries/conversation db)) (str/blank? streaming)))

(defn conversation-content
  "Render the committed conversation, plus the in-progress streamed response
  (if any) as a trailing live assistant turn. Turn labels use the identity
  names when set, falling back to capitalized \"You\"/\"Assistant\". Returns an
  empty string when there are no turns — the empty state is handled by the
  placeholder view, not here."
  [db width streaming]
  (let [turns (cond-> (vec (queries/conversation db))
                (not (str/blank? streaming)) (conj {:role :assistant :content streaming}))
        names {:user      (queries/user-name db)
               :assistant (queries/assistant-name db)}]
    ;; Two blank lines between turns — wider than the label's own bottom gap.
    (str/join "\n\n\n" (map #(render-turn % width names) turns))))

;; --- log content ------------------------------------------------------------

(defn- log-entry-line [{:keys [level msg]} width]
  (let [oneline (str (format "%-5s " (str/upper-case (name (or level :info))))
                     (str/replace (str msg) #"\s*\n\s*" " "))
        text    (style/truncate oneline width :tail "…")]
    (case level
      (:error :fatal) (style/render (style/style :fg style/red :bold true) text)
      :warn           (style/render (style/style :fg style/yellow) text)
      :debug          (style/render (style/style :faint true) text)
      text)))

(defn logs-content [logs width]
  (if (seq logs)
    (str/join "\n" (map #(log-entry-line % width) logs))
    (style/styled "(no log entries yet)" :faint true)))

;; --- layout sizing ----------------------------------------------------------

(defn- panel-lines
  "Vertical lines the log panel occupies: one summary line when collapsed; a
  title line plus the bordered content box when expanded."
  [logs-open?]
  (if logs-open? (+ 1 log-content-lines 2) 1))

(defn viewport-height
  "Lines available inside the conversation box's viewport: terminal height
  minus fixed chrome (header + 2 blanks + input box + hint = 7), the log
  panel, and the conversation box's own two border rows."
  [{:keys [height logs-open?]}]
  (max 3 (- (or height 24) (+ 9 (panel-lines logs-open?)))))

;; --- view -------------------------------------------------------------------

(def ^:private placeholder "Ask me anything…")
(def ^:private empty-conversation-hint "Type a message and press Enter.")

(defn- header []
  (style/styled "  personal assistant  " :bold true :fg style/black :bg accent))

;; A focused region gets a thick border; otherwise a rounded one. Borders are
;; never coloured — charm downgrades box-drawing edges to ASCII when a border
;; :fg/:bg is set.
(defn- border-for [focused?]
  (if focused? style/thick-border style/rounded-border))

(defn- conversation-view [{:keys [viewport focus] :as model}]
  (let [content (if viewport
                  (vp/viewport-view viewport)
                  (conversation-content (:db model) (text-width model) (:streaming model)))]
    (style/render (style/style :border  (border-for (= :conversation focus))
                               :padding box-padding
                               :width   (inner-width model))
                  content)))

;; Shown before the first message: a borderless, unfocusable hint centred in
;; the same rectangle the conversation box would occupy, so the input below
;; stays put. No border/focus styling — that's what looked wonky empty.
(defn- empty-conversation-view [model]
  (style/render (style/style :width  (or (:width model) 80)
                             :height (+ (viewport-height model) 2)
                             :align  :center
                             :valign :center)
                (style/styled empty-conversation-hint :faint true)))

(defn- cursor []
  (style/styled " " :reverse true))

(defn- visible-window
  "Trailing window of `s` that fits in `avail` columns, prefixing an ellipsis
  when text scrolled off the left. The result is never wider than `avail`."
  [s avail]
  (if (<= (count s) avail)
    s
    (str "…" (subs s (- (count s) (dec avail))))))

(defn- input-view [{:keys [input focus] :as model}]
  (let [inner (inner-width model)                 ; full width, flush with the other boxes
        avail (max 1 (- inner 5))                 ; room for input text + cursor
        body  (if (str/blank? input)
                (str (cursor) (style/styled placeholder :faint true))
                (str (visible-window input avail) (cursor)))]
    (style/render
     (style/style :border  (border-for (= :input focus))
                  :padding box-padding
                  :width   inner)
     (str (style/styled "›" :fg accent :bold true) " " body))))

(defn- hint []
  (style/styled "Enter send · Tab/Esc focus · ↑/↓ scroll · ^L logs · ^C quit" :faint true))

(defn- log-panel [{:keys [logs logs-open? log-viewport focus] :as model}]
  (let [iw (inner-width model)]
    (if logs-open?
      (let [focused? (= focus :logs)
            title    (style/styled (str "▾ logs (" (count logs) ") · Tab focus · ^L collapse")
                                   :faint (not focused?) :bold focused? :fg (when focused? accent))
            content  (if log-viewport (vp/viewport-view log-viewport) (logs-content logs (text-width model)))]
        (str title "\n" (style/render (style/style :border  (border-for focused?)
                                                   :padding box-padding
                                                   :width   iw)
                                      content)))
      (let [n     (count logs)
            warns (count (filter #(#{:warn :error :fatal} (:level %)) logs))]
        (style/styled (str "▸ logs (" n (when (pos? warns) (str ", " warns " warn/err")) ") · ^L expand")
                      :faint true)))))

(defn view [model]
  (style/join-vertical
   :left
   (header)
   ""
   (if (conversation-empty? model)
     (empty-conversation-view model)
     (conversation-view model))
   ""
   (input-view model)
   (hint)
   (log-panel model)))
