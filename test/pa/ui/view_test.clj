(ns pa.ui.view-test
  (:require [charm.components.viewport :as vp]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pa.ui.view :as view]
            [pa.ui.view.layout :as layout]))

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

(deftest header-shows-llm-model
  (testing "the active LLM model appears next to the wordmark when known"
    (let [with-model (view/view {:width 60 :height 24 :llm-model "gpt-5"
                                 :db {:conversation []}})
          without    (view/view {:width 60 :height 24 :db {:conversation []}})]
      (is (str/includes? with-model "· gpt-5") "model name shown after the wordmark")
      (is (not (str/includes? without "· gpt-5")) "nothing shown when the model is unknown"))))

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
      (is (= 6 (layout/notification-lines model)) "3 rows + overflow line + 2 border rows")
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

;; ---------------------------------------------------------------------------
;; Markdown rendering (Phase 8) — behind the :markdown setting
;;
;; ANSI escapes never contain "**"/"`", so marker presence/absence is a reliable
;; signal without stripping: rendered = markers consumed, raw = markers present.
;; ---------------------------------------------------------------------------

(defn- convo-db [md? conversation]
  {:conversation conversation :settings {:markdown md?}})

(deftest markdown-on-renders-committed-assistant-turn
  (testing "with :markdown on, a committed assistant turn is markdown-rendered
            (emphasis markers consumed) while a user turn stays literal"
    (let [out (view/conversation-content
               (convo-db true [{:role :user      :content "keep my **stars**"}
                               {:role :assistant :content "here is **bold** text"}])
               60 nil)]
      (is (str/includes? out "bold") "assistant text kept")
      (is (str/includes? out "keep my **stars**") "user turn is left literal")
      ;; the only "**" left in the output is the user's, so exactly one pair
      (is (= 2 (count (re-seq #"\*\*" out))) "assistant emphasis markers consumed"))))

(deftest markdown-off-shows-raw-source
  (testing "with :markdown off, the assistant turn shows its raw markdown source"
    (let [out (view/conversation-content
               (convo-db false [{:role :assistant :content "here is **bold** text"}])
               60 nil)]
      (is (str/includes? out "**bold**") "markers left intact when off"))))

(deftest live-stream-never-markdown-rendered
  (testing "even with :markdown on, the in-flight streamed turn stays plain —
            a half-open block mid-stream can't garble the preview"
    (let [out (view/conversation-content
               (convo-db true [{:role :user :content "hi"}])
               60 "streaming **not** styled")]
      (is (str/includes? out "streaming **not** styled") "stream markers untouched"))))

(deftest non-map-conversation-entry-does-not-crash
  (testing "a non-map sentinel entry passes through the markdown tagging path"
    (let [out (view/conversation-content
               (convo-db true [:some-sentinel
                               {:role :assistant :content "**ok**"}])
               60 nil)]
      (is (str/includes? out "ok") "the real turn still renders"))))

(deftest markdown-on-preserves-frame-height
  (testing "rendering markdown wraps to text-width, so the fixed frame height is
            unchanged vs markdown off"
    (let [model  (fn [md?] {:width 50 :height 30 :logs [] :logs-open? false
                            :focus :input :streaming "" :motd-fallback "tip"
                            :viewport (vp/viewport "")
                            :db (convo-db md? [{:role :assistant
                                                :content "# Title\n\n- a\n- b\n\n**bold** and `code`"}])})
          on     (view/view (model true))
          off    (view/view (model false))]
      (is (= 30 (count (str/split-lines on))) "markdown on fills exactly the height")
      (is (= 30 (count (str/split-lines off))) "markdown off fills exactly the height"))))

(deftest attached-resources-are-shown-under-the-turn
  (testing "what reaches the model is visible in the transcript"
    (let [out (view/conversation-content
               {:conversation
                [{:role :user :content "summarize @s:demo://a"
                  :attachments [{:name "a.md" :uri "demo://a" :mime-type "text/markdown"}
                                {:name "b.md" :uri "demo://b" :error "server is not connected"}]}]}
               60 nil)]
      (is (str/includes? out "summarize @s:demo://a"))
      (is (str/includes? out "a.md"))
      (is (str/includes? out "text/markdown"))
      (is (str/includes? out "not read: server is not connected")))))
