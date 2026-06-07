# Plan: Phase 6 — Scheduling & Background Cognition

## Task groups

### Group 1 — Bootstrap prerequisites

- [ ] Add `tasks/scheduled/` to `pa.storage.fs/create-dirs!`
- [ ] Add `HEARTBEAT.md` to `system-template-files` in `pa.storage.fs` and write
      a starter template at `resources/templates/system/heartbeat.md`
- [ ] Enable SQLite WAL mode: add `PRAGMA journal_mode=WAL` to `pa.db.schema/init!`

### Group 2 — Scheduler component

- [ ] Define scheduled task schema:
      `{:task/id, :task/type, :task/payload, :task/fire-at, :task/interval-ms}`
      (`:task/fire-at` is a Unix epoch ms timestamp; `:task/interval-ms` is set
      for repeating tasks, nil for one-shots)
- [ ] Implement task EDN persistence: write/read tasks to/from `tasks/scheduled/`
      as individual EDN files (one file per task, named by `:task/id`)
- [ ] Implement scheduler as an Integrant component (`:scheduler`): on
      `ig/init-key` load all tasks from `tasks/scheduled/`, fire any whose
      `:task/fire-at` has already passed (catch-up), then start an in-session
      ticker loop (core.async `go-loop` + `timeout`)
- [ ] Wire `:scheduler` into `pa.config/system-config` and into the
      `:pa.runtime/dispatcher` context so effect executors can schedule/cancel tasks
- [ ] Implement `ig/halt-key! :scheduler`: stop the ticker loop cleanly (close
      the control channel); halt must not block shutdown
- [ ] Implement `HEARTBEAT.md` loader: parse the static checklist from
      `system/heartbeat.md` into a sequence of job descriptors; execute on startup
      catch-up pass
- [ ] Implement reminder task type: when a reminder task fires, dispatch
      `:reminder/due` with the payload; the handler emits a visible UI notification
      and moves the task EDN to `tasks/completed/`
- [ ] Implement periodic reflection job: on fire, run a summarization prompt over
      recent `cognition/` content and write output to `cognition/reflections/`
- [ ] Implement memory consolidation job: on fire, merge daily memory files older
      than a configurable age threshold into a summary entry
- [ ] Move completed/fired one-shot tasks from `tasks/scheduled/` to
      `tasks/completed/`; update `:task/fire-at` for repeating tasks and rewrite
      the EDN file
- [ ] Expose scheduler state (loaded tasks, last-fired timestamps) via `tap>` after
      each tick

### Group 2a — Reminder creation

- [ ] Implement `:reminder/create` handler: receives `{:text "..." :fire-at <epoch-ms>}`
      from the LLM tool call, emits `:task/schedule` effect with `{:type :reminder
      :payload {:text "..."} :fire-at <epoch-ms>}`; returns a confirmation tap
- [ ] Register a `set-reminder` tool in the tool registry so the LLM can invoke it
      with natural-language time already resolved to epoch ms by the assistant
- [ ] Write tests:
      - `:reminder/create` handler: assert `:task/schedule` effect is emitted with
        correct type, payload, and fire-at
      - Confirm no task is written when fire-at is missing or in the past

### Group 3 — Memory extraction

- [ ] Implement `memory.md` wisdom writer (e.g. `pa.storage.memory-wisdom`): read
      current `memory.md`, merge in new permanent facts as bullet items, deduplicate
      against existing content, write back — distinct from the append-only daily
      writer in `pa.storage.memory`
- [ ] Add session turn counter: in the `:user/message` handler, derive turn count
      from `(count (:conversation db'))` after the state update and emit
      `:session/threshold-reached` when count is a positive multiple of N (config,
      default 10)
- [ ] Implement `:session/threshold-reached` handler: dispatch the extraction job
      as a fire-and-forget `:dispatch` — does not block user input
- [ ] Implement extraction job handler: call LLM with a classification prompt over
      the last N turns; route ephemeral items to `:memory/write` effect (daily
      notes) and permanent items to the wisdom writer
- [ ] Write tests — scheduler:
      - Mock clock: assert tasks whose `:task/fire-at` is in the past fire
        immediately on `ig/init-key` (catch-up)
      - Ticker: assert a task fires at correct wall-clock time with a mocked
        timer channel
      - Reminder task: fixture task EDN → assert `:reminder/due` event dispatched
        and file moved to `tasks/completed/`
      - Reflection and consolidation jobs with fixture memory data
- [ ] Write tests — memory extraction:
      - `memory.md` wisdom writer: fixture current content + new items → assert
        output contains new items, deduplicates exact matches, preserves unrelated
        content
      - Session turn counter: dispatch N `:user/message` events → assert
        `:session/threshold-reached` fired exactly once at turn N
      - Extraction job: fixture conversation of N turns → assert ephemeral items
        produce `:memory/write` effects and permanent items are passed to the
        wisdom writer

## Notes

Groups are sequentially dependent: Group 2 requires Group 1's bootstrap items
(especially `tasks/scheduled/` and WAL mode). Group 3 requires Group 2's
scheduler component to be wired (the halt flush triggers the final extraction).

Within Group 2, the natural order is: schema → persistence → component wiring →
halt-key! → HEARTBEAT loader → job types → task lifecycle → Portal exposure.
Implement and test the component skeleton before adding job types.

Within Group 3, implement and test the wisdom writer in isolation first — it has
no dependency on the scheduler. Then add the session turn counter and extraction
handler.

The `ig/halt-key! :scheduler` halt must close the ticker channel non-blockingly.
If an in-flight job is running when halt fires, it should be allowed to finish
(or abandoned with a log warning) — it must not hold up process exit.
