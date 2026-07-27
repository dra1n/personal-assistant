# Ideas Backlog

Unordered ideas that don't belong in the phase sequence in [roadmap.md](roadmap.md).
Nothing here is committed to a phase number, so items can be added at the end
of this file at any time without renumbering or reshuffling the roadmap. When
one of these is ready to be worked on, promote it into its own phase (or a
`/feature-spec`) in the roadmap.

---

## Optional Advanced Features

These are explicitly deferred and not required for a complete system.

- [ ] Local model support (Ollama or similar)
- [ ] Voice input / output
- [ ] Web UI (optional complement to terminal)
- [ ] Mobile interface
- [ ] Graph-based memory (entities + relationships)
- [ ] Semantic planning (multi-step goal decomposition)
- [ ] Multi-agent experimentation
- [ ] Autonomous task execution (self-initiated without user trigger)

## Semantic memory retrieval (embeddings)

Embeddings add meaningful retrieval quality but bring significant complexity:
a new `embed` method on the provider protocol, an OpenAI embeddings API
dependency separate from the chat API, a BLOB column on the `memories` table,
and Clojure-side cosine similarity over all stored embeddings (a full table
scan per LLM call — acceptable at personal scale, but worth noting). Deferred
until recency + FTS retrieval proves insufficient in practice.

- [ ] Add `embed` method to the LLM provider protocol; implement for OpenAI (`text-embedding-3-small`); Anthropic stub returns nil (no embeddings API)
- [ ] Add `embedding` BLOB column to the `memories` table; generate and store embedding on every `index!` call
- [ ] Implement semantic retrieval: load all embeddings from SQLite, compute cosine similarity against the query embedding, apply decay scoring, return top-N
- [ ] Extend combined retrieval to merge recency + keyword + semantic result sets
- [ ] Write embedding round-trip test: generate embedding, store, retrieve by cosine similarity with a semantically related query
