# Requirements: Phase 6 — Scheduling & Background Cognition

## Goal

Introduce time-based behavior and background cognition to the assistant. The
scheduler is an Integrant component that fires overdue jobs on startup, runs a
ticker loop during the session, and flushes end-of-session jobs on shutdown.
Memory extraction runs as a background job triggered by a session turn threshold,
classifying conversation items as ephemeral (daily notes) or permanent (memory.md).
The scheduler is built first; memory extraction is built second.

## Scope

### In scope

- Bootstrap prerequisites: `tasks/scheduled/` directory, `HEARTBEAT.md` system
  template, SQLite WAL mode
- Scheduled task schema and EDN persistence in `tasks/scheduled/`
- Scheduler as an Integrant component with startup catch-up, in-session ticker,
  and `ig/halt-key!` shutdown flush
- Reminder task type: emit `:reminder/due` at scheduled time, visible in the
  terminal UI
- Periodic reflection job: summarize recent memory into `cognition/reflections/`
- Memory consolidation job: merge daily memory files into longer-term summaries
- `HEARTBEAT.md` as a static checklist loaded and executed by the scheduler on
  startup
- Moving completed tasks from `tasks/scheduled/` to `tasks/completed/`
- Exposing scheduler state via Portal (`tap>`)
- `memory.md` wisdom writer: merge-and-deduplicate curated facts back into
  `memory.md` (new namespace, separate from the append-only daily writer)
- Session turn counter and `:session/threshold-reached` event
- End-of-session memory extraction job triggered by the turn threshold

### Out of scope

- Background daemon / separate process (deferred — migration path documented in
  roadmap)
- IPC between a daemon and the interactive session
- LLM-interpreted `HEARTBEAT.md` (static checklist only for now)
- Embeddings-based memory retrieval (Phase 9)
- Personality and user model evolution (Phase 8)
- Summarization of old episodic memories into `memory/semantic/` (Phase 8)

## Design decisions

1. **Scheduler model: interval/fixed-time, not full cron.** No cron expression
   library dependency. Tasks specify either a fixed wall-clock time or a repeat
   interval in milliseconds. Sufficient for reminders and periodic jobs; keeps
   the dependency footprint minimal (consistent with `tech-stack.md` values).

2. **HEARTBEAT.md is a static checklist.** The scheduler reads and executes it
   as a data file on startup — no LLM interpretation. LLM-driven HEARTBEAT
   behaviour is a Phase 8 concern.

3. **Scheduler lifecycle via Integrant.** `ig/init-key :scheduler` fires overdue
   tasks and starts the ticker loop. `ig/halt-key! :scheduler` stops the loop and
   triggers end-of-session flush. No custom session-lifecycle abstraction needed —
   Integrant's own lifecycle is the contract.

4. **SQLite WAL mode from Phase 6 onwards.** `PRAGMA journal_mode=WAL` added to
   `pa.db.schema/init!`. No downside at single-process scale; required for any
   future daemon concurrency without a schema change.

5. **`memory.md` wisdom writer is a new namespace** (`pa.storage.memory-wisdom`
   or similar), separate from `pa.storage.memory` (the append-only daily writer).
   `pa.storage.identity/load-all` already reads `memory.md`; this phase adds the
   write path.

6. **Session turn counter derived from runtime state.** `(count (:conversation db))`
   is the source of truth. A `:session/threshold-reached` event is emitted each
   time the count hits a multiple of N (default N=10). No new atom or counter
   needed.

7. **Extraction is fire-and-forget.** The extraction job dispatches via `:dispatch`
   from the threshold handler and does not block user input or shutdown. If the
   app closes mid-extraction, the job is silently abandoned; it will re-trigger
   on the next session that reaches the threshold.

8. **Scheduler first, extraction second.** Scheduler infrastructure (component,
   ticker, reminders, jobs) is built and validated before touching the memory
   extraction pipeline.

## Context

Phases 1–5 established the full effect system (`:dispatch`, `:dispatch-later`,
`:memory/write`, `:memory/index`), a working Integrant component tree, SQLite
memory indexing, and `memory.md` loading. The `tasks/active` and `tasks/completed`
directories exist in bootstrap but `tasks/scheduled/` does not yet. The `:tasks {}`
map is in runtime state with `put-task` / `remove-task` transitions already defined.
The `ig/halt-key!` shutdown path exists for all current components and closes
cleanly — the new scheduler halt must not block it.
