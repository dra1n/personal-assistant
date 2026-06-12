(ns pa.memory.extraction-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.memory.extraction :as extraction]))

(def ^:private sample-conversation
  [{:role :user      :content "Can you help me set up a reminder?"}
   {:role :assistant :content "Sure, I've set a reminder for 3pm."}
   {:role :user      :content "Also, I prefer dark mode everywhere."}
   {:role :assistant :content "Noted — dark mode preference saved."}])

;; ---------------------------------------------------------------------------
;; classify-messages
;; ---------------------------------------------------------------------------

(deftest classify-messages-structure
  (testing "returns a two-message vector with :system and :user roles"
    (let [msgs (extraction/classify-messages sample-conversation)]
      (is (= 2 (count msgs)))
      (is (= :system (:role (first msgs))))
      (is (= :user   (:role (second msgs)))))))

(deftest classify-messages-includes-conversation-content
  (testing "the user message contains the conversation turns"
    (let [content (:content (second (extraction/classify-messages sample-conversation)))]
      (is (re-find #"dark mode" content))
      (is (re-find #"reminder" content)))))

(deftest classify-messages-skips-tool-and-empty-turns
  (testing "tool-result and nil-content turns are excluded from the prompt"
    (let [conv [{:role :user      :content "hi"}
                {:role :tool      :content "result" :tool-call-id "x"}
                {:role :assistant :content nil}
                {:role :assistant :content "hello"}]
          content (:content (second (extraction/classify-messages conv)))]
      (is (re-find #"User: hi" content))
      (is (re-find #"Assistant: hello" content))
      (is (not (re-find #"result" content))))))

;; ---------------------------------------------------------------------------
;; parse-response
;; ---------------------------------------------------------------------------

(deftest parse-response-valid-json
  (testing "parses a well-formed JSON response"
    (let [json "{\"ephemeral\":[{\"title\":\"Setup\",\"summary\":\"User set up a reminder\"}],\"permanent\":[\"User prefers dark mode\"]}"
          {:keys [ephemeral permanent]} (extraction/parse-response json)]
      (is (= 1 (count ephemeral)))
      (is (= "Setup" (:title (first ephemeral))))
      (is (= "User set up a reminder" (:summary (first ephemeral))))
      (is (= ["User prefers dark mode"] permanent)))))

(deftest parse-response-strips-code-fence
  (testing "strips markdown code fences before parsing"
    (let [fenced "```json\n{\"ephemeral\":[],\"permanent\":[\"fact\"]}\n```"
          {:keys [permanent]} (extraction/parse-response fenced)]
      (is (= ["fact"] permanent)))))

(deftest parse-response-empty-arrays
  (testing "returns empty vectors when model finds nothing to extract"
    (let [{:keys [ephemeral permanent]} (extraction/parse-response "{\"ephemeral\":[],\"permanent\":[]}")]
      (is (= [] ephemeral))
      (is (= [] permanent)))))

(deftest parse-response-malformed-returns-empty
  (testing "returns empty vectors on unparseable content"
    (let [{:keys [ephemeral permanent]} (extraction/parse-response "not json at all")]
      (is (= [] ephemeral))
      (is (= [] permanent)))))
