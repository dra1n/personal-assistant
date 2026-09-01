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

## Prompt caching: stabilize the cached prefix

Providers cache on a common prefix, so anything that varies early in the
message list makes everything after it uncacheable. Three problems, found while
building Phase 9's attached resources (2026-09-01). The first two are
pre-existing; the third is the one already fixed, recorded here for context.

**1. Retrieved memories sit in the system message and change every turn.**
`memories-interceptor` queries with each user message's own text, so the
snippets differ turn to turn. Because they are rendered into the system message
— the very first thing sent — the entire conversation after it is uncacheable
on every turn. A twenty-turn conversation re-pays full price for all twenty
turns each time.

- [ ] Move retrieved memories out of the system message to just before the
      latest user turn (or into that turn), so the stable prefix covers
      identity + the static note + every prior turn. Verify answer quality
      does not regress: the memories are currently framed as system-level
      context, and moving them changes how strongly the model weighs them
- [ ] Decide whether `render-memories`' framing needs to change once it is no
      longer part of the system message

**2. Memories are dropped on follow-up hops within a turn.** Of the four
`:llm/invoke` sites in `pa.runtime.handlers`, `:user/message` and
`:mcp/mentions-resolved` pass the retrieved snippets, while the `:tool/result`
follow-up and `:mcp/prompt-resolved` pass `[]`. So in a tool-calling turn the
model has memory context when it decides to call a tool and loses it when
interpreting the result and writing the answer — the reply is composed without
the context that prompted going to get it. It also means the system message
changes *between hops of one turn*, so a multi-hop turn cannot reuse its own
prefix.

- [ ] Retrieve once per turn and store the snippets in db, so every hop
      assembles from the same value. This is a prerequisite for (1): the
      snippets need a stable per-turn home before they can be repositioned
- [ ] Clear them on `:conversation/clear` and replace them on the next
      `:user/message`
- [ ] Test that a tool-calling turn's follow-up carries the same system message
      as its first hop

**3. Done — the attachment note is unconditional.** It was added only when a
turn carried attachments, which rewrote the system message mid-conversation the
first time anyone `@`-mentioned a resource. It is now always present, and
`system-content` orders sections most stable first (identity → static note →
memories). Kept here as the reason that ordering exists, so it is not
"tidied up" later.
