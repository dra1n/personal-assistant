# Plan: Phase 2 — Persistent Storage & Memory Foundation

## Task groups

### Group A — Directory layout & bootstrap

- [ ] Create `~/.config/personal-assistant/` directory structure:

  ```text
  ~/.config/personal-assistant/
    identity/
      soul.md
      identity.md
      user.md
      agents.md

    memory/
      daily/
      semantic/
      episodic/
      summaries/

    cognition/
      reflections/
      plans/

    tasks/
      active/
      completed/

    events/
      events.edn

    system/
      heartbeat.md
      tools.md

    sqlite/
      assistant.db
  ```

- [ ] Implement first-startup detection: if `~/.config/personal-assistant/` does not exist, run bootstrap sequence
- [ ] Generate default identity template files: `soul.md`, `identity.md`, `user.md`, `agents.md` with minimal structured stubs
- [ ] Create empty `~/.config/personal-assistant/events/events.edn` on first startup
- [ ] Wire bootstrap as an Integrant component (`pa.storage.fs`) that runs at system start
- [ ] Write fresh-boot test: point `PA_HOME` at a temp directory, start system, assert directory structure and template files created correctly

### Group B — Event persistence & replay

- [ ] Implement event log writer (`pa.storage.events`): append serialized EDN event to `~/.config/personal-assistant/events/events.edn`

  Events are immutable EDN maps with `:event/type` and payload as top-level keys, e.g.:
  ```clojure
  {:event/type :user/message :text "hello"}
  {:event/type :scheduler/tick}
  {:event/type :memory/stored}
  ```

- [ ] Implement event log reader: load and parse all events from `events.edn` → sequence of event maps
- [ ] Extend Phase 1 replay function to load events from disk (via `pa.storage.events`) rather than from in-memory sequence

  Replay flow:
  ```text
  events.edn
     -> load events
     -> dispatch through runtime
     -> reconstruct state
  ```

- [ ] Wire `:event/store` effect (defined in Phase 1) to the event log writer — this is the only path for persisting events
- [ ] Wire `pa.storage.events` as an Integrant component
- [ ] Write replay test: write fixture events to `events.edn`, replay via runtime, assert reconstructed state matches expected state

### Group C — Identity loading

- [ ] Define structured format for identity files: YAML front-matter block for machine-parseable fields, Markdown prose below
- [ ] Implement `pa.storage.identity/load-file`: parse a single identity Markdown file → normalized EDN map
- [ ] Implement `pa.storage.identity/load-all`: load `soul.md`, `identity.md`, `user.md`, `agents.md` → merged identity context map
- [ ] Inject identity context into runtime startup state (add `:identity` key to initial db map at system start)
- [ ] Write identity loader tests: fixture identity Markdown files → assert normalized EDN structure matches expected output

### Group D — Memory domain model & Markdown persistence

- [ ] Define memory record schema:

  ```clojure
  {:memory/id       ...   ; UUID
   :memory/type     ...   ; e.g. :episodic, :semantic, :fact
   :memory/path     ...   ; path to canonical Markdown file
   :memory/title    ...
   :memory/summary  ...
   :memory/tags     ...
   :memory/created-at ...}
  ```

  Markdown file stores canonical semantic content; SQLite stores retrieval/index metadata only.

- [ ] Implement `pa.memory.records/make`: constructor that generates `:memory/id` (UUID) and `:memory/created-at` (timestamp)
- [ ] Implement `pa.storage.memory/write-daily`: serialize memory record to `~/.config/personal-assistant/memory/daily/YYYY-MM-DD.md` (append semantics — multiple records per file)
- [ ] Implement `pa.storage.memory/read-daily`: parse a daily memory file → sequence of memory record maps
- [ ] Implement `pa.storage.memory/read-all-daily`: scan `memory/daily/` directory → all memory records across all daily files
- [ ] Emit `:event/memory-stored` after successful Markdown write

  Memory persistence lifecycle:
  ```text
  memory record created
      ↓
  Markdown writer persists canonical artifact
      ↓
  :event/memory-stored emitted
      ↓
  SQLite indexer updates retrieval metadata
  ```

- [ ] Write memory round-trip test: create memory record → write to Markdown → read back → assert semantic equivalence

### Group E — SQLite schema, sync, queries & Integrant wiring

- [ ] Add SQLite dependency to `deps.edn` (`next.jdbc` + SQLite JDBC driver)
- [ ] Implement `pa.db.schema/init!`: create `memories` table if not exists

  Initial schema (intentionally minimal — no full cognition payloads, no premature normalization):
  ```text
  id
  path
  type
  title
  summary
  tags
  created_at
  ```

- [ ] Wire `pa.db.sqlite` as an Integrant component: initializes schema on start
- [ ] Implement `pa.db.memory/index!`: insert or update memory record metadata in SQLite from a memory record map
- [ ] Implement `pa.memory.indexer` component: subscribes to `:event/memory-stored` → calls `pa.db.memory/index!`
- [ ] Implement `pa.db.memory/recent`: query N most recent memory records
- [ ] Implement `pa.db.memory/by-type`: filter records by `:memory/type`
- [ ] Implement `pa.db.memory/by-tags`: filter records by tag intersection
- [ ] Implement `(rebuild-memory-index!)` in `pa.memory.indexer`: clear SQLite memories table → scan `pa.storage.memory/read-all-daily` → re-index all records
- [ ] Wire `pa.memory.store` as an Integrant component: coordinates write → emit event → SQLite index lifecycle

  Full Integrant component set introduced in this phase:
  ```clojure
  :storage/fs
  :storage/events
  :db/sqlite
  :memory/store
  :memory/indexer
  ```

- [ ] Write SQLite integration tests: memory record → SQLite write → query → assert returned metadata matches
- [ ] Write rebuild test: populate SQLite, delete and recreate schema, call `rebuild-memory-index!`, assert records restored correctly
- [ ] REPL smoke test: confirm full cycle — identity loads, replay reconstructs state, memory write round-trips, SQLite rebuilds from scratch after deletion

## Notes

- Groups A → B → C → D → E must be worked in order. Each group depends on the previous: bootstrap (A) must exist before event persistence (B) can write to disk; identity loading (C) requires the filesystem structure from A; memory domain (D) requires identity context and the storage namespace split from C; SQLite wiring (E) requires the memory records and Markdown persistence from D.
- The namespace split is introduced progressively across groups B, C, D, and E — not all at once in Group A:
  ```text
  pa.runtime.*  -> orchestration/event/effect pipeline
  pa.storage.*  -> filesystem persistence
  pa.db.*       -> SQLite indexing/query layer
  pa.memory.*   -> semantic memory domain
  ```
  Dependency direction is one-way: `runtime -> storage/db/memory`. Persistence layers must not depend on runtime orchestration.
- The `:event/store` effect was defined in Phase 1 but left unimplemented. Group B implements its effect executor.
- SQLite must not be required for the replay path (Group B). Keep the event log reader and runtime replay entirely within `pa.storage.events` — no SQLite calls in the replay pipeline.
- The `pa.memory.indexer` in Group E listens for `:event/memory-stored` and handles its own SQLite writes — it must not be called directly from the Markdown writer to preserve the event-driven synchronization boundary.
