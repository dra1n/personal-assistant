(ns pa.runtime.tool-call-test
  "The single-hop LLM tool-call path: :user/message -> :llm/invoke ->
  :assistant/tool-call -> :tool/invoke -> :tool/result -> follow-up :llm/invoke
  -> :assistant/response. Handlers are tested as pure functions; the effect
  branch is tested with a stub provider; replay proves the whole turn
  reconstructs from events with no LLM or tool execution."
  (:require [clojure.test :refer [deftest is testing]]
            [pa.llm.provider :as provider]
            [pa.runtime.executor :as executor]
            [pa.runtime.handlers]                 ; registers the handlers
            [pa.runtime.registry :as registry]
            [pa.runtime.replay :as replay]))

(defn- h [event-type] (:fn (registry/get-handler event-type)))

(def ^:private one-call
  [{:id "call_1" :name :fs/read-file :arguments {:path "a.txt"}}])

;; ---------------------------------------------------------------------------
;; :assistant/tool-call handler
;; ---------------------------------------------------------------------------

(deftest assistant-tool-call-records-turn-and-invokes-tool
  (let [event {:event/type :assistant/tool-call :content "" :tool-calls one-call}
        fx    ((h :assistant/tool-call) {:db {:conversation []} :event event})]
    (testing "the assistant tool-call request is appended to the conversation and stored"
      (is (= {:role :assistant :content "" :tool-calls one-call}
             (last (:conversation (:db fx)))))
      (is (= event (:event/store fx))))
    (testing "it emits :tool/invoke carrying the call-id and follow-up marker"
      (is (= {:tool/name      :fs/read-file
              :tool/args      {:path "a.txt"}
              :tool/call-id   "call_1"
              :llm/follow-up? true}
             (:tool/invoke fx))))))

;; ---------------------------------------------------------------------------
;; :tool/result handler — LLM-driven vs direct
;; ---------------------------------------------------------------------------

(deftest tool-result-from-llm-appends-turn-and-follows-up
  (let [db    {:identity {}
               :conversation [{:role :user :content "read a.txt"}
                              {:role :assistant :content "" :tool-calls one-call}]}
        event {:event/type :tool/result :tool/name :fs/read-file :tool/args {:path "a.txt"}
               :tool/status :ok :tool/output {:content "hello"}
               :tool/call-id "call_1" :llm/follow-up? true}
        fx    ((h :tool/result) {:db db :event event})]
    (testing "the result is recorded in :tool/results"
      (is (= 1 (count (:tool/results (:db fx))))))
    (testing "a :role :tool turn is appended to the conversation"
      (let [turn (last (:conversation (:db fx)))]
        (is (= :tool (:role turn)))
        (is (= "call_1" (:tool-call-id turn)))
        (is (string? (:content turn)))))
    (testing "it re-invokes the LLM with no tools advertised (single hop)"
      (is (vector? (get-in fx [:llm/invoke :messages])))
      (is (not (contains? (:llm/invoke fx) :opts))
          "follow-up advertises no tools, so the model must answer in text"))))

(deftest tool-result-direct-invocation-only-records
  (let [event {:event/type :tool/result :tool/name :fs/read-file :tool/args {}
               :tool/status :ok :tool/output 1}
        fx    ((h :tool/result) {:db {:conversation []} :event event})]
    (is (= 1 (count (:tool/results (:db fx)))))
    (is (= [] (:conversation (:db fx))) "no conversation turn for a non-LLM tool call")
    (is (not (contains? fx :llm/invoke)) "no follow-up")))

;; ---------------------------------------------------------------------------
;; :llm/invoke effect — branches on tool calls
;; ---------------------------------------------------------------------------

(defn- stub-returning [result]
  (reify provider/LLMProvider
    (invoke [_ _ _] result)
    (stream [_ _ _ _] result)))

(deftest llm-invoke-dispatches-tool-call-when-model-requests-tools
  (let [dispatched (atom nil)
        ctx        {:llm-provider (stub-returning {:content "" :tool-calls one-call})
                    :emit-delta!  (fn [_])
                    :dispatch!    #(reset! dispatched %)}]
    @(executor/execute-effect :llm/invoke {:messages []} ctx)
    (is (= :assistant/tool-call (:event/type @dispatched)))
    (is (= one-call (:tool-calls @dispatched)))))

(deftest llm-invoke-dispatches-response-when-text-only
  (let [dispatched (atom nil)
        ctx        {:llm-provider (stub-returning (provider/text-result "just text"))
                    :emit-delta!  (fn [_])
                    :dispatch!    #(reset! dispatched %)}]
    @(executor/execute-effect :llm/invoke {:messages []} ctx)
    (is (= {:event/type :assistant/response :content "just text"} @dispatched))))

;; ---------------------------------------------------------------------------
;; Replay — the four-event tool turn reconstructs with no execution
;; ---------------------------------------------------------------------------

(deftest tool-turn-replays-into-conversation
  (testing "user -> tool-call -> tool-result -> response reconstructs the full
            conversation from events; no LLM call and no tool run on replay"
    (let [events [{:event/type :user/message :content "read a.txt"}
                  {:event/type :assistant/tool-call :content "" :tool-calls one-call}
                  {:event/type :tool/result :tool/name :fs/read-file :tool/args {:path "a.txt"}
                   :tool/status :ok :tool/output {:content "hello"}
                   :tool/call-id "call_1" :llm/follow-up? true}
                  {:event/type :assistant/response :content "it says hello"}]
          final  (replay/replay events)]
      (is (= [:user :assistant :tool :assistant] (mapv :role (:conversation final))))
      (is (= one-call (:tool-calls (second (:conversation final)))))
      (is (= "it says hello" (:content (last (:conversation final))))))))
