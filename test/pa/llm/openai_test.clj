(ns pa.llm.openai-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pa.http :as http]
            [pa.llm.openai :as openai]
            [pa.llm.provider :as provider])
  (:import [java.io ByteArrayInputStream]))

;; ---------------------------------------------------------------------------
;; Fake HTTP client — records the request and returns a canned response,
;; so the provider is exercised end-to-end with no network.
;; ---------------------------------------------------------------------------

(defrecord FakeClient [captured response]
  http/HttpClient
  (post [_ url opts]
    (reset! captured {:url url :opts opts})
    response))

(defn- fake-client [captured response]
  (->FakeClient captured response))

(defn- sse-stream
  "An InputStream of newline-terminated SSE lines."
  [lines]
  (ByteArrayInputStream. (.getBytes (str (str/join "\n" lines) "\n") "UTF-8")))

(def ^:private chat-sse
  ["data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"
   "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}"
   "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}"
   ": keep-alive"
   "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}"
   "data: [DONE]"])

;; ---------------------------------------------------------------------------
;; Pure SSE parsing
;; ---------------------------------------------------------------------------

(deftest parse-sse-line-test
  (testing "every line shape"
    (is (= {:done true} (openai/parse-sse-line "data: [DONE]")))
    (is (= {:delta "Hi"}
           (openai/parse-sse-line "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}")))
    (is (nil? (openai/parse-sse-line "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"))
        "role-only opening chunk carries no content")
    (is (nil? (openai/parse-sse-line "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}"))
        "empty-string content is skipped")
    (is (nil? (openai/parse-sse-line "")) "blank line")
    (is (nil? (openai/parse-sse-line ": keep-alive")) "SSE comment / keep-alive")
    (is (nil? (openai/parse-sse-line "data: ")) "empty data payload")))

;; ---------------------------------------------------------------------------
;; stream
;; ---------------------------------------------------------------------------

(deftest stream-accumulates-deltas-test
  (testing "stream feeds each content delta to on-delta and returns full text"
    (let [captured (atom nil)
          client   (fake-client captured {:body (sse-stream chat-sse)})
          prov     (openai/make-provider {:api-key "sk-test" :http client})
          deltas   (atom [])
          result   (provider/stream prov
                                    [{:role :system :content "sys"}
                                     {:role :user   :content "hi"}]
                                    {}
                                    #(swap! deltas conj %))]
      (is (= ["Hel" "lo" " world"] @deltas) "only non-empty content deltas, in order")
      (is (= "Hello world" result) "returns the accumulated text"))))

(deftest stream-request-shape-test
  (testing "builds a streaming chat-completions POST with auth header + messages"
    (let [captured (atom nil)
          client   (fake-client captured {:body (sse-stream ["data: [DONE]"])})
          prov     (openai/make-provider {:api-key "sk-test" :model "gpt-x" :http client})]
      (provider/stream prov [{:role :user :content "hi"}] {} (fn [_]))
      (let [{:keys [url opts]} @captured
            body (json/read-str (:body opts) :key-fn keyword)]
        (is (str/ends-with? url "/chat/completions"))
        (is (= "Bearer sk-test" (get-in opts [:headers "authorization"])))
        (is (= :stream (:as opts)))
        (is (true? (:stream body)))
        (is (= "gpt-x" (:model body)))
        (is (= [{:role "user" :content "hi"}] (:messages body))
            "messages roles are stringified for the API")))))

(deftest stream-opts-override-model-test
  (testing "per-call :model in opts overrides the provider default"
    (let [captured (atom nil)
          client   (fake-client captured {:body (sse-stream ["data: [DONE]"])})
          prov     (openai/make-provider {:api-key "sk-test" :model "default-m" :http client})]
      (provider/stream prov [{:role :user :content "hi"}] {:model "override-m"} (fn [_]))
      (is (= "override-m" (:model (json/read-str (:body (:opts @captured)) :key-fn keyword)))))))

;; ---------------------------------------------------------------------------
;; invoke
;; ---------------------------------------------------------------------------

(deftest invoke-returns-content-test
  (testing "invoke does a non-streaming request and returns message content"
    (let [captured (atom nil)
          body     (json/write-str {:choices [{:message {:role "assistant" :content "42"}}]})
          client   (fake-client captured {:body body})
          prov     (openai/make-provider {:api-key "sk-test" :http client})]
      (is (= "42" (provider/invoke prov [{:role :user :content "q"}] {})))
      (is (= :string (:as (:opts @captured))) "non-streaming requests a string body")
      (is (false? (:stream (json/read-str (:body (:opts @captured)) :key-fn keyword)))))))

;; ---------------------------------------------------------------------------
;; key guard
;; ---------------------------------------------------------------------------

(deftest missing-key-throws-before-http-test
  (testing "a blank api key throws and makes no HTTP call"
    (let [captured (atom nil)
          client   (fake-client captured {:body (sse-stream ["data: [DONE]"])})
          prov     (openai/make-provider {:api-key "" :http client})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"API key missing"
                            (provider/stream prov [{:role :user :content "x"}] {} (fn [_]))))
      (is (nil? @captured) "HTTP client was never called"))))
