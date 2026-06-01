(ns pa.llm.openai
  "OpenAI chat-completions provider — full implementation of pa.llm.provider.

  HTTP via hato (JVM HttpClient). `stream` opens a streaming SSE response and
  feeds each content delta to the on-delta callback; `invoke` does a plain
  non-streaming request and returns the full message.

  The SSE line parsing (parse-sse-line) is a pure function so it can be
  unit-tested against fixture chunks with no network (Group E)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pa.http :as http]
            [pa.llm.provider :as provider]))

(def ^:private default-base-url "https://api.openai.com/v1")
(def ^:private default-model "gpt-4o-mini")

;; ---------------------------------------------------------------------------
;; Pure SSE parsing
;; ---------------------------------------------------------------------------

(defn parse-sse-line
  "Parse a single line from an OpenAI streaming response. Returns:
     {:done true}      on the [DONE] sentinel
     {:delta \"...\"}    when the line carries a non-empty content delta
     nil               for anything else — blank lines, SSE comments,
                       keep-alives, and role-only/empty-delta chunks.

  Pure: no I/O, safe to test against fixture strings."
  [line]
  (when (and line (str/starts-with? line "data:"))
    (let [payload (str/trim (subs line (count "data:")))]
      (cond
        (= payload "[DONE]") {:done true}
        (str/blank? payload) nil
        :else
        (let [content (-> (json/read-str payload :key-fn keyword)
                          (get-in [:choices 0 :delta :content]))]
          (when (and content (not (str/blank? content)))
            {:delta content}))))))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- ->api-messages
  "Convert prompt-assembly messages ({:role kw :content str}) into the
  JSON-bound shape OpenAI expects (string roles)."
  [messages]
  (mapv (fn [{:keys [role content]}]
          {:role (name role) :content content})
        messages))

(defn- request-body [model messages stream? opts]
  (json/write-str
   (merge {:model    model
           :messages (->api-messages messages)
           :stream   stream?}
          (dissoc opts :model :api-key :base-url))))

(defn- post-opts [api-key body as]
  {:headers {"authorization" (str "Bearer " api-key)
             "content-type"  "application/json"}
   :body    body
   :as      as})

(defn- require-key! [api-key]
  (when (str/blank? api-key)
    (throw (ex-info "OpenAI API key missing — set OPENAI_API_KEY or pass :api-key"
                    {:provider :openai}))))

;; ---------------------------------------------------------------------------
;; Streaming consumption
;; ---------------------------------------------------------------------------

(defn- consume-stream!
  "Read the SSE InputStream line by line, invoking on-delta for each content
  delta and accumulating the full text. Returns the full text on [DONE] or
  end-of-stream."
  [^java.io.InputStream in on-delta]
  (with-open [r (io/reader in)]
    (let [sb (StringBuilder.)]
      (loop [lines (line-seq r)]
        (if-let [line (first lines)]
          (let [{:keys [done delta]} (parse-sse-line line)]
            (cond
              done  (.toString sb)
              delta (do (on-delta delta)
                        (.append sb delta)
                        (recur (rest lines)))
              :else (recur (rest lines))))
          (.toString sb))))))

;; ---------------------------------------------------------------------------
;; Provider
;; ---------------------------------------------------------------------------

(defrecord OpenAIProvider [api-key base-url model http]
  provider/LLMProvider
  (invoke [_ messages opts]
    (require-key! api-key)
    (let [model* (or (:model opts) model)
          body   (request-body model* messages false opts)
          resp   (http/post http (str base-url "/chat/completions")
                            (post-opts api-key body :string))]
      (-> (json/read-str (:body resp) :key-fn keyword)
          (get-in [:choices 0 :message :content]))))
  (stream [_ messages opts on-delta]
    (require-key! api-key)
    (let [model* (or (:model opts) model)
          body   (request-body model* messages true opts)
          resp   (http/post http (str base-url "/chat/completions")
                            (post-opts api-key body :stream))]
      (consume-stream! (:body resp) on-delta))))

(defn make-provider
  "Construct an OpenAIProvider. Reads the API key from :api-key or the
  OPENAI_API_KEY env var; the key is not validated until a call is made.
  :http defaults to the hato-backed client; tests may inject a fake."
  ([] (make-provider {}))
  ([{:keys [api-key base-url model http]}]
   (->OpenAIProvider (or api-key (System/getenv "OPENAI_API_KEY"))
                     (or base-url default-base-url)
                     (or model default-model)
                     (or http (http/hato-client)))))
