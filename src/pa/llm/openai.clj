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

;; ---------------------------------------------------------------------------
;; Tool-name encoding
;;
;; Registry tool names are qualified keywords (e.g. :fs/read-file) but OpenAI
;; function names may not contain '/'. Encode the slash as '__' and decode back.
;; ---------------------------------------------------------------------------

(defn- encode-name [kw]
  (str (namespace kw) "__" (name kw)))

(defn- decode-name [s]
  (let [[ns* n] (str/split s #"__" 2)]
    (if n (keyword ns* n) (keyword s))))

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
        (let [delta   (-> (json/read-str payload :key-fn keyword)
                          (get-in [:choices 0 :delta]))
              content (:content delta)
              tcs     (:tool_calls delta)]
          (cond
            (not-empty content) {:delta content}
            (seq tcs)           {:tool-calls tcs}
            :else               nil))))))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- ->api-messages
  "Convert prompt-assembly messages into the JSON-bound shape OpenAI expects.
  Plain turns become {:role str :content str}; an assistant turn carrying
  :tool-calls and a tool-result turn carrying :tool-call-id are serialized to
  OpenAI's tool-calling message shapes."
  [messages]
  (mapv (fn [{:keys [role content tool-calls tool-call-id]}]
          (cond
            (seq tool-calls)
            {:role       "assistant"
             :content    (or content "")
             :tool_calls (mapv (fn [tc]
                                 {:id       (:id tc)
                                  :type     "function"
                                  :function {:name      (encode-name (:name tc))
                                             :arguments (json/write-str (:arguments tc))}})
                               tool-calls)}

            tool-call-id
            {:role "tool" :tool_call_id tool-call-id :content content}

            :else
            {:role (name role) :content content}))
        messages))

(defn- ->api-tools
  "Convert provider-neutral advertised tool specs (registry/advertise) into
  OpenAI's tools array."
  [tools]
  (mapv (fn [{:keys [name description parameters]}]
          {:type     "function"
           :function {:name        (encode-name name)
                      :description description
                      :parameters  parameters}})
        tools))

(defn- request-body [model messages stream? opts]
  (let [tools (:tools opts)
        extra (dissoc opts :model :api-key :base-url :tools)]
    (json/write-str
     (cond-> (merge {:model    model
                     :messages (->api-messages messages)
                     :stream   stream?}
                    extra)
       (seq tools) (assoc :tools (->api-tools tools))))))

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

(defn- accumulate-tool-calls
  "Merge streamed tool_call fragments into acc (index -> {:id :name :args}).
  Arguments stream as string fragments and are concatenated."
  [acc fragments]
  (reduce (fn [m frag]
            (update m (:index frag)
                    (fn [cur]
                      (-> (or cur {:args ""})
                          (cond-> (:id frag) (assoc :id (:id frag)))
                          (cond-> (get-in frag [:function :name])
                            (assoc :name (get-in frag [:function :name])))
                          (update :args str (or (get-in frag [:function :arguments]) ""))))))
          acc fragments))

(defn- finalize-tool-calls
  "Turn the index->fragment accumulator into provider-neutral tool-calls,
  ordered by index, with names decoded and arguments parsed from JSON."
  [acc]
  (->> (sort-by key acc)
       (mapv (fn [[_ {:keys [id name args]}]]
               {:id        id
                :name      (decode-name name)
                :arguments (if (str/blank? args) {} (json/read-str args :key-fn keyword))}))))

(defn- result [sb acc]
  {:content (.toString ^StringBuilder sb) :tool-calls (finalize-tool-calls acc)})

(defn- consume-stream!
  "Read the SSE InputStream line by line: feed each content delta to on-delta
  and accumulate text, and assemble any streamed tool_call fragments. Returns
  {:content <text> :tool-calls [...]} on [DONE] or end-of-stream."
  [^java.io.InputStream in on-delta]
  (with-open [r (io/reader in)]
    (let [sb (StringBuilder.)]
      (loop [lines (line-seq r)
             acc   {}]
        (if-let [line (first lines)]
          (let [parsed (parse-sse-line line)]
            (cond
              (:done parsed)       (result sb acc)
              (:delta parsed)      (do (on-delta (:delta parsed))
                                       (.append sb ^String (:delta parsed))
                                       (recur (rest lines) acc))
              (:tool-calls parsed) (recur (rest lines)
                                          (accumulate-tool-calls acc (:tool-calls parsed)))
              :else                (recur (rest lines) acc)))
          (result sb acc))))))

(defn- parse-message
  "Parse a non-streaming choices[0].message into the provider result shape."
  [msg]
  {:content    (or (:content msg) "")
   :tool-calls (mapv (fn [tc]
                       (let [args (get-in tc [:function :arguments])]
                         {:id        (:id tc)
                          :name      (decode-name (get-in tc [:function :name]))
                          :arguments (if (str/blank? args) {} (json/read-str args :key-fn keyword))}))
                     (:tool_calls msg))})

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
      (parse-message (-> (json/read-str (:body resp) :key-fn keyword)
                         (get-in [:choices 0 :message])))))
  (stream [_ messages opts on-delta]
    (require-key! api-key)
    (let [model* (or (:model opts) model)
          body   (request-body model* messages true opts)
          resp   (http/post http (str base-url "/chat/completions")
                            (post-opts api-key body :stream))]
      (consume-stream! (:body resp) on-delta))))

(defn make-provider
  "Construct an OpenAIProvider from explicit settings — :api-key, :base-url
  and :model come from the :llm/provider integrant config (system.edn), which
  is where defaults and env overrides live. The key is not validated until a
  call is made. :http defaults to the hato-backed client; tests may inject a
  fake."
  [{:keys [api-key base-url model http]}]
  (->OpenAIProvider api-key base-url model (or http (http/hato-client))))
