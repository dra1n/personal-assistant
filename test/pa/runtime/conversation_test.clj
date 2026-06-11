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

(deftest user-message-handler-history-append-schema
  (testing ":history/append entry has required schema fields"
    (let [db {:conversation [] :identity {:identity {:front-matter {:name "Aria"} :prose ""}}}
          fx ((handler :user/message)
              {:db db :event {:event/type :user/message :content "hello"}})]
      (is (= "hello" (:history/text (:history/append fx))))
      (is (uuid? (:history/id (:history/append fx))))
      (is (inst? (:history/timestamp (:history/append fx)))))))

(deftest user-message-handler-deduplicates-history
  (let [base-db {:conversation []
                 :identity     {:identity {:front-matter {:name "Aria"} :prose ""}}}]
    (testing "new message: appends to :ui/history and emits :history/append"
      (let [db (assoc base-db :ui/history [{:history/text "old"}])
            fx ((handler :user/message)
                {:db db :event {:event/type :user/message :content "hello"}})]
        (is (= "hello" (:history/text (last (:ui/history (:db fx)))))
            "entry appended to db history")
        (is (= "hello" (:history/text (:history/append fx)))
            ":history/append effect emitted")))

    (testing "duplicate message: skips :ui/history append and omits :history/append"
      (let [db (assoc base-db :ui/history [{:history/text "hello"}])
            fx ((handler :user/message)
                {:db db :event {:event/type :user/message :content "hello"}})]
        (is (= 1 (count (:ui/history (:db fx))))
            "history length unchanged")
        (is (not (contains? fx :history/append))
            ":history/append effect absent")))

    (testing "consecutive duplicate: second call with same text omits effect"
      (let [db1 (assoc base-db :ui/history [])
            fx1 ((handler :user/message)
                 {:db db1 :event {:event/type :user/message :content "ping"}})
            db2 (:db fx1)
            fx2 ((handler :user/message)
                 {:db db2 :event {:event/type :user/message :content "ping"}})]
        (is (contains? fx1 :history/append) "first call emits effect")
        (is (not (contains? fx2 :history/append)) "second call omits effect")))))

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
                       (invoke [_ _ _] (provider/text-result "Hello world"))
                       (stream [_ _ _ on-delta]
                         (doseq [d ["Hello" " " "world"]] (on-delta d))
                         (provider/text-result "Hello world")))
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

;; ---------------------------------------------------------------------------
;; Session turn counter
;; ---------------------------------------------------------------------------

(defn- run-messages
  "Simulate n :user/message dispatches through the handler, chaining db state.
  Returns the sequence of effect maps produced."
  [n config]
  (let [base {:conversation [] :identity {:identity {:front-matter {} :prose ""}}}]
    (loop [db base i 0 acc []]
      (if (= i n)
        acc
        (let [fx ((handler :user/message)
                  {:db db :event {:event/type :user/message :content (str "msg-" i)}
                   :config config})]
          (recur (:db fx) (inc i) (conj acc fx)))))))

(deftest session-threshold-fires-at-nth-turn
  (testing ":session/threshold-reached dispatched at exactly the Nth turn"
    (let [fxs (run-messages 3 {:session/extraction-threshold 3})]
      (is (nil?    (:dispatch (nth fxs 0))) "no dispatch at turn 1")
      (is (nil?    (:dispatch (nth fxs 1))) "no dispatch at turn 2")
      (is (= {:event/type :session/threshold-reached :turn-count 3}
             (:dispatch (nth fxs 2)))       "dispatch at turn 3"))))

(deftest session-threshold-fires-at-each-multiple
  (testing ":session/threshold-reached dispatched at every multiple of the threshold"
    (let [fxs (run-messages 6 {:session/extraction-threshold 3})]
      (is (= {:event/type :session/threshold-reached :turn-count 3} (:dispatch (nth fxs 2))))
      (is (= {:event/type :session/threshold-reached :turn-count 6} (:dispatch (nth fxs 5)))))))

(deftest session-threshold-ten-fires-at-turn-ten
  (testing "threshold of 10 fires at turn 10"
    (let [fxs (run-messages 10 {:session/extraction-threshold 10})]
      (is (every? nil? (map :dispatch (take 9 fxs))) "no dispatch before turn 10")
      (is (= {:event/type :session/threshold-reached :turn-count 10}
             (:dispatch (last fxs)))))))

(deftest session-threshold-handler-dispatches-extraction
  (testing ":session/threshold-reached handler emits :extraction/run dispatch"
    (let [fx ((handler :session/threshold-reached)
              {:db {} :event {:event/type :session/threshold-reached :turn-count 10}})]
      (is (= {:event/type :extraction/run} (:dispatch fx))))))
