(ns pa.ui.view-test
  (:require [charm.components.viewport :as vp]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pa.ui.view :as view]))

(defn- visible-width
  "Column count of s with ANSI escapes stripped."
  [s]
  (count (str/replace s #"\e\[[0-9;]*m" "")))

(deftest single-line-cursor-window-follows-the-cursor
  (let [slc #'view/single-line-with-cursor]
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
  (let [mlc  #'view/multiline-with-cursor
        text "first\nsecond"]
    (testing "two segments render two rows"
      (let [lines (str/split-lines (mlc text 9 20 "> "))]
        (is (= 2 (count lines)))))
    (testing "cursor cell is on the row containing the cursor position"
      ;; pos 8 = 'c' in "second" ("first" is 0–4, \n is 5)
      (let [lines (str/split-lines (mlc text 8 20 "> "))]
        (is (not (str/includes? (first lines) "\u001b[7m")) "no cursor on row 1")
        (is (str/includes? (second lines) "\u001b[7m") "reverse-video cursor on row 2")))
    (testing "a segment filling the row exactly wraps the cursor to a fresh row"
      ;; segment of exactly 10 chars at avail 10, cursor at its end
      (let [lines (str/split-lines (mlc "0123456789\nx" 10 10 "> "))]
        (is (= 3 (count lines)) "full row + empty continuation row + second segment")
        (is (str/includes? (second lines) "\u001b[7m") "cursor on the continuation row")))))

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
  (testing "fixed chrome (header + 1-line input + hint + borders) is subtracted"
    ;; 24 − (8 + input-line-count(1) + collapsed-panel(1)) = 14
    (is (= 14 (view/viewport-height {:height 24 :logs-open? false})))
    ;; 24 − (8 + 1 + expanded-panel(11)) = 4
    (is (= 4 (view/viewport-height {:height 24 :logs-open? true})))))

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

(deftest wrap-line-hard-splits-overlong-words
  (testing "a word longer than the width is split into width-sized chunks"
    (let [wl    #'view/wrap-line
          lines (wl (str "see " (apply str (repeat 25 "x")) " ok") 10)]
      (is (every? #(<= (count %) 10) lines) "no line overflows the box width")
      (is (= (str "see" (apply str (repeat 25 "x")) "ok")
             (str/replace (str/join lines) " " ""))
          "all characters survive the split"))))

(deftest tool-result-turns-are-collapsed
  (testing "a :tool turn shows only the first lines plus an elision count"
    (let [content (str/join "\n" (map #(str "line " %) (range 20)))
          out     (view/conversation-content
                   {:conversation [{:role :tool :tool-call-id "c1" :content content}]}
                   80 nil)]
      (is (str/includes? out "line 0") "the head is shown")
      (is (not (str/includes? out "line 19")) "the tail is elided")
      (is (str/includes? out "more lines") "the elision count is shown"))))

(deftest tool-call-arguments-are-truncated
  (testing "long tool-call arguments are truncated to one line per call"
    (let [out (view/conversation-content
               {:conversation [{:role       :assistant
                                :content    ""
                                :tool-calls [{:id "c1" :name :fs/write-file
                                              :arguments {:path    "a.txt"
                                                          :content (apply str (repeat 500 "y"))}}]}]}
               40 nil)]
      (is (str/includes? out "fs/write-file"))
      (is (not (str/includes? out (apply str (repeat 100 "y"))))
          "the argument blob does not flood the turn"))))

(deftest pending-turn-shows-thinking-indicator
  (testing "waiting on the first delta renders a faint thinking… turn"
    (let [out (view/conversation-content
               {:conversation [{:role :user :content "hi"}]} 40 "" true)]
      (is (str/includes? out "thinking…"))))
  (testing "the indicator disappears once deltas arrive"
    (let [out (view/conversation-content
               {:conversation [{:role :user :content "hi"}]} 40 "Hel" true)]
      (is (not (str/includes? out "thinking…")))
      (is (str/includes? out "Hel") "the live stream is shown instead"))))

(deftest notification-banner-renders-and-reserves-height
  (testing "pending notifications appear under the header with a dismiss hint"
    (let [db  {:conversation      []
               :ui/notifications  [{:id "t1" :type :reminder :payload {:text "stretch your legs"}}]}
          out (view/view {:width 60 :height 30 :db db :logs [] :logs-open? false
                          :focus :input :input "" :streaming "" :motd-fallback "tip"})]
      (is (str/includes? out "Reminder"))
      (is (str/includes? out "stretch your legs"))
      (is (str/includes? out "^X dismiss"))
      (is (= 30 (count (str/split-lines out)))
          "the banner takes its rows from the conversation, not the frame")))
  (testing "no banner when nothing is pending"
    (let [out (view/view {:width 60 :height 30 :db {:conversation []} :logs []})]
      (is (not (str/includes? out "dismiss")))))
  (testing "overflow beyond the row cap collapses into a +N more line"
    (let [notes (mapv #(hash-map :id (str "t" %) :type :reminder
                                 :payload {:text (str "reminder " %)}) (range 5))
          model {:width 60 :height 30 :db {:conversation [] :ui/notifications notes}
                 :logs [] :logs-open? false :focus :input :input ""
                 :streaming "" :motd-fallback "tip"}
          out   (view/view model)]
      (is (= 6 (view/notification-lines model)) "3 rows + overflow line + 2 border rows")
      (is (str/includes? out "reminder 2"))
      (is (not (str/includes? out "reminder 3")) "capped at 3 rows")
      (is (str/includes? out "+2 more"))
      (is (= 30 (count (str/split-lines out)))))))

(deftest conversation-labels-fall-back-on-blank-name
  (testing "a blank identity name falls back to the capitalized default"
    (let [out (view/conversation-content
               {:conversation [{:role :assistant :content "hello"}]
                :identity {:identity {:front-matter {:name ""}}}}
               40 nil)]
      (is (str/includes? out "Assistant")))))
