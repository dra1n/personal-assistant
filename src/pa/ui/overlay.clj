(ns pa.ui.overlay
  "A reusable overlay list surface: a bordered list rendered above the input,
  each row a left label with a right-aligned faint hint, the highlighted row in
  reverse video, and a faint help line for the highlight beneath. Presentation
  only — a pure fn of rows + highlight index + width. Built for the command
  selector and the future :select argument picker (they differ only in the rows
  and help they pass in).

  A row is {:label <string> :hint <string?>}. The list scrolls when it exceeds
  max-rows, keeping the highlight in view. height mirrors what overlay-list
  renders, so the layout sizing (pa.ui.view) and the render never drift."
  (:require [charm.style.core :as style]
            [clojure.string :as str]))

(def ^:private max-rows 8)   ; rows shown before the list scrolls around the highlight

(defn- visible-count [n]
  (if (zero? n) 1 (min n max-rows)))

(defn height
  "The number of vertical lines overlay-list occupies for these rows/help: two
  border rows, the visible row count (1 for the empty-match line), plus a blank
  + help line when a help string is present and there are rows."
  [{:keys [rows help]}]
  (+ 2
     (visible-count (count rows))
     (if (and help (seq rows)) 2 0)))

(defn- window-start
  "First row index to show so `index` stays visible within max-rows."
  [n index]
  (if (<= n max-rows)
    0
    (-> index (- (quot max-rows 2)) (max 0) (min (- n max-rows)))))

(defn- render-row
  "One row padded to text-width `tw`: label left, hint right. Layout is computed
  on plain lengths, then styling is applied — the highlighted row reversed, an
  ordinary row's hint faint."
  [{:keys [label hint]} highlighted? tw]
  (let [hint (or hint "")
        lbl  (style/truncate label (max 1 (- tw (count hint) 1)) :tail "…")
        gap  (apply str (repeat (max 1 (- tw (count lbl) (count hint))) " "))]
    (if highlighted?
      (style/styled (str lbl gap hint) :reverse true)
      (str lbl gap (style/styled hint :faint true)))))

(defn overlay-list
  "Render the overlay box for {:rows :index :help :inner-width}. inner-width is
  the box width (terminal inner width); the text width is that minus the two
  padding columns. Returns the bordered string; height above gives its line
  count for layout."
  [{:keys [rows index help inner-width]}]
  (let [tw    (max 8 (- inner-width 2))
        n     (count rows)
        body  (if (zero? n)
                (style/styled "no matching commands" :faint true)
                (let [start (window-start n index)
                      shown (subvec (vec rows) start (+ start (visible-count n)))]
                  (->> shown
                       (map-indexed (fn [i row] (render-row row (= (+ start i) index) tw)))
                       (str/join "\n"))))
        help* (when (and help (pos? n))
                (style/styled (style/truncate help tw :tail "…") :faint true))]
    (style/render (style/style :border  style/rounded-border
                               :padding [0 1]
                               :width   inner-width)
                  (cond-> body help* (str "\n\n" help*)))))
