# Requirements: Phase 2 — Persistent Storage & Memory Foundation

## Goal

Establish a durable, inspectable, recoverable storage architecture. This phase defines canonical memory ownership, operational persistence, replay foundations, indexing/query infrastructure, startup/bootstrap semantics, and synchronization boundaries. The system should clearly distinguish between semantic memory (Markdown), operational runtime persistence (append-only EDN event log), and query/index infrastructure (SQLite).

## Scope

### In scope

- `~/.config/personal-assistant/` directory layout and first-startup bootstrap
- Identity template generation (`identity.md`, `user.md`, `agents.md`) <!-- soul.md was originally generated too; retired and merged into identity.md in Phase 3 (Group F) -->

- Append-only EDN event log (`~/.config/personal-assistant/events/events.edn`)
- Replay pipeline: load events from disk → dispatch through runtime → reconstruct state
- Identity loader: parse structured Markdown (YAML front-matter or EDN sections) → normalized EDN maps → injected into startup state
- Memory domain model: define `{:memory/id :memory/type :memory/path :memory/title :memory/summary :memory/tags :memory/created-at}`
- Markdown memory writer: serialize memory records to `~/.config/personal-assistant/memory/daily/YYYY-MM-DD.md` (daily/ is the only target in this phase)
- Markdown memory reader: parse daily files → reconstruct memory records
- SQLite schema: minimal `memories` table (id, path, type, title, summary, tags, created_at)
- SQLite index synchronization: event-driven write after Markdown persistence (`:event/memory-stored` → SQLite indexer)
- Basic retrieval queries: recent N records, filter by type, filter by tags
- Integrant components: `:storage/fs`, `:storage/events`, `:db/sqlite`, `:memory/store`, `:memory/indexer`
- `(rebuild-memory-index!)` function: scan Markdown files → rebuild SQLite indexes
- Namespace split: `pa.storage.*`, `pa.db.*`, `pa.memory.*` introduced in this phase
- All tests: replay, memory round-trip, SQLite integration, identity loader
- Fresh-boot check: system initializes cleanly with no pre-existing `~/.config/personal-assistant/`
- CLI/REPL smoke test covering full write → persist → query → rebuild cycle

### Out of scope

- Writing to `memory/semantic/` or `memory/episodic/` subdirectories (deferred to later phases)
- Sophisticated semantic retrieval, embeddings, cosine similarity (Phase 5)
- Memory decay / relevance scoring (Phase 5)
- Memory extraction from LLM responses (Phase 5)
- Scheduled task persistence to `tasks/scheduled/` (Phase 6)
- Cognition pipeline stages (Phase 7)
- Personality evolution, user model evolution (Phase 8)
- Any LLM invocation or AI behavior

## Design decisions

1. **Namespace split committed in Phase 2.** `pa.storage.*` handles filesystem persistence, `pa.db.*` handles SQLite indexing/queries, `pa.memory.*` handles the semantic memory domain. Dependency direction is one-way: `pa.runtime.*` → `pa.storage.*` / `pa.db.*` / `pa.memory.*`. Persistence layers must not depend on runtime orchestration.

2. **Markdown is canonical for semantic content; SQLite is rebuildable infrastructure.** The system must tolerate SQLite deletion without semantic memory loss. `rebuild-memory-index!` must work from Markdown alone.

3. **Identity files use structured format.** Identity Markdown files (`identity.md`, `user.md`, `agents.md`) use YAML front-matter or EDN sections for machine-parseable fields alongside prose content. This enables the identity loader to produce normalized EDN maps without fragile freeform prose parsing. _(Originally also included `soul.md`; it was retired and its `name`/`traits`/`communication-style`/`values` schema merged into `identity.md` in Phase 3, Group F.)_

4. **Data root is `~/.config/personal-assistant/`.** The runtime resolves this path at startup (respecting a `PA_HOME` env var override for testing). No `assistant-data/` directory in the project tree.

5. **Markdown writer targets `daily/` only in Phase 2.** `semantic/` and `episodic/` subdirectories exist in the layout but are not written to until later phases introduce the cognition pipeline.

6. **Memory synchronization is event-driven.** The flow is: Markdown writer persists → emits `:event/memory-stored` → SQLite indexer updates index. This is asynchronous-friendly and rebuildable.

7. **SQLite schema is intentionally minimal.** Initial `memories` table: id, path, type, title, summary, tags, created_at. No full cognition payloads, no premature normalization.

8. **Event log is SQLite-independent.** Replay must reconstruct runtime state from `events.edn` alone, without touching SQLite.

9. **Storage tests must use real SQLite.** Per `spec/tech-stack.md` testing philosophy: no mocking the database in integration tests. Storage round-trips hit the real filesystem and real SQLite.

## Context

Phase 1 established the event-driven runtime pipeline (dispatch → coeffect injection → handler → effects → execution) and a working in-memory replay function. Phase 2 grounds that architecture in durable storage. The replay function built in Phase 1 is extended to load events from disk rather than from an in-memory sequence. The Phase 1 event bus and interceptor chain remain intact; Phase 2 adds persistence as a layer beneath them, not a replacement.

The namespace refactor proposed in the Phase 1 roadmap note is committed here: `pa.runtime.*` remains the orchestration layer; `pa.storage.*`, `pa.db.*`, and `pa.memory.*` are introduced as the persistence substrate.
