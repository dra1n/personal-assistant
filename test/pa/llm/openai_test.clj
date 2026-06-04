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
  (post  [_ url opts]
    (reset! captured {:url url :opts opts})
    response)
  (fetch [_ _url _opts]
    (throw (UnsupportedOperationException. "FakeClient does not implement fetch"))))

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
      (is (= "Hello world" (:content result)) "returns the accumulated text")
      (is (= [] (:tool-calls result)) "no tool calls in a plain text response"))))

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
      (is (= "42" (:content (provider/invoke prov [{:role :user :content "q"}] {}))))
      (is (= :string (:as (:opts @captured))) "non-streaming requests a string body")
      (is (false? (:stream (json/read-str (:body (:opts @captured)) :key-fn keyword)))))))

;; ---------------------------------------------------------------------------
;; Tool calls
;; ---------------------------------------------------------------------------

(deftest parse-sse-line-tool-call-test
  (testing "a tool_calls delta is surfaced as raw fragments; content still wins"
    (is (= {:tool-calls [{:index 0 :id "call_1"
                          :function {:name "fs__read-file" :arguments "{\"pa"}}]}
           (openai/parse-sse-line
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"fs__read-file\",\"arguments\":\"{\\\"pa\"}}]}}]}")))))

(def ^:private tool-call-sse
  ["data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"
   "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"fs__read-file\",\"arguments\":\"\"}}]}}]}"
   "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\"}}]}}]}"
   "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]}}]}"
   "data: [DONE]"])

(deftest stream-assembles-tool-calls-test
  (testing "streamed tool_call fragments assemble into a decoded, parsed tool call"
    (let [captured (atom nil)
          client   (fake-client captured {:body (sse-stream tool-call-sse)})
          prov     (openai/make-provider {:api-key "sk-test" :http client})
          deltas   (atom [])
          result   (provider/stream prov [{:role :user :content "read a.txt"}] {}
                                    #(swap! deltas conj %))]
      (is (= [] @deltas) "a tool call streams no text deltas")
      (is (= "" (:content result)))
      (is (= [{:id "call_1" :name :fs/read-file :arguments {:path "a.txt"}}]
             (:tool-calls result))
          "name decoded to a keyword and arguments parsed from the concatenated JSON"))))

(deftest invoke-parses-tool-calls-test
  (testing "non-streaming invoke parses tool_calls from the message"
    (let [captured (atom nil)
          body     (json/write-str
                    {:choices [{:message {:role "assistant" :content nil
                                          :tool_calls [{:id "call_9"
                                                        :function {:name "fs__write-file"
                                                                   :arguments "{\"path\":\"out.txt\",\"content\":\"hi\"}"}}]}}]})
          client   (fake-client captured {:body body})
          prov     (openai/make-provider {:api-key "sk-test" :http client})
          result   (provider/invoke prov [{:role :user :content "write"}] {})]
      (is (= "" (:content result)))
      (is (= [{:id "call_9" :name :fs/write-file
               :arguments {:path "out.txt" :content "hi"}}]
             (:tool-calls result))))))

(deftest request-advertises-tools-test
  (testing ":tools in opts are translated into OpenAI function specs with encoded names"
    (let [captured (atom nil)
          client   (fake-client captured {:body (sse-stream ["data: [DONE]"])})
          prov     (openai/make-provider {:api-key "sk-test" :http client})
          tools    [{:name        :fs/read-file
                     :description "Read a file."
                     :parameters  {:type "object" :properties {:path {:type "string"}} :required [:path]}}]]
      (provider/stream prov [{:role :user :content "hi"}] {:tools tools} (fn [_]))
      (let [body (json/read-str (:body (:opts @captured)) :key-fn keyword)
            fns  (:tools body)]
        (is (= 1 (count fns)))
        (is (= "function" (get-in fns [0 :type])))
        (is (= "fs__read-file" (get-in fns [0 :function :name])) "slash encoded for the API")
        (is (= "Read a file." (get-in fns [0 :function :description])))
        (is (= "object" (get-in fns [0 :function :parameters :type])))))))

(deftest request-serializes-tool-turns-test
  (testing "assistant tool-call and tool-result turns serialize to OpenAI shapes"
    (let [captured (atom nil)
          client   (fake-client captured {:body (sse-stream ["data: [DONE]"])})
          prov     (openai/make-provider {:api-key "sk-test" :http client})
          msgs     [{:role :user :content "read a.txt"}
                    {:role :assistant :content ""
                     :tool-calls [{:id "call_1" :name :fs/read-file :arguments {:path "a.txt"}}]}
                    {:role :tool :tool-call-id "call_1" :content "\"file contents\""}]]
      (provider/stream prov msgs {} (fn [_]))
      (let [api-msgs (:messages (json/read-str (:body (:opts @captured)) :key-fn keyword))]
        (is (= "fs__read-file" (get-in api-msgs [1 :tool_calls 0 :function :name])))
        (is (= "{\"path\":\"a.txt\"}" (get-in api-msgs [1 :tool_calls 0 :function :arguments]))
            "arguments serialized back to a JSON string")
        (is (= {:role "tool" :tool_call_id "call_1" :content "\"file contents\""}
               (api-msgs 2)))))))

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
