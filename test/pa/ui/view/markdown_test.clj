(ns pa.ui.view.markdown-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pa.ui.view.markdown :as md]))

;; Assertions are on ANSI-stripped output — the renderer's job is the text
;; transformation (markers consumed, structure added); the exact SGR codes are
;; charm's concern and are exercised by rendering without throwing.
(defn- strip [s] (str/replace s #"\[[0-9;]*m" ""))
(defn- render [s width] (strip (md/render s width)))
(defn- lines [s] (str/split-lines s))
(defn- max-width [s] (apply max 0 (map count (lines s))))

;; --- inline & headings ------------------------------------------------------

(deftest headings-keep-text-drop-markers-of-emphasis
  (testing "a heading keeps its text; the # marker is preserved as a gutter"
    (let [out (render "# Hello World" 40)]
      (is (str/includes? out "Hello World"))
      (is (str/includes? out "#")))))

(deftest emphasis-markers-consumed-text-kept
  (testing "strong/em/strikethrough markers are consumed, the inner text stays"
    (let [out (render "**bold** and *em* and ~~gone~~" 60)]
      (is (str/includes? out "bold"))
      (is (str/includes? out "em"))
      (is (str/includes? out "gone"))
      (is (not (str/includes? out "**")))
      (is (not (str/includes? out "~~"))))))

(deftest inline-code-markers-consumed
  (testing "inline code keeps its text without the backticks"
    (let [out (render "call `foo-bar` now" 40)]
      (is (str/includes? out "foo-bar"))
      (is (not (str/includes? out "`"))))))

(deftest link-text-kept-with-faint-url
  (testing "a link renders its label and appends the href faintly"
    (let [out (render "see [the docs](http://example.com) today" 80)]
      (is (str/includes? out "the docs"))
      (is (str/includes? out "(http://example.com)") "href appended")
      (is (not (str/includes? out "]("))))))

(deftest autolink-does-not-repeat-url
  (testing "when the label already is the URL, it is not appended twice"
    (let [out (render "<http://example.com>" 80)]
      (is (= 1 (count (re-seq #"http://example.com" out))) "URL shown once"))))

;; --- lists ------------------------------------------------------------------

(deftest bullet-list-marker-transformed
  (testing "a '-' bullet becomes a rendered bullet glyph; the '- ' source marker is gone"
    (let [out (render "- one\n- two" 40)]
      (is (str/includes? out "one"))
      (is (str/includes? out "two"))
      (is (not (str/includes? out "- one")) "raw '- ' marker replaced"))))

(deftest nested-list-preserved
  (testing "a nested bullet is indented under its parent"
    (let [out   (render "- parent\n  - child" 40)
          ls    (lines out)
          child (some #(when (str/includes? % "child") %) ls)]
      (is (str/includes? out "parent"))
      (is (some? child))
      (is (str/starts-with? child " ") "child line is indented"))))

(deftest numbered-list-numbers-kept
  (testing "an ordered list keeps its numbering"
    (let [out (render "1. first\n2. second" 40)]
      (is (str/includes? out "1. first"))
      (is (str/includes? out "2. second")))))

(deftest task-list-checkbox-markers
  (testing "task items render [ ] / [x] checkboxes with their text"
    (let [out (render "- [ ] todo\n- [x] done" 40)]
      (is (str/includes? out "[ ] todo"))
      (is (str/includes? out "[x] done")))))

;; --- blocks -----------------------------------------------------------------

;; charm downgrades box-drawing glyphs to ASCII when the text is styled, so a
;; faint gutter/rule renders as "|"/"-" while unstyled table borders keep the
;; box glyphs — the char classes below tolerate either form.
(deftest fenced-code-block-gutter-and-language
  (testing "code lines and the language label share a gutter; content is verbatim"
    (let [out (render "```clojure\n(+ 1 2)\n```" 40)
          ls  (lines out)]
      (is (str/includes? out "(+ 1 2)"))
      (is (str/includes? out "clojure"))
      (is (every? #(re-find #"^[│|]" %) ls) "every line (incl. language) shares the gutter"))))

(deftest blockquote-gutter
  (testing "a blockquote prefixes its lines with a gutter"
    (let [out (render "> quoted text" 40)]
      (is (str/includes? out "quoted text"))
      (is (re-find #"^[│|]" (first (lines out)))))))

(deftest thematic-break-is-a-rule
  (testing "--- becomes a horizontal rule spanning the width"
    (let [out (render "a\n\n---\n\nb" 20)]
      (is (some #(re-matches #"[─-]{3,}" %) (lines out)) "a run of rule glyphs present"))))

(deftest table-renders-aligned
  (testing "a GFM table renders header, divider, and body rows aligned in columns"
    (let [out (render "| A | B |\n|---|---|\n| 1 | 2 |" 40)
          ls  (lines out)]
      (is (str/includes? out "A"))
      (is (str/includes? out "B"))
      (is (some #(re-find #"├.*┼.*┤" %) ls) "divider row present")
      (is (apply = (map count ls)) "every rendered row is the same visible width"))))

(deftest table-truncates-to-width
  (testing "an over-wide table is shrunk/truncated so it never overruns the frame"
    (let [wide (str "| Column One Is Long | Column Two Is Also Long |\n"
                    "|---|---|\n"
                    "| a very long cell value here | another long overflowing one |")
          out  (md/render wide 40)]
      (is (<= (max-width (strip out)) 40) "no row exceeds the target width")
      (is (str/includes? (strip out) "…") "overflowing cells show an ellipsis"))))

(deftest footnotes-ref-and-definition
  (testing "a footnote renders a [n] reference inline and its definition below"
    (let [out (render "text with a note.[^1]\n\n[^1]: the body" 60)]
      (is (str/includes? out "[1]") "inline reference marker")
      (is (str/includes? out "the body") "definition text rendered"))))

;; --- wrapping ---------------------------------------------------------------

(deftest paragraph-wraps-to-width
  (testing "a long paragraph wraps so no line exceeds the width"
    (let [out (render (str/join " " (repeat 40 "word")) 30)]
      (is (> (count (lines out)) 1) "wrapped onto multiple lines")
      (is (<= (max-width out) 30)))))

(deftest soft-break-becomes-space
  (testing "a single newline inside a paragraph joins with a space, not a merge"
    (let [out (render "wrapped\nto width" 40)]
      (is (str/includes? out "wrapped to width"))
      (is (not (str/includes? out "wrappedto"))))))

(deftest overlong-word-hard-splits
  (testing "a word longer than the width is hard-split rather than overrunning"
    (let [out (render (apply str (repeat 50 "x")) 20)]
      (is (> (count (lines out)) 1) "split across lines")
      (is (<= (max-width out) 20)))))
