(ns pa.ui.input-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.ui.input :as input]))

(def ^:private history
  [{:history/text "git status"}
   {:history/text "git diff"}
   {:history/text "git log"}])

(def ^:private no-nav input/initial-nav)

(deftest navigate-back-from-idle
  (testing "first ↑ snapshots draft and shows most recent entry"
    (let [[nav text] (input/navigate-back no-nav history "my draft")]
      (is (= 2 (:nav/index nav)) "index points to last entry")
      (is (= "my draft" (:nav/draft nav)) "draft preserved")
      (is (= "git log" text)))))

(deftest navigate-back-clamps-at-oldest
  (testing "↑ at the oldest entry stays there"
    (let [nav {:nav/index 0 :nav/draft ""}
          [nav' text] (input/navigate-back nav history "git status")]
      (is (= 0 (:nav/index nav')) "index stays at 0")
      (is (= "git status" text)))))

(deftest navigate-back-no-ops-on-empty-history
  (testing "↑ with no history returns unchanged nav and current input"
    (let [[nav text] (input/navigate-back no-nav [] "draft")]
      (is (= no-nav nav))
      (is (= "draft" text)))))

(deftest navigate-forward-past-end-restores-draft
  (testing "↓ past the last (most recent) entry restores draft and resets navigation"
    (let [nav         {:nav/index 2 :nav/draft "my draft"}
          [nav' text] (input/navigate-forward nav history)]
      (is (nil? (:nav/index nav')) "index reset to nil")
      (is (= "my draft" text) "draft restored"))))

(deftest navigate-forward-steps-toward-recent
  (testing "↓ moves index forward and shows the next entry"
    (let [nav         {:nav/index 0 :nav/draft "draft"}
          [nav' text] (input/navigate-forward nav history)]
      (is (= 1 (:nav/index nav')))
      (is (= "git diff" text)))))

(deftest navigate-forward-no-ops-when-not-navigating
  (testing "↓ while not navigating is a no-op"
    (let [[nav text] (input/navigate-forward no-nav history)]
      (is (= no-nav nav))
      (is (= "" text)))))

(deftest reset-navigation-exits-and-appends-char
  (testing "typing a char exits navigation mode and appends it to the saved draft"
    (let [nav         {:nav/index 1 :nav/draft "git "}
          [nav' text] (input/reset-navigation nav "s")]
      (is (nil? (:nav/index nav')) "index cleared")
      (is (= "git s" text) "char appended to draft"))))

(deftest reset-navigation-on-empty-draft
  (testing "char alone when draft was empty"
    (let [[nav' text] (input/reset-navigation {:nav/index 0 :nav/draft ""} "x")]
      (is (nil? (:nav/index nav')))
      (is (= "x" text)))))
