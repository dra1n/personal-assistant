(ns pa.ui.app-test
  (:require [charm.message :as msg]
            [clojure.test :refer [deftest is testing]]
            [pa.ui.app :as app]))

(deftest typing-appends-runes-to-input
  (testing "printable characters accumulate in the input buffer"
    (let [[m1 _] (app/update-model {:input ""} (msg/key-press "h"))
          [m2 _] (app/update-model m1 (msg/key-press "i"))]
      (is (= "hi" (:input m2))))))

(deftest space-key-appends-a-space
  (testing "space is a special key, not a runes string"
    (let [[m _] (app/update-model {:input "a"} (msg/key-press :space))]
      (is (= "a " (:input m))))))

(deftest backspace-removes-last-char
  (let [[m _] (app/update-model {:input "abc"} (msg/key-press :backspace))]
    (is (= "ab" (:input m))))
  (testing "backspace on empty buffer is a no-op"
    (let [[m _] (app/update-model {:input ""} (msg/key-press :backspace))]
      (is (= "" (:input m))))))

(deftest modified-chords-do-not-append
  (testing "ctrl/alt chords are not treated as text input"
    (let [[m cmd] (app/update-model {:input "x"} (msg/key-press "c" :ctrl true))]
      (is (= "x" (:input m)) "ctrl+c does not append")
      (is (some? cmd) "ctrl+c yields the quit command"))
    (let [[m _] (app/update-model {:input "x"} (msg/key-press "a" :alt true))]
      (is (= "x" (:input m)) "alt+a does not append"))))

(deftest enter-dispatches-user-message-and-clears-buffer
  (testing "Enter dispatches a trimmed :user/message and clears the input"
    (let [events  (atom [])
          model   {:input "  hello world  " :dispatch! #(swap! events conj %)}
          [m cmd] (app/update-model model (msg/key-press :enter))]
      (is (= "" (:input m)) "buffer cleared")
      (is (some? cmd) "a dispatch command is returned")
      ((:fn cmd))  ; run the command's side effect
      (is (= [{:event/type :user/message :content "hello world"}] @events)))))

(deftest enter-on-blank-input-is-a-no-op
  (testing "blank/whitespace input is neither dispatched nor cleared oddly"
    (let [events  (atom [])
          [m cmd] (app/update-model {:input "   " :dispatch! #(swap! events conj %)}
                                    (msg/key-press :enter))]
      (is (nil? cmd))
      (is (empty? @events))
      (is (= "   " (:input m))))))

(deftest db-update-preserves-input-buffer
  (testing "an incoming runtime snapshot does not clobber in-progress typing"
    (let [model  {:input "half-typed" :db-ch nil :db {}}
          [m _]  (app/update-model model {:type :runtime/db-updated :db {:conversation [:x]}})]
      (is (= "half-typed" (:input m)))
      (is (= {:conversation [:x]} (:db m))))))
