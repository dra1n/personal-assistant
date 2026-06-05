(ns pa.ui.view-test
  (:require [charm.components.viewport :as vp]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pa.ui.view :as view]))

(deftest visible-window-scrolls-to-trailing-text
  (testing "fits untouched, never wider than avail, ellipsis when scrolled"
    (let [vw #'view/visible-window]
      (is (= "short" (vw "short" 10)) "shorter than avail is untouched")
      (is (= "abcde" (vw "abcde" 5)) "exact fit is untouched")
      (let [r (vw "abcdefghij" 5)]
        (is (= 5 (count r)) "never wider than avail")
        (is (str/starts-with? r "…"))
        (is (= "…ghij" r) "keeps the trailing chars after the ellipsis")))))

(deftest view-shows-placeholder-and-hint-when-empty
  (testing "empty input shows the placeholder and the key hint"
    (let [out (view/view {:input "" :width 40 :db {:conversation []}})]
      (is (str/includes? out "Ask me anything"))
      (is (str/includes? out "Enter send")))))

(deftest conversation-labels-default-capitalized
  (testing "with no identity names, turns use capitalized You/Assistant"
    (let [out (view/conversation-content
               {:conversation [{:role :user :content "hi"}
                               {:role :assistant :content "hello"}]}
               40 nil)]
      (is (str/includes? out "You"))
      (is (str/includes? out "Assistant")))))

(deftest conversation-labels-use-identity-names
  (testing "identity names override the default labels when set"
    (let [out (view/conversation-content
               {:conversation [{:role :user :content "hi"}
                               {:role :assistant :content "hello"}]
                :identity {:identity {:front-matter {:name "Aria"}}
                           :user     {:front-matter {:name "Andrey"}}}}
               40 nil)]
      (is (str/includes? out "Andrey"))
      (is (str/includes? out "Aria"))
      (is (not (str/includes? out "Assistant")) "name replaces the default label"))))

(deftest input-line-count-single-line
  (testing "blank or single-line input always reports 1 visual line"
    (is (= 1 (view/input-line-count {})))
    (is (= 1 (view/input-line-count {:input ""})))
    (is (= 1 (view/input-line-count {:input "hello" :width 80})))))

(deftest input-line-count-multiline
  (testing "counts visual lines for buffers with embedded newlines"
    (is (= 2 (view/input-line-count {:input "line one\nline two" :width 80})))
    (is (= 3 (view/input-line-count {:input "a\nb\nc" :width 80})))
    ;; trailing newline adds a blank visual line
    (is (= 2 (view/input-line-count {:input "hello\n" :width 80})))))

(deftest viewport-height-reserves-conversation-border-rows
  (testing "fixed chrome (header + blanks + 1-line input + hint + borders) are subtracted"
    ;; 24 − (10 + input-line-count(1) + collapsed-panel(1)) = 12
    (is (= 12 (view/viewport-height {:height 24 :logs-open? false})))
    ;; 24 − (10 + 1 + expanded-panel(11)) = 2 → clamped to the 3-row minimum
    (is (= 3 (view/viewport-height {:height 24 :logs-open? true})))))

(deftest viewport-height-shrinks-for-multiline-input
  (testing "each additional input line reduces the conversation viewport by one row"
    (let [single (view/viewport-height {:height 30 :logs-open? false :input "one line"  :width 80})
          double (view/viewport-height {:height 30 :logs-open? false :input "a\nb"       :width 80})
          triple (view/viewport-height {:height 30 :logs-open? false :input "a\nb\nc"    :width 80})]
      (is (= 1 (- single double)) "2-line input shrinks viewport by 1")
      (is (= 1 (- double triple)) "3-line input shrinks viewport by another 1"))))

(deftest frame-height-unchanged-with-multiline-input
  (testing "the rendered frame is still exactly terminal height when input is multiline"
    (let [m (-> {:input "line one\nline two" :width 40 :height 30
                 :db {:conversation []} :logs [] :logs-open? false :focus :input
                 :streaming "" :motd-fallback "tip" :viewport (vp/viewport "")}
                view/view)]
      (is (= 30 (count (str/split-lines m)))
          "multiline input grows the input box and shrinks the conversation to compensate"))))

(deftest header-shows-motd-or-fallback-tip
  (testing "the header wordmark plus the user's motd, or a fallback tip"
    (let [with-motd (view/view {:width 54 :height 24 :db {:conversation []
                                 :identity {:user {:front-matter {:motd "Carpe diem!"}}}}})
          fallback  (view/view {:width 54 :height 24 :motd-fallback "a handy tip"
                                :db {:conversation []}})]
      (is (str/includes? with-motd "Personal Assistant") "wordmark present")
      (is (str/includes? with-motd "Carpe diem!") "user's motd shown when set")
      (is (str/includes? fallback "a handy tip") "fallback tip shown when motd unset"))))

(deftest conversation-renders-tool-call-turn
  (testing "an assistant turn that only calls a tool shows the call, not an empty bubble"
    (let [out (view/conversation-content
               {:conversation [{:role       :assistant
                                :content    ""
                                :tool-calls [{:id "c1" :name :fs/write-file
                                              :arguments {:path "workspace/hello.txt"
                                                          :content "hi"}}]}]}
               80 nil)]
      (is (str/includes? out "fs/write-file") "the tool name is shown")
      (is (str/includes? out "workspace/hello.txt") "the arguments are shown"))))

(deftest conversation-labels-fall-back-on-blank-name
  (testing "a blank identity name falls back to the capitalized default"
    (let [out (view/conversation-content
               {:conversation [{:role :assistant :content "hello"}]
                :identity {:identity {:front-matter {:name ""}}}}
               40 nil)]
      (is (str/includes? out "Assistant")))))
