# Validation: Phase 5 — Memory Retrieval

## Definition of done

Phase 5 is merged and complete when the assistant can reference a prior memory record in its response without being told to, driven entirely by the retrieval pipeline. Specifically: `memory.md` content is always present in the system message; the `:memories` coeffect injects the top-N scored records for every `:user/message` turn; the FTS5 index is queryable and the decay scoring function ranks fresher, more-relevant records above stale or off-topic ones; all six test categories pass; and a REPL smoke test confirms end-to-end retrieval in a running system.

## Checklist

### Tests

- [ ] Unit tests for `by-keyword` FTS: insert fixture memory records into an in-memory SQLite db, assert FTS5 query returns correct records for matching terms and excludes non-matching ones
- [ ] Unit tests for relevance decay function: assert a record 60 days old scores lower than an identical record 1 day old; assert `match_score` 1.0 vs 0.5 produces expected ordering
- [ ] Unit tests for combined retrieval: fixture records with varying age and keyword match → assert top-N ordering reflects decay scoring (keyword + recent beats keyword + old beats recency-only + recent)
- [ ] Test `:memories` coeffect injection: dispatch a `:user/message` event with a query term matching a fixture record → assert `:memories` in the coeffect map is non-empty before the handler runs; dispatch a non-`:user/message` event → assert `:memories` is absent or empty
- [ ] Test `MEMORY.md` loader: fixture `memory.md` file with content → assert its content appears in the assembled system message; fixture with empty content section → assert no crash and content section is blank
- [ ] Schema migration tests: `init!` creates both `memories` and `memories_fts`; `index!` writes to both tables; `rebuild-memory-index!` drops and recreates both; `by-keyword` query succeeds after rebuild

### Behaviors

- [ ] First-startup bootstrap creates `assistant-data/memory/memory.md` with a header and no content
- [ ] `pa.storage.identity/load-all` returns a map including `:memory/md` key with file content (empty string for blank file)
- [ ] Assembled system prompt includes a `memory.md` section on every turn (verify via log or tap> trace)
- [ ] A `:user/message` event dispatched while a matching memory record exists in SQLite → `:memories` coeffect is non-empty when the handler runs
- [ ] A `:user/message` event with no matching records → `:memories` coeffect is an empty vector (no crash)
- [ ] `rebuild-memory-index!` called after SQLite deletion → rebuilds both `memories` and `memories_fts` from Markdown source without error

### Integration

- [ ] REPL smoke test: seed one memory record via `pa.memory.indexer/index!`, send a `:user/message` about the same topic, tap> inspect the coeffect map — confirm `:memories` is non-empty; inspect the assembled prompt — confirm the memory snippet is present
- [ ] Prompt assembly regression: existing `identity.md`, `user.md`, `agents.md` sections still present and unaffected by `memory.md` injection
- [ ] Existing retrieval functions (`recent`, `by-type`, `by-tags`) still pass their prior tests after `created_at` migration and FTS5 addition
- [ ] `pa.db.memory/index!` with a record that has no prior FTS entry creates a new FTS row; a second call with the same id updates (not duplicates) it

## Merge criteria

All of the following must be true before this branch is merged:

1. All six test categories in the checklist above pass with no skips
2. REPL smoke test passes: seed → query → assert `:memories` non-empty → assert snippet in prompt
3. No regression in existing retrieval tests (`recent`, `by-type`, `by-tags`) after schema migration
4. `rebuild-memory-index!` successfully drops and recreates both tables from a clean state
5. `memory.md` injection is unconditional and visible in tap> prompt traces
6. No write paths introduced: no new `:memory/write` effects, no extraction code, no `memory.md` update logic
