# Plan: Phase 5 — Memory Retrieval

## Task groups

### Group 1 — MEMORY.md tier

- [x] Add `assistant-data/memory/memory.md` to first-startup bootstrap template generation (header-only: `# Memory\n\n`)
- [x] Extend `pa.storage.identity/load-all` to read and return `memory.md` content alongside `identity.md`, `user.md`, `agents.md`; handle empty content section gracefully (return empty string, not nil)
- [x] Update `pa.llm.prompt/assemble` to inject `memory.md` content as an always-present system message section (unconditional — never gated by retrieval scoring)

### Group 2 — Schema migrations

- [x] Migrate `created_at` column from TEXT to INTEGER (Unix epoch ms) in `memories` table; update `record->row` and `row->record` in `pa.db.memory`
- [x] Add FTS5 virtual table in `pa.db.schema/init!`: `CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(id UNINDEXED, title, summary, tags)`
- [x] Update `pa.db.memory/index!` to upsert into `memories_fts` alongside `memories` (use `INSERT OR REPLACE`)
- [x] Update `pa.memory.indexer/rebuild-memory-index!` to drop and recreate both `memories` and `memories_fts`

### Group 3 — Retrieval functions

- [ ] Define retrieval query spec: `{:query/text <string>, :query/types <keyword-set, optional>, :query/limit <int>}`
- [ ] Implement `pa.db.memory/by-keyword`: FTS5 full-text search over `title`, `summary`, `tags`; returns records ordered by FTS rank
- [ ] Implement relevance decay scoring function: `score = match_score * exp(-λ * age_days)`; `match_score` 1.0 for keyword hits, 0.5 for recency-only; λ = `(/ (Math/log 2) 30)` (30-day half-life) as a config constant
- [ ] Implement `pa.db.memory/retrieve`: union of recency + `by-keyword` result sets → apply decay scoring → deduplicate by id → return top-N

### Group 4 — `:memories` coeffect + prompt wiring

- [x] Add `:memories` coeffect injector to `pa.runtime.coeffects`: reads `:content` from the triggering `:user/message` event as query text, calls `pa.db.memory/retrieve`, injects result as `{:memories [...]}` into the coeffect map
- [x] Register the `:memories` injector for `:user/message` handlers only (per-handler interceptor, not global)
- [x] Update `assemble-for` in `pa.runtime.handlers` to read `:memories` from the coeffect map instead of hardcoding `[]`
- [x] REPL verification: seed one memory record, send a message on the same topic, confirm `:memories` in coeffect map is non-empty and the assembled prompt includes the snippet

## Notes

**Hard sequencing constraint:** Group 2 (schema migrations) must be complete before Group 3 (retrieval functions) can be implemented or meaningfully tested — `by-keyword` requires FTS5 to exist. Groups 1 and 2 can proceed in parallel. Group 4 depends on Group 3 being functional (needs `pa.db.memory/retrieve`).

**Migration note:** The `created_at` change is a breaking schema change. Since the table holds only smoke-test data, a drop-and-recreate approach is fine. The `rebuild-memory-index!` update in Group 2 should make this a one-call recovery path.

**Config constant placement:** λ should live in the system config map (or a named `const` in `pa.db.memory`) — not a magic literal inside the scoring function — so Phase 8 tuning is a one-line change.
