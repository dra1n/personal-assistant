(ns pa.ui.view-test
  (:require [clojure.string :as str]
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

(deftest viewport-height-reserves-conversation-border-rows
  (testing "the conversation box's two border rows are subtracted from the height"
    ;; 24 − fixed chrome (9) − collapsed log panel (1) = 14
    (is (= 14 (view/viewport-height {:height 24 :logs-open? false})))
    ;; 24 − 9 − expanded log panel (11) = 4
    (is (= 4 (view/viewport-height {:height 24 :logs-open? true})))))

(deftest conversation-labels-fall-back-on-blank-name
  (testing "a blank identity name falls back to the capitalized default"
    (let [out (view/conversation-content
               {:conversation [{:role :assistant :content "hello"}]
                :identity {:identity {:front-matter {:name ""}}}}
               40 nil)]
      (is (str/includes? out "Assistant")))))
