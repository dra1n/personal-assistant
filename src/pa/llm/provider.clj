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
    Returns the complete assistant response text as a string.")
  (stream [this messages opts on-delta]
    "Send `messages` and stream the response. Calls `(on-delta text)` once per
    SSE chunk, where `text` is the chunk's text delta — a fragment that may
    contain zero, one, or several tokens (providers do not emit one token per
    event). Returns the full accumulated response text as a string once the
    stream completes.

    Runs synchronously on the calling thread — background execution and
    concurrency are the caller's concern (see the :llm/invoke effect)."))
