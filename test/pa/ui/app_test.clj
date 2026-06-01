(ns pa.ui.app-test
  (:require [charm.components.viewport :as vp]
            [charm.message :as msg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pa.ui.app :as app]))

(defn- model-with-turns
  "An initialised model sized to a small terminal with `n` turns of content,
  so the conversation overflows the viewport."
  [n]
  (let [[m0 _] ((app/init {:db-ch nil :watch-cmd nil :dispatch! identity}))
        [m1 _] (app/update-model m0 {:type :window-size :width 40 :height 12})
        turns  (vec (for [i (range n) role [:user :assistant]]
                      {:role role :content (str (name role) " " i)}))
        [m2 _] (app/update-model m1 {:type :runtime/db-updated :db {:conversation turns}})]
    m2))

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

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(deftest visible-window-scrolls-to-trailing-text
  (testing "fits untouched, never wider than avail, ellipsis when scrolled"
    (let [vw #'app/visible-window]
      (is (= "short" (vw "short" 10)) "shorter than avail is untouched")
      (is (= "abcde" (vw "abcde" 5)) "exact fit is untouched")
      (let [r (vw "abcdefghij" 5)]
        (is (= 5 (count r)) "never wider than avail")
        (is (str/starts-with? r "…"))
        (is (= "…ghij" r) "keeps the trailing chars after the ellipsis")))))

(deftest view-shows-placeholder-and-hint-when-empty
  (testing "empty input shows the placeholder and the key hint"
    (let [out (app/view {:input "" :width 40 :db {:conversation []}})]
      (is (str/includes? out "Ask me anything"))
      (is (str/includes? out "Enter to send")))))

;; ---------------------------------------------------------------------------
;; Conversation viewport
;; ---------------------------------------------------------------------------

(deftest new-turns-pin-conversation-to-bottom
  (testing "after a db update the viewport shows the latest turns"
    (is (vp/viewport-at-bottom? (:viewport (model-with-turns 20))))))

(deftest page-up-scrolls-conversation-up
  (testing "PgUp moves the window up, away from the bottom"
    (let [m       (model-with-turns 20)
          [m' _]  (app/update-model m (msg/key-press :page-up))]
      (is (< (get-in m' [:viewport :y-offset]) (get-in m [:viewport :y-offset])))
      (is (not (vp/viewport-at-bottom? (:viewport m')))))))

(deftest typing-does-not-scroll-the-viewport
  (testing "j/k go to the input buffer, not the viewport (no keymap conflict)"
    (let [m      (model-with-turns 20)
          [m' _] (app/update-model m (msg/key-press "j"))]
      (is (= "j" (:input m')) "j is typed, not consumed as a scroll key")
      (is (= (get-in m [:viewport :y-offset]) (get-in m' [:viewport :y-offset]))
          "viewport offset unchanged by typing"))))

;; ---------------------------------------------------------------------------
;; Log panel
;; ---------------------------------------------------------------------------

(deftest log-appended-buffers-entries
  (testing ":log/appended conj's the entry and reschedules the watch command"
    (let [[m cmd] (app/update-model {:logs [] :log-ch nil}
                                    {:type :log/appended
                                     :entry {:level :info :msg "hello"}})]
      (is (= [{:level :info :msg "hello"}] (:logs m)))
      (is (some? cmd) "watch-log-cmd rescheduled"))))

(deftest log-buffer-is-bounded
  (testing "the ring buffer keeps only the most recent entries"
    (let [m (reduce (fn [model i]
                      (first (app/update-model model
                                               {:type :log/appended
                                                :entry {:level :info :msg (str "m" i)}})))
                    {:logs [] :log-ch nil}
                    (range 250))]
      (is (= 200 (count (:logs m))) "capped at log-buffer-size")
      (is (= "m249" (:msg (last (:logs m)))) "newest retained")
      (is (= "m50" (:msg (first (:logs m)))) "oldest dropped"))))

(deftest ctrl-l-toggles-log-panel
  (testing "Ctrl+L flips the panel open/closed and the conversation viewport resizes"
    (let [m0       (model-with-turns 20)
          h0       (get-in m0 [:viewport :height])
          [m1 _]   (app/update-model m0 (msg/key-press "l" :ctrl true))
          [m2 _]   (app/update-model m1 (msg/key-press "l" :ctrl true))]
      (is (true? (:logs-open? m1)))
      (is (< (get-in m1 [:viewport :height]) h0) "expanded panel shrinks the conversation")
      (is (false? (:logs-open? m2)))
      (is (= h0 (get-in m2 [:viewport :height])) "collapsing restores the height"))))
