(ns pa.ui.input.view-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pa.ui.input.view :as input-view]))

(defn- visible-width
  "Column count of s with ANSI escapes stripped."
  [s]
  (count (str/replace s #"\e\[[0-9;]*m" "")))

(deftest single-line-cursor-window-follows-the-cursor
  (let [slc #'input-view/single-line-with-cursor]
    (testing "short input is untouched apart from the cursor cell"
      (is (str/includes? (slc "short" 5 10) "short"))
      (is (= 6 (visible-width (slc "short" 5 10))) "trailing cursor cell appended at end"))
    (testing "cursor at the end of overflowing text shows the tail"
      (let [r (slc "abcdefghijklmnop" 16 10)]
        (is (<= (visible-width r) 10) "never wider than avail")
        (is (str/starts-with? r "…") "left overflow flagged")
        (is (str/includes? r "op") "tail visible")))
    (testing "cursor at the start of overflowing text shows the head"
      (let [r (slc "abcdefghijklmnop" 0 10)]
        (is (<= (visible-width r) 10))
        (is (str/includes? (str/replace r #"\e\[[0-9;]*m" "") "abc") "head visible")
        (is (str/ends-with? r "…") "right overflow flagged")))
    (testing "cursor mid-string keeps both neighbours visible"
      (let [r (slc "abcdefghijklmnopqrstuvwxyz" 13 10)]
        (is (<= (visible-width r) 10))
        (is (str/starts-with? r "…"))
        (is (str/ends-with? r "…"))
        (is (str/includes? (str/replace r #"\e\[[0-9;]*m" "") "n") "char under cursor present")))))

(deftest multiline-cursor-lands-on-its-row
  (let [mlc  #'input-view/multiline-with-cursor
        text "first\nsecond"]
    (testing "two segments render two rows"
      (let [lines (str/split-lines (mlc text 9 20 "> "))]
        (is (= 2 (count lines)))))
    (testing "cursor cell is on the row containing the cursor position"
      ;; pos 8 = 'c' in "second" ("first" is 0–4, \n is 5)
      (let [lines (str/split-lines (mlc text 8 20 "> "))]
        (is (not (str/includes? (first lines) "[7m")) "no cursor on row 1")
        (is (str/includes? (second lines) "[7m") "reverse-video cursor on row 2")))
    (testing "a segment filling the row exactly wraps the cursor to a fresh row"
      ;; segment of exactly 10 chars at avail 10, cursor at its end
      (let [lines (str/split-lines (mlc "0123456789\nx" 10 10 "> "))]
        (is (= 3 (count lines)) "full row + empty continuation row + second segment")
        (is (str/includes? (second lines) "[7m") "cursor on the continuation row")))))
