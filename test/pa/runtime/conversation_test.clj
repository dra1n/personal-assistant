(ns pa.runtime.conversation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pa.llm.provider :as provider]
            [pa.runtime.executor :as executor]
            [pa.runtime.handlers]                 ; registers the conversation handlers
            [pa.runtime.registry :as registry]
            [pa.runtime.replay :as replay]))

(defn- handler [event-type]
  (:fn (registry/get-handler event-type)))

(deftest user-message-handler-assembles-and-invokes
  (let [db {:conversation [] :identity {:identity {:front-matter {:name "Aria"} :prose ""}}}
        fx ((handler :user/message)
            {:db db :event {:event/type :user/message :content "hello"}})]
    (testing "appends the user turn and persists the event"
      (is (= {:role :user :content "hello"} (last (:conversation (:db fx)))))
      (is (= {:event/type :user/message :content "hello"} (:event/store fx))))
    (testing "emits :llm/invoke with assembled messages (identity system + user turn)"
      (let [msgs (get-in fx [:llm/invoke :messages])]
        (is (= :system (:role (first msgs))))
        (is (str/includes? (:content (first msgs)) "Aria"))
        (is (= {:role :user :content "hello"} (last msgs)))))
    (testing "emits no memory-write or tool effect"
      (is (not (contains? fx :memory/write)))
      (is (not (contains? fx :tool/invoke))))))

(deftest assistant-response-handler-commits-turn
  (let [fx ((handler :assistant/response)
            {:db {:conversation [{:role :user :content "hi"}]}
             :event {:event/type :assistant/response :content "hello!"}})]
    (is (= {:role :assistant :content "hello!"} (last (:conversation (:db fx)))))
    (is (= {:event/type :assistant/response :content "hello!"} (:event/store fx)))))

(deftest llm-invoke-effect-streams-and-dispatches
  (testing "streams deltas to the UI and dispatches the full text as one event"
    (let [deltas     (atom [])
          dispatched (atom nil)
          stub       (reify provider/LLMProvider
                       (invoke [_ _ _] "Hello world")
                       (stream [_ _ _ on-delta]
                         (doseq [d ["Hello" " " "world"]] (on-delta d))
                         "Hello world"))
          ctx        {:llm-provider stub
                      :emit-delta!  #(swap! deltas conj %)
                      :dispatch!    #(reset! dispatched %)}
          fut        (executor/execute-effect
                      :llm/invoke {:messages [{:role :user :content "hi"}]} ctx)]
      @fut                                        ; the effect runs on a future
      (is (= ["Hello" " " "world"] @deltas) "each delta pushed live to the UI")
      (is (= {:event/type :assistant/response :content "Hello world"} @dispatched)
          "full accumulated text dispatched as a single :assistant/response"))))

(deftest turn-replays-without-calling-the-llm
  (testing "a stored turn reconstructs the conversation from events alone"
    (let [events [{:event/type :user/message     :content "hi"}
                  {:event/type :assistant/response :content "hello!"}]
          final  (replay/replay events)]
      (is (= [{:role :user :content "hi"}
              {:role :assistant :content "hello!"}]
             (:conversation final))
          "both turns reconstructed; :llm/invoke is skipped on replay (no LLM call)"))))
