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
  (testing "fixed chrome (incl. the 4-row header) and border rows are subtracted"
    ;; 24 − fixed chrome (12) − collapsed log panel (1) = 11
    (is (= 11 (view/viewport-height {:height 24 :logs-open? false})))
    ;; 24 − 12 − expanded log panel (11) = 1 → clamped to the 3-row minimum
    (is (= 3 (view/viewport-height {:height 24 :logs-open? true})))))

(deftest header-shows-motd-or-fallback-tip
  (testing "the header wordmark plus the user's motd, or a fallback tip"
    (let [with-motd (view/view {:width 54 :height 24 :db {:conversation []
                                 :identity {:user {:front-matter {:motd "Carpe diem!"}}}}})
          fallback  (view/view {:width 54 :height 24 :motd-fallback "a handy tip"
                                :db {:conversation []}})]
      (is (str/includes? with-motd "personal assistant") "wordmark present")
      (is (str/includes? with-motd "Carpe diem!") "user's motd shown when set")
      (is (str/includes? fallback "a handy tip") "fallback tip shown when motd unset"))))

(deftest conversation-labels-fall-back-on-blank-name
  (testing "a blank identity name falls back to the capitalized default"
    (let [out (view/conversation-content
               {:conversation [{:role :assistant :content "hello"}]
                :identity {:identity {:front-matter {:name ""}}}}
               40 nil)]
      (is (str/includes? out "Assistant")))))
