(ns pa.llm.provider
  "LLM provider protocol — the thin, project-owned abstraction over an LLM
  chat API.

  Implementations (OpenAI, Anthropic) live in sibling namespaces and are
  swapped behind this protocol; nothing above this layer knows which provider
  is active. This is the only seam through which the runtime reaches an LLM.

  Vocabulary:
    messages — the vector produced by prompt assembly (pa.llm.prompt): an
               ordered sequence of {:role :content} maps, where :role is one
               of :system, :user, :assistant and :content is a string.
    opts     — an optional map of per-call parameters (e.g. :model,
               :temperature). Implementations supply their own defaults.")

(defprotocol LLMProvider
  (invoke [this messages opts]
    "Send `messages` and block until the full response is available.
    Returns a result map (see below).")
  (stream [this messages opts on-delta]
    "Send `messages` and stream the response. Calls `(on-delta text)` once per
    SSE chunk, where `text` is the chunk's text delta — a fragment that may
    contain zero, one, or several tokens (providers do not emit one token per
    event). Returns the result map once the stream completes.

    Runs synchronously on the calling thread — background execution and
    concurrency are the caller's concern (see the :llm/invoke effect)."))

;; ---------------------------------------------------------------------------
;; Result shape
;;
;; Both invoke and stream return a result map:
;;
;;   {:content    <string>     ; the assistant's text (may be "" on a tool call)
;;    :tool-calls [<tool-call>] ; empty unless the model requested tools}
;;
;; A tool-call is provider-neutral:
;;
;;   {:id        <string>   ; provider-assigned call id, echoed back with the result
;;    :name      <keyword>  ; registry tool name, e.g. :fs/read-file
;;    :arguments <map>}     ; parsed argument map for the tool
;;
;; on-delta only ever fires for text content; tool calls are assembled and
;; surfaced via the return value, not the callback.
;; ---------------------------------------------------------------------------

(defn text-result
  "A plain text result with no tool calls."
  [content]
  {:content content :tool-calls []})
