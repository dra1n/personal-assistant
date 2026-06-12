(ns pa.runtime.extraction-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.llm.provider :as provider]
            [pa.runtime.executor :as executor]
            [pa.runtime.handlers]
            [pa.runtime.registry :as registry]))

(defn- handler [event-type]
  (:fn (registry/get-handler event-type)))

(def ^:private sample-turns
  [{:role :user      :content "I like Clojure."}
   {:role :assistant :content "Great choice!"}])

;; ---------------------------------------------------------------------------
;; :extraction/run handler
;; ---------------------------------------------------------------------------

(deftest extraction-run-emits-classify-effect
  (testing "emits :extraction/classify with conversation and done when non-empty"
    (let [done (promise)
          fx   ((handler :extraction/run)
                {:db {:conversation sample-turns}
                 :event {:event/type :extraction/run :done done}})]
      (is (= sample-turns (get-in fx [:extraction/classify :turns])))
      (is (= done (get-in fx [:extraction/classify :done]))))))

(deftest extraction-run-dispatches-done-when-empty
  (testing "dispatches :extraction/done directly when conversation is empty"
    (let [done (promise)
          fx   ((handler :extraction/run)
                {:db {:conversation []}
                 :event {:event/type :extraction/run :done done}})]
      (is (= {:event/type :extraction/done :done done} (:dispatch fx))))))

;; ---------------------------------------------------------------------------
;; :extraction/write-memory handler
;; ---------------------------------------------------------------------------

(deftest extraction-write-memory-emits-memory-write
  (testing "returns :memory/write effect with the record"
    (let [record {:memory/type :episodic :memory/title "T" :memory/summary "S"}
          fx     ((handler :extraction/write-memory)
                  {:db {} :event {:event/type :extraction/write-memory :record record}})]
      (is (= record (:memory/write fx))))))

;; ---------------------------------------------------------------------------
;; :extraction/merge-wisdom handler
;; ---------------------------------------------------------------------------

(deftest extraction-merge-wisdom-emits-wisdom-merge
  (testing "returns :wisdom/merge effect with the items"
    (let [fx ((handler :extraction/merge-wisdom)
              {:db {} :event {:event/type :extraction/merge-wisdom :items ["fact"]}})]
      (is (= ["fact"] (:wisdom/merge fx))))))

;; ---------------------------------------------------------------------------
;; :extraction/done handler
;; ---------------------------------------------------------------------------

(deftest extraction-done-delivers-promise
  (testing "delivers the :done promise when present"
    (let [p (promise)]
      ((handler :extraction/done)
       {:db {} :event {:event/type :extraction/done :done p}})
      (is (realized? p))
      (is (= :ok @p)))))

(deftest extraction-done-tolerates-nil-promise
  (testing "does not throw when :done is absent"
    (is (= {} ((handler :extraction/done)
               {:db {} :event {:event/type :extraction/done}})))))

;; ---------------------------------------------------------------------------
;; :extraction/classify effect
;; ---------------------------------------------------------------------------

(defn- stub-llm [response-json]
  (reify provider/LLMProvider
    (invoke [_ _ _] (provider/text-result response-json))
    (stream [_ _ _ _] (provider/text-result response-json))))

(deftest classify-effect-dispatches-write-and-merge-events
  (testing "dispatches :extraction/write-memory, :extraction/merge-wisdom, then :extraction/done"
    (let [dispatched (atom [])
          json       "{\"ephemeral\":[{\"title\":\"T\",\"summary\":\"S\"}],\"permanent\":[\"fact\"]}"
          ctx        {:llm-provider (stub-llm json)
                      :dispatch!    #(swap! dispatched conj %)}]
      (executor/execute-effect :extraction/classify {:turns sample-turns :done nil} ctx)
      (is (= :extraction/write-memory (:event/type (first @dispatched))))
      (is (= :episodic (get-in @dispatched [0 :record :memory/type])))
      (is (= :extraction/merge-wisdom  (:event/type (second @dispatched))))
      (is (= ["fact"] (:items (second @dispatched))))
      (is (= :extraction/done (:event/type (last @dispatched)))))))

(deftest classify-effect-dispatches-done-on-empty-response
  (testing "dispatches :extraction/done even when model extracts nothing"
    (let [dispatched (atom [])
          ctx        {:llm-provider (stub-llm "{\"ephemeral\":[],\"permanent\":[]}")
                      :dispatch!    #(swap! dispatched conj %)}]
      (executor/execute-effect :extraction/classify {:turns sample-turns :done nil} ctx)
      (is (= 1 (count @dispatched)))
      (is (= :extraction/done (:event/type (first @dispatched)))))))

(deftest classify-effect-dispatches-done-without-llm
  (testing "dispatches :extraction/done even when :llm-provider is absent"
    (let [dispatched (atom [])
          ctx        {:dispatch! #(swap! dispatched conj %)}]
      (executor/execute-effect :extraction/classify {:turns sample-turns :done nil} ctx)
      (is (= 1 (count @dispatched)))
      (is (= :extraction/done (:event/type (first @dispatched)))))))

;; ---------------------------------------------------------------------------
;; :wisdom/merge effect
;; ---------------------------------------------------------------------------

(deftest wisdom-merge-effect-calls-merge-wisdom
  (testing ":wisdom/merge effect calls merge-wisdom! with the items"
    (let [merged (atom nil)
          ctx    {:merge-wisdom! #(reset! merged %)}]
      (executor/execute-effect :wisdom/merge ["fact one" "fact two"] ctx)
      (is (= ["fact one" "fact two"] @merged)))))
