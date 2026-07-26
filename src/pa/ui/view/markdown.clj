(ns pa.ui.view.markdown
  "Render Markdown to charm-styled terminal text.

  Parses with `nextjournal.markdown` (a Clojure-data tree over commonmark-java),
  then walks the AST emitting ANSI via `charm.style`. Pure: `(render md-string
  width)` → a styled string wrapped to `width` columns, ready to drop into a
  committed assistant turn behind the `:markdown` toggle. No runtime-state
  access — the flag lookup and result caching live in the view/model layer.

  Inline styling is done at the character level (a seq of `[char style-map]`
  pairs) so word-wrapping measures *visible* length — ANSI escapes never corrupt
  the column math — and punctuation stays attached across style runs (e.g. the
  '.' after inline `code` keeps no stray space)."
  (:require [charm.style.core :as style]
            [clojure.string :as str]
            [nextjournal.markdown :as md]))

;; Palette — kept from the render spike for now (cyan headings, magenta inline
;; code, blue links). Note it overlaps the app's cyan accent; revisiting that is
;; a deliberate follow-up, not this phase.
(def ^:private code-color    style/magenta)
(def ^:private link-color    style/blue)
(def ^:private heading-color style/cyan)

;; --- inline -----------------------------------------------------------------

(defn- with-style
  "Merge `extra` style onto every [char style] pair (leaving :break markers)."
  [extra chs]
  (map (fn [x] (if (= :break (first x)) x [(first x) (merge extra (second x))])) chs))

(defn- inline-chars
  "Flatten an inline node into a seq of [char style-map] pairs; a hard break
  becomes the marker [:break]. A softbreak (a source newline within a paragraph)
  becomes a single space so adjacent words don't merge."
  [node]
  (case (:type node)
    :text          (map (fn [c] [c {}]) (:text node))
    :softbreak     [[\space {}]]
    :hardbreak     [[:break]]
    :strong        (with-style {:bold true}                     (mapcat inline-chars (:content node)))
    :em            (with-style {:italic true}                   (mapcat inline-chars (:content node)))
    ;; charm has no SGR 9 (strikethrough); fall back to faint.
    :strikethrough (with-style {:faint true}                    (mapcat inline-chars (:content node)))
    :monospace     (with-style {:fg code-color}                 (mapcat inline-chars (:content node)))
    :link          (with-style {:underline true :fg link-color} (mapcat inline-chars (:content node)))
    ;; default: recurse into children (or contribute nothing)
    (mapcat inline-chars (:content node))))

(defn- style-str [s style-map]
  (if (seq style-map) (apply style/styled s (mapcat identity style-map)) s))

(defn- chars->str
  "Render a seq of [char style] into a styled string, grouping consecutive equal
  styles into single style runs."
  [chs]
  (->> (partition-by second chs)
       (map (fn [grp] (style-str (apply str (map first grp)) (second (first grp)))))
       (apply str)))

(defn- hard-split
  "Split a single over-long word (a vec of [char style]) into width-sized chunks
  so it never overruns the frame."
  [word width]
  (if (<= (count word) width) [word] (mapv vec (partition-all width word))))

(defn- wrap-words
  "Greedily pack words (each a vec of [char style]) into lines of `width`,
  hard-splitting any word longer than the width. Returns a seq of word-seqs."
  [words width]
  (let [words (mapcat #(hard-split % width) words)]
    (loop [ws words cur [] len 0 lines []]
      (if-let [w (first ws)]
        (let [wl (count w) sep (if (pos? len) 1 0)]
          (if (and (pos? len) (> (+ len sep wl) width))
            (recur ws [] 0 (conj lines cur))
            (recur (rest ws) (conj cur w) (+ len sep wl) lines)))
        (cond-> lines (seq cur) (conj cur))))))

(defn- nonspace? [[c]] (not (or (= c \space) (= c \tab) (= c :break))))

(defn- inline-lines
  "Wrapped, styled lines (seq of strings) for an inline container's children.
  Splits on hard breaks, wraps each segment independently."
  ([content width] (inline-lines content width {}))
  ([content width extra]
   (let [chs (with-style extra (mapcat inline-chars content))]
     (->> (partition-by #(= :break (first %)) chs)
          (remove #(= :break (ffirst %)))
          (mapcat (fn [seg]
                    (let [words (->> (partition-by nonspace? seg)
                                     (filter #(nonspace? (first %)))
                                     (map vec))]
                      (map (fn [ws] (str/join " " (map chars->str ws)))
                           (wrap-words words width)))))))))

(defn- inline-plain
  "The visible text of an inline container, unstyled — used for table cells."
  [content]
  (->> (mapcat inline-chars content) (remove #(= :break (first %))) (map first) (apply str)))

;; --- blocks -----------------------------------------------------------------

(declare render-block)

(defn- render-blocks [nodes width sep]
  (->> nodes (map #(render-block % width)) (remove str/blank?) (str/join sep)))

(defn- prefix-lines [first-prefix cont-prefix lines]
  (map-indexed (fn [i l] (str (if (zero? i) first-prefix cont-prefix) l)) lines))

(defn- heading [{:keys [heading-level content]} width]
  (let [tag   (style/styled (str (apply str (repeat heading-level "#")) " ") :faint true)
        pad   (apply str (repeat (inc heading-level) " "))
        lines (inline-lines content (max 1 (- width (inc heading-level)))
                            {:bold true :fg heading-color})]
    (str/join "\n" (prefix-lines tag pad lines))))

(defn- code-block [{:keys [language content]} width]
  (let [gutter (style/styled "│ " :faint true)
        code   (str/split-lines (str/replace (apply str (map :text content)) #"\n\z" ""))
        body   (map #(str gutter (style/truncate % (max 1 (- width 2)) :tail "…")) code)]
    (str/join "\n" (cond->> body
                     language (cons (style/styled (str "```" language) :faint true))))))

(defn- blockquote [{:keys [content]} width]
  (let [gutter (style/styled "│ " :faint true)]
    (->> (str/split-lines (render-blocks content (max 1 (- width 2)) "\n"))
         (map #(str gutter %))
         (str/join "\n"))))

(defn- list-item [item width marker]
  (let [indent (apply str (repeat (count marker) " "))
        inner  (str/split-lines (render-blocks (:content item) (max 1 (- width (count marker))) "\n"))]
    (str/join "\n" (prefix-lines (style/styled marker :faint true) indent inner))))

(defn- bullet-list [{:keys [content]} width]
  (str/join "\n" (map #(list-item % width "• ") content)))

(defn- numbered-list [{:keys [content attrs]} width]
  (let [start (get attrs :start 1)]
    (str/join "\n" (map-indexed (fn [i item] (list-item item width (str (+ start i) ". "))) content))))

(defn- ruler [width]
  (style/styled (apply str (repeat width "─")) :faint true))

(defn- render-table [{:keys [content]} _width]
  (let [head  (some #(when (= :table-head (:type %)) %) content)
        body  (some #(when (= :table-body (:type %)) %) content)
        cells (fn [row] (mapv #(inline-plain (:content %)) (:content row)))
        hrow  (when head (cells (first (:content head))))
        brows (mapv cells (:content body))
        rows  (cond-> [] hrow (conj hrow) :always (into brows))
        ncol  (apply max 0 (map count rows))
        wid   (mapv (fn [c] (apply max 1 (map #(count (nth % c "")) rows))) (range ncol))
        fmt   (fn [cs] (str "│ " (str/join " │ " (map #(format (str "%-" %2 "s") (or %1 "")) (concat cs (repeat "")) wid)) " │"))
        divid (str "├─" (str/join "─┼─" (map #(apply str (repeat % "─")) wid)) "─┤")]
    (str/join "\n" (cond-> []
                     hrow  (conj (style/styled (fmt hrow) :bold true) divid)
                     :always (into (map fmt brows))))))

(defn- render-block [node width]
  (case (:type node)
    :heading       (heading node width)
    (:paragraph
     :plain)       (str/join "\n" (inline-lines (:content node) width))
    :code          (code-block node width)
    :blockquote    (blockquote node width)
    :bullet-list   (bullet-list node width)
    :numbered-list (numbered-list node width)
    :ruler         (ruler width)
    :table         (render-table node width)
    (if (:content node) (render-blocks (:content node) width "\n\n") "")))

;; --- public -----------------------------------------------------------------

(defn render
  "Render a Markdown string to charm-styled terminal text wrapped to `width`.
  Blocks are separated by a blank line. Pure — safe to cache on
  `[md-string width]`."
  [markdown width]
  (render-blocks (:content (md/parse (str markdown))) (max 4 width) "\n\n"))
