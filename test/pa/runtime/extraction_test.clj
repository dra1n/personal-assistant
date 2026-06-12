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
  (testing "emits :extraction/classify with the full conversation"
    (let [fx ((handler :extraction/run)
              {:db {:conversation sample-turns} :event {:event/type :extraction/run}})]
      (is (= sample-turns (get-in fx [:extraction/classify :turns]))))))

(deftest extraction-run-skips-empty-conversation
  (testing "returns nil when conversation is empty"
    (let [fx ((handler :extraction/run)
              {:db {:conversation []} :event {:event/type :extraction/run}})]
      (is (nil? fx)))))

;; ---------------------------------------------------------------------------
;; :extraction/classify effect
;; ---------------------------------------------------------------------------

(defn- stub-llm [response-json]
  (reify provider/LLMProvider
    (invoke [_ _ _] (provider/text-result response-json))
    (stream [_ _ _ _] (provider/text-result response-json))))

(deftest classify-effect-writes-ephemeral-and-permanent
  (testing "routes ephemeral items through :memory/write and permanent to merge-wisdom!"
    (let [written  (atom [])
          merged   (atom nil)
          json     "{\"ephemeral\":[{\"title\":\"T\",\"summary\":\"S\"}],\"permanent\":[\"User likes Clojure\"]}"
          ctx      {:llm-provider  (stub-llm json)
                    :write-memory! #(do (swap! written conj %) %)
                    :dispatch!     (constantly nil)
                    :merge-wisdom! #(reset! merged %)}]
      (executor/execute-effect :extraction/classify {:turns sample-turns} ctx)
      (is (= 1 (count @written)))
      (is (= :episodic (:memory/type (first @written))))
      (is (= "T" (:memory/title (first @written))))
      (is (= ["User likes Clojure"] @merged)))))

(deftest classify-effect-handles-empty-response
  (testing "does not call write-memory! or merge-wisdom! when model extracts nothing"
    (let [written  (atom [])
          merged   (atom :untouched)
          ctx      {:llm-provider  (stub-llm "{\"ephemeral\":[],\"permanent\":[]}")
                    :dispatch!     (constantly nil)
                    :write-memory! #(swap! written conj %)
                    :merge-wisdom! #(reset! merged %)}]
      (executor/execute-effect :extraction/classify {:turns sample-turns} ctx)
      (is (= [] @written))
      (is (= :untouched @merged)))))

(deftest classify-effect-skips-without-llm-provider
  (testing "no-ops silently when :llm-provider is absent"
    (let [written (atom [])]
      (executor/execute-effect :extraction/classify {:turns sample-turns}
                               {:write-memory! #(swap! written conj %)})
      (is (= [] @written)))))
