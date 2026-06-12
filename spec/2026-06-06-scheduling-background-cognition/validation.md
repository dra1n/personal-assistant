# Validation: Phase 6 — Scheduling & Background Cognition

## Definition of done

Phase 6 is complete when the scheduler fires a reminder visibly in the terminal
UI at a scheduled time, and `memory.md` gains a new bullet after N turns of
conversation — both verified end-to-end in a running session. All unit and
integration tests pass. The system starts and shuts down cleanly with the
scheduler component present, and no existing Phase 1–5 behaviors regress.

## Checklist

### Tests

- [ ] Scheduler catch-up: tasks with past `:task/fire-at` fire immediately on
      `ig/init-key` (mock clock)
- [ ] Scheduler ticker: task fires at correct time with a mocked timer channel
- [ ] Scheduler halt: `ig/halt-key!` closes ticker channel without blocking;
      assert process exit is not delayed
- [ ] Reminder task: fixture EDN → `:reminder/due` dispatched, file moved to
      `tasks/completed/`
- [ ] Reflection job: fixture memory data → reflection written to
      `cognition/reflections/`
- [ ] Consolidation job: fixture daily memory files → summary entry produced
- [ ] `memory.md` wisdom writer: fixture content + new items → merged output
      contains new items, deduplicates exact matches, preserves unrelated content
- [ ] Session turn counter: N `:user/message` events → `:session/threshold-reached`
      fired exactly once at turn N and again at turn 2N
- [ ] Extraction job: fixture conversation of N turns → ephemeral items produce
      `:memory/write` effects; permanent items passed to wisdom writer
- [ ] SQLite WAL mode: `PRAGMA journal_mode` returns `wal` after `init!`
- [ ] Bootstrap: `tasks/scheduled/` created on first startup; `HEARTBEAT.md`
      template written

### Behaviors

- [ ] Start the app with a pre-written reminder task in `tasks/scheduled/` whose
      fire time is in the past → reminder appears in the terminal UI immediately
      at startup (catch-up)
- [ ] Schedule a reminder for 30 seconds in the future → reminder fires in the
      terminal UI at the correct time without requiring user input
- [ ] Send 10 messages in a session → `memory.md` gains at least one new bullet
      after the extraction job completes (may take a few seconds; check file on
      disk)
- [ ] Shut down the app while the scheduler is running → clean exit, no hang,
      no error in logs

### Integration

- [ ] Existing Phase 5 memory retrieval still works after WAL mode change:
      FTS search and combined retrieval return correct results
- [ ] `ig/halt-key!` chain completes cleanly with `:scheduler` in the system —
      no deadlock or timeout; all components halt in dependency order
- [ ] Portal receives scheduler state taps after each tick alongside existing
      runtime state taps
- [ ] `tasks/completed/` receives moved files after reminder fires; no orphaned
      files left in `tasks/scheduled/`
- [ ] Extraction job does not emit duplicate memory entries when triggered twice
      in the same session (threshold fires at turn 10 and turn 20)

## Merge criteria

- All test items in the checklist above are green
- Both end-to-end behaviors are verified live: reminder fires in terminal UI,
  `memory.md` updates after N turns
- System starts and shuts down cleanly with no regressions in existing features
- No direct `swap!` or `reset!` on the db atom introduced (all state transitions
  via `:db` effect, consistent with Phase 1 constraints)
- SQLite WAL mode active and verified
