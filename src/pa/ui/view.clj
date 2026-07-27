(ns pa.ui.view
  "Top-level rendering for the terminal UI: the conversation, log panel,
  notification banner, and header/chrome, plus the `view` fn that composes the
  whole frame (delegating the input buffer to pa.ui.input.view and the selector
  overlay to pa.ui.selector.view).

  Pure presentation: turns the charm model (a plain data map) into the string
  charm displays. Holds no state and references no update logic — it only reads
  the model map. Sizing lives in pa.ui.view.layout; shared render helpers in
  pa.ui.view.common. pa.ui.app depends on this namespace; this namespace never
  depends back on app."
  (:require [charm.components.viewport :as vp]
            [charm.style.core :as style]
            [clojure.string :as str]
            [pa.state.queries :as queries]
            [pa.ui.input.view :as input-view]
            [pa.ui.selector.view :as selector-view]
            [pa.ui.view.common :as common]
            [pa.ui.view.layout :as layout]
            [pa.ui.view.markdown :as markdown]))

;; --- conversation content ---------------------------------------------------

(defn- wrap-line
  "Greedily word-wrap a single (ANSI-free) line to `width` columns. A word
  longer than `width` (a URL, a pr-str blob) is hard-split into width-sized
  chunks — an overflowing line would push past the box border and break the
  fixed layout's height math."
  [line width]
  (if (<= (count line) width)
    [line]
    (loop [words (mapcat #(map str/join (partition-all width %))
                         (str/split line #"\s+"))
           cur "" out []]
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

(defn- tool-call-line
  "One truncated line per tool call — arguments can be enormous (write-file
  contents, long URLs) and are a gist, not the record; the full call is in
  the log panel and the event log."
  [{:keys [name arguments]} width]
  (style/truncate (str "→ " (subs (str name) 1) " " (pr-str arguments))
                  width :tail "…"))

(defn- faint-lines
  "Word-wrap s to width and apply the faint style per physical line, so the
  styling survives the line breaks."
  [s width]
  (->> (wrap-text s width)
       str/split-lines
       (map #(style/styled % :faint true))
       (str/join "\n")))

(def ^:private tool-output-max-lines
  "Physical lines of a tool-result turn shown before collapsing the rest.
  Tool output (a fetched webpage, a file's contents) can run to thousands of
  lines of machine chatter that would drown the conversation; the full text
  still reaches the LLM and the event log."
  3)

(defn- collapsed-tool-output
  "A tool-result turn's content: faint, capped at tool-output-max-lines
  wrapped lines, with a trailing count of what was elided."
  [content width]
  (let [lines (str/split-lines (wrap-text content width))
        shown (take tool-output-max-lines lines)
        more  (- (count lines) tool-output-max-lines)]
    (->> (cond-> (mapv #(style/styled % :faint true) shown)
           (pos? more) (conj (style/styled (str "… +" more " more lines") :faint true)))
         (str/join "\n"))))

(defn- render-turn [{:keys [role content tool-calls pending? markdown?]} width names]
  (let [w     (max 1 width)
        label (case role
                :user      (style/styled (or (:user names) "You")            :fg common/accent :bold true)
                :assistant (style/styled (or (:assistant names) "Assistant") :fg style/green :bold true)
                (style/styled (name (or role :system)) :faint true))
        ;; An assistant turn that only calls a tool has blank content; show the
        ;; call(s) faintly instead of an empty bubble. A turn may carry both
        ;; (some models add commentary alongside a tool call). `markdown?` is set
        ;; by conversation-content only on committed assistant turns when the
        ;; :markdown setting is on — tool output and the live stream never carry
        ;; it, so they keep their plain rendering.
        parts (cond-> []
                pending?
                (conj (style/styled "thinking…" :faint true))
                (not (str/blank? content))
                (conj (cond
                        (= :tool role) (collapsed-tool-output (str content) w)
                        markdown?      (markdown/render (str content) w)
                        :else          (wrap-text (str content) w)))
                (seq tool-calls)
                (conj (faint-lines (str/join "\n" (map #(tool-call-line % w) tool-calls)) w)))]
    ;; One blank line under the label so it stands out as a header; the gap
    ;; between turns (below) is wider, so the label groups with its own body.
    (str label "\n\n" (str/join "\n\n" parts))))

(defn conversation-empty?
  "True when there are no committed turns and nothing streaming — the state in
  which the borderless empty-conversation placeholder is shown instead of the
  bordered, focusable pane."
  [{:keys [db streaming]}]
  (and (empty? (queries/conversation db)) (str/blank? streaming)))

(defn conversation-content
  "Render the committed conversation, plus the in-progress streamed response
  (if any) as a trailing live assistant turn. When `pending?` is true and no
  deltas have arrived yet, a faint thinking… placeholder turn is shown instead
  (the wait for the first token, or for a tool call to finish). Turn labels
  use the identity names when set, falling back to capitalized
  \"You\"/\"Assistant\". Returns an empty string when there are no turns — the
  empty state is handled by the placeholder view, not here."
  ([db width streaming] (conversation-content db width streaming false))
  ([db width streaming pending?]
   (let [md?    (queries/setting db :markdown)
         ;; Tag committed assistant turns so render-turn markdown-renders them.
         ;; Guarded on map? — non-map sentinel entries pass through untouched.
         ;; Only committed turns are tagged; the live stream and pending turns
         ;; below are conj'd without the flag, so they stay plain.
         tag    (fn [t] (if (and md? (map? t) (= :assistant (:role t)))
                          (assoc t :markdown? true)
                          t))
         turns (cond-> (mapv tag (queries/conversation db))
                 (not (str/blank? streaming))
                 (conj {:role :assistant :content streaming})

                 (and pending? (str/blank? streaming) (seq (queries/conversation db)))
                 (conj {:role :assistant :pending? true}))
         names {:user      (queries/user-name db)
                :assistant (queries/assistant-name db)}]
     ;; Two blank lines between turns — wider than the label's own bottom gap.
     (str/join "\n\n\n" (map #(render-turn % width names) turns)))))

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

;; --- notifications ------------------------------------------------------------

(defn- notification-text
  "Single-width characters only — a double-width glyph (an emoji clock) would
  throw off charm's char-count padding and shift the box border."
  [{:keys [type payload]}]
  (str (case type :reminder "Reminder: " "• ")
       (or (:text payload) (pr-str payload))))

(defn- notification-banner
  "The pending-notifications panel, or nil when there are none. Reminders
  fire into :ui/notifications (see :reminder/due); this is where they become
  visible. A bordered box like the other panels; the first line carries the
  dismiss hint."
  [model]
  (let [notes (queries/notifications (:db model))]
    (when (seq notes)
      (let [tw    (layout/text-width model)
            shown (take layout/notification-max-rows notes)
            more  (- (count notes) layout/notification-max-rows)
            line  (fn [i note]
                    (let [hint (if (zero? i) " · ^X dismiss" "")]
                      (str (style/styled (style/truncate (notification-text note)
                                                         (max 1 (- tw (count hint)))
                                                         :tail "…")
                                         :fg style/yellow :bold true)
                           (style/styled hint :faint true))))]
        (style/render (style/style :border  style/rounded-border
                                   :padding common/box-padding
                                   :width   (layout/inner-width model))
                      (str/join "\n"
                                (cond-> (vec (map-indexed line shown))
                                  (pos? more) (conj (style/styled (str "… +" more " more") :faint true)))))))))

;; --- header / chrome --------------------------------------------------------

(def ^:private empty-conversation-hint "Type a message and press Enter.")

(def ^:private tips
  ["Tab cycles focus · Esc jumps back to the input"
   "Press ^L to peek at the live log panel"
   "↑/↓ scroll whichever pane is focused"
   "Set your own header line via `motd` in user.md"
   "Your assistant's persona lives in identity.md"
   "Readline keys work: ←/→ · Ctrl+A/E · Alt+F/B · Ctrl+W/K/U"])

(defn random-tip
  "A randomly chosen usage tip — the header MOTD fallback when the user hasn't
  set `motd` in user.md. Picked once at startup (app/init) and held in the
  model so it stays stable for the session rather than flickering each render."
  []
  (rand-nth tips))

(defn- dim-glyph
  "Faint via the raw SGR escape. charm's styler downgrades box-drawing *edges*
  (─ │) to ASCII whenever an attribute is applied (see pa.ui.view.common/border-for),
  so we emit the dim escape by hand around the glyphs to keep them Unicode."
  [s]
  (str "\u001b[2m" s "\u001b[0m"))

;; Split header: a rounded box, wordmark (plus the active LLM model, faint) on
;; the left and the message-of-the-day (user's `motd`, else the session's
;; fallback tip) on the right of one row. The border is hand-drawn and dimmed
;; (dim-glyph) to match the faint MOTD.
(defn- header [model]
  (let [iw       (layout/inner-width model)
        tw       (layout/text-width model)
        wordmark "Personal Assistant"
        llm      (:llm-model model)
        left-len (cond-> (+ 2 (count wordmark))          ; ✦ + space + wordmark
                   llm (+ 3 (count llm)))                ; + space + "· " + model
        left     (str (style/styled "✦" :fg common/accent :bold true) " "
                      (style/styled wordmark :bold true)
                      (when llm
                        (str " " (style/styled (str "· " llm) :faint true))))
        raw      (or (queries/user-motd (:db model)) (:motd-fallback model) (first tips))
        motd     (style/truncate raw (max 1 (- tw left-len 1)) :tail "…")
        gap      (apply str (repeat (max 1 (- tw left-len (count motd))) " "))
        rule     (dim-glyph (apply str (repeat iw "─")))]
    (str (dim-glyph "╭") rule (dim-glyph "╮") "\n"
         (dim-glyph "│") " " left gap (style/styled motd :faint true) " " (dim-glyph "│") "\n"
         (dim-glyph "╰") rule (dim-glyph "╯"))))

(defn- pad-lines
  "Append blank lines so `s` spans exactly `n` lines (no-op if already ≥ n).
  charm's `:height` doesn't pad, so we fill manually — this is what makes the
  conversation box always occupy its full slot, keeping the layout fixed and
  the input pinned to the bottom regardless of how much has been said. The
  filler is a single space, not \"\": charm drops trailing *empty* lines when
  rendering borders and joining blocks."
  [s n]
  (let [lines (str/split-lines s)]
    (->> (concat lines (repeat " "))
         (take (max n (count lines)))
         (str/join "\n"))))

(defn- conversation-view [{:keys [viewport focus] :as model}]
  (let [content (if viewport
                  (vp/viewport-view viewport)
                  (conversation-content (:db model) (layout/text-width model) (:streaming model)
                                        (and (:streaming-open? model)
                                             (str/blank? (:streaming model)))))]
    (style/render (style/style :border  (common/border-for (= :conversation focus))
                               :padding common/box-padding
                               :width   (layout/inner-width model))
                  (pad-lines content (layout/viewport-height model)))))

;; Shown before the first message: a borderless, unfocusable hint centred in
;; the same rectangle the conversation box would occupy, so the input below
;; stays put. No border/focus styling — that's what looked wonky empty.
(defn- empty-conversation-view [model]
  (let [w    (or (:width model) 80)
        h    (+ (layout/viewport-height model) 2)     ; match the bordered box's outer height
        lead (apply str (repeat (max 0 (quot (- w (count empty-conversation-hint)) 2)) \space))
        line (style/styled (str lead empty-conversation-hint) :faint true)
        top  (quot (dec h) 2)]
    ;; Space-filled blanks, not "" — charm drops trailing empty lines on join.
    (str/join "\n" (concat (repeat top " ") [line] (repeat (- h top 1) " ")))))

(defn- hint []
  (style/styled "Enter send · Tab/Esc focus · ↑/↓ scroll · ^L logs · ^C quit" :faint true))

(defn- log-panel [{:keys [logs logs-open? log-viewport focus] :as model}]
  (let [iw (layout/inner-width model)]
    (if logs-open?
      (let [focused? (= focus :logs)
            title    (style/styled (str "▾ logs (" (count logs) ") · Tab focus · ^L collapse")
                                   :faint (not focused?) :bold focused? :fg (when focused? common/accent))
            content  (if log-viewport (vp/viewport-view log-viewport) (logs-content logs (layout/text-width model)))]
        (str title "\n" (style/render (style/style :border  (common/border-for focused?)
                                                   :padding common/box-padding
                                                   :width   iw)
                                      content)))
      (let [n     (count logs)
            warns (count (filter #(#{:warn :error :fatal} (:level %)) logs))]
        (style/styled (str "▸ logs (" n (when (pos? warns) (str ", " warns " warn/err")) ") · ^L expand")
                      :faint true)))))

(defn view [model]
  (apply style/join-vertical
         :left
         (concat
          [(header model)]
          (when-let [banner (notification-banner model)] [banner])
          [(if (conversation-empty? model)
             (empty-conversation-view model)
             (conversation-view model))]
          ;; The selector overlay sits directly above the input while open; its
          ;; height is carved out of the conversation viewport (layout/selector-lines).
          (when-let [sel (selector-view/selector-overlay model)] [sel])
          [(input-view/input-view model)
           (hint)
           (log-panel model)])))
