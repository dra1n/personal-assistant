(ns pa.ui.view.layout
  "Frame sizing for the terminal UI: box widths and the vertical line-count math
  that keeps the rendered frame exactly terminal-height. Pure — a fn of the charm
  model map plus the registry/queries the selector reads.

  Owns all sizing so it never has to depend on a render namespace: the feature
  views (pa.ui.input.view, pa.ui.selector.view) and the aggregator (pa.ui.view)
  depend on this for widths and line counts, not the reverse. It also owns
  selector-spec, the selector overlay's view-model, so selector-lines can size the
  overlay without pulling in the render."
  (:require [clojure.string :as str]
            [pa.state.queries :as queries]
            [pa.ui.selector.sources :as sources]
            [pa.ui.selector.state :as selector]
            [pa.ui.view.overlay :as overlay]))

(def log-content-lines 8)   ; log rows visible inside the expanded panel

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

;; --- notifications ----------------------------------------------------------

(def notification-max-rows 3)

(defn notification-lines
  "Vertical lines the notification panel occupies under the header: one per
  pending notification (capped at notification-max-rows, plus an overflow
  line) inside a bordered box (2 border rows). 0 when nothing is pending
  (no panel)."
  [{:keys [db]}]
  (let [n (count (queries/notifications db))]
    (cond
      (zero? n)                   0
      (> n notification-max-rows) (+ 3 notification-max-rows)
      :else                       (+ 2 n))))

;; --- command selector overlay -----------------------------------------------

(defn selector-spec
  "The overlay's data — {:rows :index :help} — or nil when it is closed. Shared
  by the layout sizing (selector-lines) and the render
  (pa.ui.selector.view/selector-overlay) so the two never drift. The rows come
  from whichever source the buffer is invoking (a slash command or an
  @-mention); help is the highlighted row's description."
  [{:keys [selector input] :as model}]
  (let [source (sources/active model)]
    (when (selector/open? source selector input)
      (let [rows  (vec (selector/matches source input))
            index (get selector :selector/index 0)]
        {:rows  (mapv #(select-keys % [:label :hint]) rows)
         :index index
         :help  (:help (get rows index))}))))

(defn selector-lines
  "Vertical lines the selector overlay occupies (0 when closed). Subtracted from
  the conversation viewport height so the frame stays exactly terminal-height
  while the overlay is open, like the notification panel."
  [model]
  (if-let [spec (selector-spec model)]
    (overlay/height spec)
    0))

;; --- input ------------------------------------------------------------------

(defn input-line-count
  "Visual lines occupied by the input buffer. Returns 1 for blank or
  single-line input. Multiline buffers are hard-wrapped (character chunks,
  not word wrap — exact cursor arithmetic needs a lossless mapping), so each
  newline-delimited segment occupies (inc (quot len avail)) rows: a segment
  whose length is an exact multiple of avail carries an empty continuation
  row for the cursor to wrap onto. Must stay in lockstep with the row
  construction in pa.ui.input.view/multiline-with-cursor."
  [{:keys [input] :as model}]
  (if (or (str/blank? input) (not (str/includes? input "\n")))
    1
    (let [avail (max 1 (- (inner-width model) 5))]
      (->> (str/split input #"\n" -1)
           (map #(inc (quot (count %) avail)))
           (reduce +)))))

;; --- overall layout ---------------------------------------------------------

(defn- panel-lines
  "Vertical lines the log panel occupies: one summary line when collapsed; a
  title line plus the bordered content box when expanded."
  [logs-open?]
  (if logs-open? (+ 1 log-content-lines 2) 1))

(defn viewport-height
  "Lines available inside the conversation box's viewport: terminal height
  minus fixed chrome (3-row header box + input box borders + hint = 6), the
  log panel, the notification panel (when present), and the conversation
  box's own two border rows. Panels stack directly — no blank rows between
  them. The input box height is dynamic — it grows when the buffer contains
  newlines."
  [{:keys [height logs-open?] :as model}]
  (max 3 (- (or height 24) (+ 8
                              (input-line-count model)
                              (panel-lines logs-open?)
                              (notification-lines model)
                              (selector-lines model)))))
