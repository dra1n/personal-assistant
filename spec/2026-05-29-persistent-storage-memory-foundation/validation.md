# Validation: Phase 2 — Persistent Storage & Memory Foundation

## Definition of done

Phase 2 is complete when the system boots cleanly from scratch (no pre-existing `~/.config/personal-assistant/`), bootstraps its own directory layout and identity templates, persists and replays events from disk, loads identity into runtime state, writes and reads Markdown memory records with full round-trip fidelity, indexes memory metadata in SQLite, and can fully rebuild SQLite indexes from Markdown alone after deletion — all covered by passing tests in each category and verified at the REPL.

## Checklist

### Tests

- [ ] Fresh-boot test: `PA_HOME` pointed at a temp directory, system starts, directory structure and template identity files are created correctly
- [ ] Replay test: fixture events written to `events.edn`, replayed through runtime, reconstructed state matches expected state
- [ ] Replay independence test: replay completes successfully with SQLite deleted / unavailable
- [ ] Identity loader test: fixture `identity.md`, `user.md`, `agents.md` → normalized EDN maps match expected structure <!-- soul.md retired in Phase 3 (Group F) -->

- [ ] Memory round-trip test: memory record → Markdown writer → Markdown reader → reconstructed record is semantically equivalent
- [ ] SQLite integration test: memory record → `pa.db.memory/index!` → query (recent, by-type, by-tags) → returned metadata matches original
- [ ] Rebuild test: populate SQLite, delete and reinitialize schema, call `rebuild-memory-index!`, assert all records restored from Markdown

### Behaviors

- [ ] System boots from scratch and `~/.config/personal-assistant/` is fully initialized on first run
- [ ] Identity context (`:identity` key) is present in runtime state after startup
- [ ] A dispatched event is persisted to `events.edn` when `:event/store` effect is emitted
- [ ] Restarting the system and replaying `events.edn` reconstructs the same runtime state
- [ ] Writing a memory record creates a readable entry in `~/.config/personal-assistant/memory/daily/YYYY-MM-DD.md`
- [ ] After Markdown write, `:event/memory-stored` is emitted and SQLite index is updated
- [ ] `(rebuild-memory-index!)` works after deleting `assistant.db` and recreating it from scratch

### Integration

- [ ] Phase 1 interceptor chain and coeffect injection are unaffected by the namespace split and new components
- [ ] Phase 1 in-memory replay function is replaced/extended by the disk-backed replay without breaking existing replay tests
- [ ] Integrant system starts and halts cleanly with all Phase 2 components (`:storage/fs`, `:storage/events`, `:db/sqlite`, `:memory/store`, `:memory/indexer`) present
- [ ] `pa.runtime.*` depends on `pa.storage.*` / `pa.db.*` / `pa.memory.*` — no reverse dependency introduced

## Merge criteria

All of the following must be true before this branch merges:

1. All tests in the checklist above pass
2. System boots cleanly from a fresh state (no `~/.config/personal-assistant/`) without errors or manual intervention
3. Full write → persist → query → rebuild cycle exercised and verified at the REPL
4. No regression in Phase 1 tests (runtime dispatch, coeffect injection, interceptor chain, in-memory replay)
5. Integrant system starts and halts cleanly in development with all Phase 2 components present
6. SQLite is absent from the replay pipeline — `events.edn` replay works standalone
7. Namespace dependency direction enforced: `pa.runtime.*` → storage/db/memory, never the reverse
