# Requirements: Phase 5 — Memory Retrieval

## Goal

Make the assistant context-aware by wiring retrieved memory into every `:user/message` turn. This phase focuses exclusively on the retrieval path — reading existing memories and surfacing them at the right moment. It establishes the `MEMORY.md` always-loaded tier as a distinct higher-priority memory pool, upgrades the SQLite schema with FTS5 full-text search, implements relevance-decay scoring, and injects the top-N retrieved records into the prompt via a new `:memories` coeffect. Memory writing, extraction, and consolidation remain deferred to Phase 6.

## Scope

### In scope

- Add `assistant-data/memory/memory.md` template to first-startup bootstrap (header only, no starter content)
- Extend `pa.storage.identity/load-all` to parse and return `memory.md` alongside the three identity files
- Update `pa.llm.prompt/assemble` to inject `memory.md` content as a mandatory system message section (always present, never scored out)
- Migrate `created_at` column from TEXT to INTEGER (Unix epoch milliseconds) in the `memories` table
- Add FTS5 virtual table (`memories_fts`) to `pa.db.schema/init!`
- Update `pa.db.memory/index!` to upsert into `memories_fts` alongside `memories`
- Update `pa.memory.indexer/rebuild-memory-index!` to drop and recreate both `memories` and `memories_fts`
- Implement `by-keyword` retrieval using FTS5 (replaces any prior keyword query; FTS5 is the sole keyword path)
- Implement relevance decay scoring: `score = match_score * exp(-λ * age_days)`, λ tuned to a 30-day half-life
- Implement combined retrieval: union of recency + keyword result sets → decay scoring → deduplicate by id → top-N
- Define retrieval query spec: `{:query/text, :query/types, :query/limit}`
- Add `:memories` coeffect injector in `pa.runtime.coeffects` (runs for `:user/message` events only, not globally)
- Update `assemble-for` in `pa.runtime.handlers` to read `:memories` from the coeffect map

### Out of scope

- Memory write paths (`:memory/write` effect, daily writer updates)
- End-of-session memory extraction (Phase 6)
- `memory.md` content authoring or curation tooling
- Decay λ tuning beyond the 30-day default constant
- Embeddings / semantic similarity retrieval (Phase 9)
- Any changes to memory write or indexing initiated by LLM output
- Promotion of ephemeral memories to `memory.md`

## Design decisions

1. **`:memories` coeffect is per-handler, not global.** The injector is registered only for `:user/message` events. Non-user events get an empty `:memories` vector, not a SQLite read on every dispatch — keeps retrieval bounded and predictable.

2. **FTS5 replaces `by-keyword` as the sole keyword retrieval path.** The existing `by-keyword` query (if any) is superseded. `pa.db.memory/by-keyword` is implemented as a FTS5 query; there is no separate non-FTS keyword path.

3. **`memory.md` is header-only at bootstrap.** No starter content; the file is a blank canvas for the user or Phase 6 background cognition to populate. The loader must handle an empty content section gracefully.

4. **`memory.md` content is injected unconditionally.** Unlike retrieved episodic memories, `memory.md` is never gated by decay scoring. It is part of the system message on every turn, alongside `identity.md` and `user.md`.

5. **`created_at` migration is safe.** The `memories` table exists but holds only smoke-test data from Phase 2. A destructive migration (drop + recreate, or ALTER with conversion) is acceptable — no production data at risk.

6. **Decay λ is a config constant.** `λ = ln(2) / 30` (30-day half-life) is hardcoded in config at this phase. Tuning against real usage is a Phase 8 concern.

7. **`match_score` is binary.** 1.0 for any FTS5 keyword hit, 0.5 for recency-only records with no keyword match. No sub-ranking within FTS results at this phase.

## Context

Phases 0–4e established a working event runtime, persistent storage, LLM integration, tool execution, and terminal UX. The prompt assembly pipeline (`pa.llm.prompt/assemble`) currently hardcodes `[]` for memories. The SQLite `memories` table exists from Phase 2 but lacks FTS support and uses TEXT for `created_at`. Phase 5 is the first phase where the assistant can reference prior knowledge without being told to — it is the retrieval half of a read/write memory loop whose write half (extraction) arrives in Phase 6.

The architectural constraint from `mission.md` and `tech-stack.md` applies: SQLite is a rebuildable retrieval accelerator, not canonical storage. `memory.md` and `memory/daily/` Markdown files remain canonical; `memories_fts` must be fully rebuildable from them via `rebuild-memory-index!`.
