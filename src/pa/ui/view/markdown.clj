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
    :link          (let [label (with-style {:underline true :fg link-color}
                                            (mapcat inline-chars (:content node)))
                         href  (get-in node [:attrs :href])
                         ;; the plain label text, to skip the URL when it would
                         ;; just repeat the label (an autolink like <http://…>)
                         shown (apply str (map first label))]
                     (if (and href (not= href shown))
                       (concat label (map (fn [c] [c {:faint true}]) (str " (" href ")")))
                       label))
    ;; a superscript-style reference marker, e.g. [1], pointing at a definition
    ;; rendered in the footnotes section at the end of the document.
    :footnote-ref  (map (fn [c] [c {:fg link-color :faint true}]) (str "[" (:label node) "]"))
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

(defn- cell-chars
  "The [char style] pairs for an inline container, hard breaks dropped — used for
  table cells, where styling must survive width-aware truncation."
  [content]
  (->> (mapcat inline-chars content) (remove #(= :break (first %))) vec))

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
                     ;; language label carries the same gutter so it lines up with
                     ;; the code lines below it.
                     language (cons (str gutter (style/styled language :faint true)))))))

(defn- blockquote [{:keys [content]} width]
  (let [gutter (style/styled "│ " :faint true)]
    (->> (str/split-lines (render-blocks content (max 1 (- width 2)) "\n"))
         (map #(str gutter %))
         (str/join "\n"))))

(defn- list-item
  "One list item: `marker` (styled with `marker-style`, default faint) then the
  item's rendered blocks, continuation lines hanging under the marker."
  ([item width marker] (list-item item width marker [:faint true]))
  ([item width marker marker-style]
   (let [indent (apply str (repeat (count marker) " "))
         inner  (str/split-lines (render-blocks (:content item) (max 1 (- width (count marker))) "\n"))]
     (str/join "\n" (prefix-lines (apply style/styled marker marker-style) indent inner)))))

(defn- bullet-list [{:keys [content]} width]
  (str/join "\n" (map #(list-item % width "• ") content)))

(defn- numbered-list [{:keys [content attrs]} width]
  (let [start (get attrs :start 1)]
    (str/join "\n" (map-indexed (fn [i item] (list-item item width (str (+ start i) ". "))) content))))

(defn- todo-list [{:keys [content]} width]
  (str/join "\n"
            (map (fn [item]
                   (if (= :todo-item (:type item))
                     (if (get-in item [:attrs :checked])
                       (list-item item width "[x] " [:fg style/green])
                       (list-item item width "[ ] " [:faint true]))
                     ;; a plain (non-task) item mixed into a task list
                     (list-item item width "• " [:faint true])))
                 content)))

(defn- ruler [width]
  (style/styled (apply str (repeat width "─")) :faint true))

(defn- render-cell
  "A single table cell rendered to exactly `w` visible columns: styled inline
  content, truncated with an ellipsis when it overflows, space-padded when short.
  `bold?` merges bold into the whole cell (header rows) at the character level so
  no reset code inside the cell can cut the bolding short."
  [chs w bold?]
  (let [chs (vec (if bold? (with-style {:bold true} chs) chs))
        n   (count chs)]
    (cond
      (> n w) (str (chars->str (subvec chs 0 (max 0 (dec w)))) "…")
      :else   (str (chars->str chs) (apply str (repeat (- w n) \space))))))

(defn- render-table [{:keys [content]} width]
  (let [head    (some #(when (= :table-head (:type %)) %) content)
        body    (some #(when (= :table-body (:type %)) %) content)
        row->   (fn [row] (mapv #(cell-chars (:content %)) (:content row)))
        hrows   (mapv row-> (:content head))
        brows   (mapv row-> (:content body))
        all     (into hrows brows)
        ncol    (apply max 0 (map count all))
        cell-at (fn [row c] (nth row c []))
        natural (mapv (fn [c] (apply max 1 (map #(count (cell-at % c)) all))) (range ncol))
        ;; overhead = leading "│ " + a " │ " between each column + trailing " │"
        budget  (max ncol (- width (+ 1 (* 3 ncol))))
        total   (reduce + 0 natural)
        widths  (if (<= total budget)
                  natural
                  ;; too wide for the frame — shrink each column proportionally
                  ;; (min 1) so the table never overruns; cells truncate to fit.
                  (mapv #(max 1 (int (* % (/ budget (double total))))) natural))
        fmt     (fn [row bold?]
                  (str "│ "
                       (str/join " │ " (map-indexed (fn [c w] (render-cell (cell-at row c) w bold?)) widths))
                       " │"))
        divider (str "├─" (str/join "─┼─" (map #(apply str (repeat % "─")) widths)) "─┤")]
    (str/join "\n" (concat (map #(fmt % true) hrows)
                           (when (seq hrows) [divider])
                           (map #(fmt % false) brows)))))

(defn- render-block [node width]
  (case (:type node)
    :heading       (heading node width)
    (:paragraph
     :plain)       (str/join "\n" (inline-lines (:content node) width))
    :code          (code-block node width)
    :blockquote    (blockquote node width)
    :bullet-list   (bullet-list node width)
    :numbered-list (numbered-list node width)
    :todo-list     (todo-list node width)
    :ruler         (ruler width)
    :table         (render-table node width)
    (if (:content node) (render-blocks (:content node) width "\n\n") "")))

(defn- footnote-defs
  "The document-level footnote definitions, rendered as a trailing section under
  a rule: each `[label] body`, hanging like a list item. Empty when there are no
  footnotes."
  [footnotes width]
  (when (seq footnotes)
    (let [item (fn [{:keys [label content]}]
                 (let [marker (str "[" label "] ")
                       indent (apply str (repeat (count marker) " "))
                       inner  (str/split-lines (render-blocks content (max 1 (- width (count marker))) "\n"))]
                   (str/join "\n" (prefix-lines (style/styled marker :faint true) indent inner))))]
      (str/join "\n\n" (cons (ruler width) (map item footnotes))))))

;; --- public -----------------------------------------------------------------

(defn render
  "Render a Markdown string to charm-styled terminal text wrapped to `width`.
  Blocks are separated by a blank line; footnote definitions follow at the end.
  Pure — safe to cache on `[md-string width]`."
  [markdown width]
  (let [w      (max 4 width)
        parsed (md/parse (str markdown))
        body   (render-blocks (:content parsed) w "\n\n")
        notes  (footnote-defs (:footnotes parsed) w)]
    (str/join "\n\n" (remove str/blank? [body notes]))))
