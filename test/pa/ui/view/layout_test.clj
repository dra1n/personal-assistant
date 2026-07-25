(ns pa.ui.view.layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.ui.view.layout :as layout]))

(deftest input-line-count-single-line
  (testing "blank or single-line input always reports 1 visual line"
    (is (= 1 (layout/input-line-count {})))
    (is (= 1 (layout/input-line-count {:input ""})))
    (is (= 1 (layout/input-line-count {:input "hello" :width 80})))))

(deftest input-line-count-multiline
  (testing "counts visual lines for buffers with embedded newlines"
    (is (= 2 (layout/input-line-count {:input "line one\nline two" :width 80})))
    (is (= 3 (layout/input-line-count {:input "a\nb\nc" :width 80})))
    ;; trailing newline adds a blank visual line
    (is (= 2 (layout/input-line-count {:input "hello\n" :width 80})))))

(deftest viewport-height-reserves-conversation-border-rows
  (testing "fixed chrome (header + 1-line input + hint + borders) is subtracted"
    ;; 24 − (8 + input-line-count(1) + collapsed-panel(1)) = 14
    (is (= 14 (layout/viewport-height {:height 24 :logs-open? false})))
    ;; 24 − (8 + 1 + expanded-panel(11)) = 4
    (is (= 4 (layout/viewport-height {:height 24 :logs-open? true})))))

(deftest viewport-height-shrinks-for-multiline-input
  (testing "each additional input line reduces the conversation viewport by one row"
    (let [single (layout/viewport-height {:height 30 :logs-open? false :input "one line"  :width 80})
          double (layout/viewport-height {:height 30 :logs-open? false :input "a\nb"       :width 80})
          triple (layout/viewport-height {:height 30 :logs-open? false :input "a\nb\nc"    :width 80})]
      (is (= 1 (- single double)) "2-line input shrinks viewport by 1")
      (is (= 1 (- double triple)) "3-line input shrinks viewport by another 1"))))
