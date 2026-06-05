# Roadmap

Phases are ordered by dependency — each builds on the last. Phases 0–9 are all broken into concrete micro-steps.

Status legend: `[ ]` not started · `[~]` in progress · `[x]` done

---

## Phase 0 — Foundation & Boilerplate

Goal: Establish development ergonomics and architectural skeleton. No AI functionality.

- [x] Create `deps.edn` with initial dependencies (Integrant, Timbre, core.async, Portal)
- [x] Define namespace layout (`pa.core`, `pa.system`, `pa.runtime`, etc.)
- [x] Wire Integrant system map with placeholder components
- [x] Add Timbre logging with basic console appender
- [x] Set up Portal integration and `tap>` plumbing in dev namespace
- [x] Add `dev/user.clj` with REPL helpers (`start`, `stop`, `reset`)
- [x] Add charm.clj dependency; render a static "hello" terminal frame
- [x] Create a minimal event bus (core.async channel + dispatcher stub)
- [x] Confirm system starts, stops cleanly, and REPL workflow is ergonomic
- [x] Write smoke tests: system starts, all Integrant components initialize and halt without error

---

## Phase 1 — Runtime & State Model

Goal: Build the foundational event-driven cognitive runtime.

This phase establishes:
- runtime orchestration
- deterministic event processing
- effect execution
- coeffect injection
- observability foundations
- replay/debugging capabilities

This is runtime engineering, not AI engineering. No sophisticated cognition yet.

Avoid:
- autonomous loops
- complex memory retrieval
- embeddings
- agent frameworks
- direct tool execution from handlers

**Core architectural model**

The runtime follows a re-frame-inspired architecture:

```text
event
  -> coeffect injection
  -> event handler
  -> declarative effects
  -> runtime effect execution
```

Handlers should:
- receive contextual inputs through coeffects
- return declarative effects
- avoid direct side effects
- remain mostly deterministic and testable

The runtime executes effects explicitly.

Conceptual runtime loop:

```text
dispatch event
   ↓
inject coeffects
   ↓
run event handler
   ↓
obtain effects map
   ↓
execute effects
   ↓
effects may dispatch more events
```

**Event model**

Events represent immutable facts — things that already happened. Events should be immutable, serializable, persistable, and traceable.

Examples:

```clojure
{:event/type :user/message
 :text "hello"}
```

```clojure
{:event/type :scheduler/tick}
```

```clojure
{:event/type :memory/stored}
```

- [x] Define event schema (EDN map with `:event/type`, `:id`, `:timestamp`; payload as top-level keys)
- [x] Implement event dispatcher — routes events to registered handlers by `:event/type`
- [x] Wire dispatcher as an Integrant component

**Coeffects**

Handlers should not directly fetch runtime dependencies. Runtime context is injected through coeffects.

```clojure
{:db ...
 :now ...
 :config ...
 :runtime ...
 :event ...
}
```

Future coeffects may include: retrieved memories, active tasks, identity/personality context, scheduler state.

Handlers become context-aware reducers rather than imperative services.

- [x] Define coeffect map schema (`{:db :now :config :runtime :event}`)
- [x] Implement coeffect injector for each key: `:db` (current state), `:now` (wall clock), `:config`, `:runtime`, `:event` (the triggering event)
- [x] Wire coeffect injection into the dispatch pipeline: enrich context before handler runs
- [x] Handlers receive coeffect map and return effects map — no direct side effects inside handlers

**Effect system**

Handlers return declarative effects maps. Effects represent intended operations, not immediate execution. The runtime owns effect execution.

Example:

```clojure
{:state
 (update db :conversation conj
         {:role :user
          :text (:text event)})

 :dispatch
 {:event/type :conversation/updated}

 :log/info
 {:message "User message received"}}
```

Effect vocabulary for this phase:

Runtime effects:
```clojure
:state
:dispatch
:dispatch-later
```

Observability effects:
```clojure
:log/info
:trace
:tap
```

Persistence effects (`:event/store`) are defined here but implemented in Phase 2. Additional effect types (HTTP, tools, memory persistence, scheduling, embeddings, notifications) are added in later phases.

- [x] Define effect descriptor schema (map keyed by effect type, values are params)
- [x] Implement `:db` effect — resets the in-memory state atom to the new value
- [x] Implement `:dispatch` effect — enqueues a new event onto the event bus
- [x] Implement `:dispatch-later` effect — schedules a delayed event dispatch
- [x] Implement `:log/info` effect — writes structured log entry via Timbre
- [x] Implement `:trace` effect — records a trace entry in the runtime trace log
- [x] Implement `:tap` effect — emits a value via `tap>` for Portal inspection
- [x] Implement effect executor: receives effects map returned by handler, dispatches each effect type
- [x] Wire effect executor into the dispatcher (stateless — no separate Integrant component needed)

The runtime should maintain an effect execution registry:

```clojure
(defmulti execute-effect ...)
```

Effect execution should remain observable, traceable, replaceable, and testable.

Distinguish between:
- Pure/internal effects: `:state`, `:dispatch`
- External/non-deterministic effects: HTTP requests, filesystem access, API calls

This distinction matters for replay, testing, and deterministic debugging.

**Runtime state**

Runtime state should only change through effects. Avoid direct atom mutation, hidden `swap!`, and arbitrary side effects inside handlers. The `:state` effect is the canonical state transition mechanism.

This improves replayability, observability, debugging, and auditability.

Initial runtime state should remain intentionally small — this is runtime operational state, not long-term assistant memory:

```clojure
{:conversation []
 :tasks {}
 :events/recent []
 :ui {}
}
```

- [x] Define initial runtime state shape (`{:conversation [], :tasks {}, :events/recent [], :ui {}}`)
- [x] State transitions only via `:db` effect — no direct `swap!` or `reset!` outside the executor

**Interceptor chain**

The runtime should support interceptor-style processing similar to re-frame.

Potential interceptor responsibilities: tracing, logging, metrics, validation, timing, safety checks, effect auditing.

```text
event
 -> tracing interceptor
 -> coeffect injection
 -> handler
 -> effect validation
 -> effect tracing
 -> effect execution
```

- [x] Design interceptor chain: tracing → coeffect injection → handler → effect validation → effect tracing → effect execution
- [x] Implement interceptor protocol and chain runner; all dispatch flows through the chain
- [x] Support per-handler interceptors: reg-handler accepts an optional interceptor vector for event-type-specific coeffect injection

**Replayability foundations**

The architecture should support reconstructing runtime behavior from initial state + event history. This enables deterministic debugging, event replay, cognition inspection, and runtime tracing. Replayability is a core architectural goal.

- [x] Accumulate dispatched events in `:events/recent` runtime state
- [x] Write replay function: reconstruct state from initial state + in-memory event sequence (no persistence yet — that is Phase 2)
- [x] Expose runtime state via `tap>` after every `:db` transition (via `db-tap-interceptor`)

**UI boundary**

The UI must remain a thin runtime client. The UI dispatches events and subscribes to runtime state. The UI must not directly mutate state, execute tools, or call cognition logic. This separation is a core architectural constraint.

- [x] Enforce UI boundary: pa.ui.* dispatches events and reads runtime state only via pa.runtime.queries — no direct state mutation, no cognition calls
- [x] Subscribe mechanism: tap> → core.async channel → recurring charm command delivers :runtime/db-updated into the loop
- [x] Query layer: pa.runtime.queries provides pure selector fns; all consumers use these instead of reaching into db structure directly

**Tests**

- [x] Write unit tests for dispatch → coeffect injection → handler → state transition
- [x] Write replay test: fixture event sequence → replay → assert reconstructed state matches expected

**Deliverables**

By the end of Phase 1, the system should support: runtime state model, event dispatching, coeffect injection, effect execution, declarative state transitions, structured tracing, replay/debugging foundations, observable runtime behavior, and thin UI integration. No advanced AI behavior required yet.

---

## Phase 2 — Persistent Storage & Memory Foundation

> **Namespace refactor note (from Phase 1):**
> As persistence concerns become clearer, consider splitting runtime orchestration from persistence/model concerns.
>
> Potential direction:
>
> ```text
> pa.runtime.*  -> orchestration/event/effect pipeline
> pa.storage.*  -> filesystem persistence
> pa.db.*       -> SQLite indexing/query layer
> pa.memory.*   -> semantic memory domain
> ```
>
> The dependency direction should remain one-way:
>
> ```text
> runtime -> storage/db/memory
> ```
>
> Avoid allowing persistence layers to depend on runtime orchestration.

---

### Goal

Establish a durable, inspectable, recoverable storage architecture.

This phase defines:

* canonical memory ownership
* operational persistence
* replay foundations
* indexing/query infrastructure
* startup/bootstrap semantics
* synchronization boundaries

The system should clearly distinguish between:

* semantic memory
* operational runtime persistence
* query/index infrastructure

---

### Storage Architecture

The project uses three distinct persistence layers.

---

#### 1. Semantic Layer (Canonical Cognition)

Purpose:

* human-readable assistant cognition
* inspectable/editable long-term memory

Storage:

* Markdown
* EDN where appropriate

Examples:

* memories
* reflections
* identity
* plans
* summaries

This layer is:

* canonical
* durable
* human-oriented

---

#### 2. Operational Runtime Layer

Purpose:

* runtime history
* replay/debugging
* event sourcing
* crash recovery

Storage:

* append-only EDN event log

Examples:

* dispatched events
* state transitions
* scheduler activity
* runtime traces

This layer is:

* append-only
* replayable
* operational

---

#### 3. Query/Index Layer

Purpose:

* fast retrieval
* filtering
* search
* indexing
* operational querying

Storage:

* SQLite

Examples:

* memory indexes
* tags
* summaries
* retrieval metadata
* task operational state

SQLite is:

* rebuildable
* disposable infrastructure
* NOT canonical cognition storage

---

### Canonical Ownership Rules

| Data Type             | Canonical Source          |
| --------------------- | ------------------------- |
| identity              | Markdown                  |
| semantic memory       | Markdown                  |
| runtime events        | append-only EDN log       |
| runtime state         | reconstructed from replay |
| retrieval metadata    | SQLite                    |
| indexes/search tables | SQLite                    |

---

### Important Architectural Rule

Markdown owns semantic content.

SQLite owns:

* indexes
* retrieval metadata
* operational query acceleration

SQLite must always be rebuildable from canonical artifacts.

The system should tolerate:

* SQLite corruption/deletion
  without:
* semantic memory loss

---

### assistant-data/ Layout

Create:

```text
assistant-data/
  identity/
    identity.md
    user.md
    agents.md

  memory/
    memory.md       ; always-loaded wisdom file (see Phase 5)
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

  workspace/          ; default read+write sandbox for filesystem tools

  sqlite/
    assistant.db
```

---

### Startup & Bootstrap Semantics

The runtime must support clean initialization on a new machine.

---

### First Startup Responsibilities

If `assistant-data/` does not exist:

#### Create directory structure

Initialize all required folders.

---

#### Create identity templates

Generate default:

* `identity.md`
* `user.md`
* `agents.md`

using minimal starter templates.

---

#### Initialize SQLite schema

Create:

* `memories`
* `tasks`
* optional future index tables

---

#### Initialize event log

Create empty:

```text
assistant-data/events/events.edn
```

---

#### Start with empty semantic memory

No initial memories required.

---

### Recovery Semantics

The system must support rebuilding operational infrastructure from canonical artifacts.

Examples:

#### SQLite deleted/corrupted

System should support:

```clojure
(rebuild-memory-index!)
```

which:

* scans Markdown memory files
* rebuilds SQLite indexes

---

#### Runtime crash

Runtime state should reconstruct from:

* event replay
* persisted operational events

---

### Event Persistence

Persist important runtime events to:

```text
assistant-data/events/events.edn
```

The event log should remain:

* append-only
* serializable
* replayable

Events should reconstruct runtime state via replay.

SQLite should NOT be required for replay.

---

### Replay Pipeline

Replay flow:

```text
events.edn
   -> load events
   -> dispatch through runtime
   -> reconstruct state
```

Replayability is a core architectural goal.

---

### Identity Loading

Implement identity loaders for:

* `identity.md`
* `user.md`
* `agents.md`

The loader should:

* parse markdown
* produce normalized EDN maps
* inject identity context into runtime startup state

Identity files are canonical and human-editable.

---

### Memory Domain Model

Define explicit semantic memory record schema.

Initial shape:

```clojure
{:memory/id ...
 :memory/type ...
 :memory/path ...
 :memory/title ...
 :memory/summary ...
 :memory/tags ...
 :memory/created-at ...}
```

Important:

* markdown file stores canonical semantic content
* SQLite stores retrieval/index metadata

Avoid treating memory as:

* raw chat transcript blobs

---

### Memory Persistence Flow

Memory persistence should follow this lifecycle:

```text
memory record created
    ↓
Markdown writer persists canonical artifact
    ↓
:event/memory-stored emitted
    ↓
SQLite indexer updates retrieval metadata
```

Synchronization should be:

* event-driven
* asynchronous-friendly
* rebuildable

---

### Markdown Memory Writer

Implement:

* semantic memory serialization
* daily note writing
* append/update semantics

Example target:

```text
assistant-data/memory/daily/YYYY-MM-DD.md
```

Markdown artifacts should remain:

* readable
* manually editable
* portable

---

### Markdown Memory Reader

Implement:

* parsing daily memory files
* reconstructing memory records
* extracting metadata/tags

This enables:

* reindexing
* replay
* migration
* recovery

---

### SQLite Schema

Initial SQLite schema should remain intentionally small.

Suggested initial `memories` table:

```text
id
path
type
title
summary
tags
created_at
```

Avoid:

* giant schemas
* premature normalization
* storing full cognition payloads

---

### SQLite Synchronization

Implement index synchronization:

```text
Markdown memory
   -> metadata extraction
   -> SQLite index update
```

SQLite acts as:

* retrieval accelerator
  not:
* canonical semantic storage

---

### Initial Queries

Implement basic retrieval queries:

* recent memories
* filter by type
* filter by tags

Initially retrieval can remain simple.

Sophisticated semantic retrieval comes later.

---

### Integrant Components

Introduce dedicated persistence components.

Potential structure:

```clojure
:storage/fs
:storage/events
:db/sqlite
:memory/store
:memory/indexer
```

The runtime should depend on these services explicitly.

---

### Testing Goals

#### Replay Tests

Persist fixture events to disk log.

Replay runtime.

Assert reconstructed state matches expected state.

---

#### Memory Round-Trip Tests

```text
memory record
   -> markdown writer
   -> markdown reader
   -> reconstructed record
```

Assert semantic equivalence.

---

#### SQLite Integration Tests

```text
memory record
   -> sqlite index write
   -> query
   -> reconstructed metadata
```

Assert retrieval correctness.

---

#### Identity Loader Tests

Load fixture markdown identity files.

Assert normalized EDN structure matches expected output.

---

### REPL Verification Goals

At REPL, confirm:

* identity loads correctly
* replay reconstructs runtime state
* memory writes round-trip cleanly
* SQLite indexes rebuild correctly
* deleting SQLite and rebuilding succeeds

---

### Deliverables

By the end of Phase 2, the system should support:

* durable assistant-data layout
* append-only operational event persistence
* replayable runtime reconstruction
* canonical Markdown semantic memory
* SQLite retrieval/index infrastructure
* rebuildable indexes
* identity loading
* event-driven synchronization
* inspectable storage artifacts
* recovery/bootstrap workflows
* tested persistence round-trips

---

## Phase 3 — LLM Integration

Goal: Introduce controlled, abstracted LLM interaction.

- [x] Define LLM provider protocol (`invoke`, `stream`)
- [x] Implement OpenAI provider (HTTP via hato/http-kit, streaming SSE)
- [x] Implement Anthropic provider stub (same protocol, minimal implementation)
- [x] Build prompt assembly pipeline: identity + user context + memory snippets → messages list
- [x] Wire LLM invocation as an effect type (`{:effect/type :llm/invoke, ...}`)
- [x] Capture terminal text input: maintain a UI-local input buffer in `pa.ui.app`, accumulate key presses, and on Enter dispatch a `:user/message` event with the buffer contents, then clear the buffer (keeps the UI a thin client — input becomes an event, no direct state mutation)
- [x] Implement streaming response handler: emit partial tokens as events
- [x] Display streamed response in charm.clj terminal UI
- [x] Integrate conversation turn into event log (user message + assistant response as events)
- [x] Confirm LLM does NOT yet write memory or execute tools — only responds
- [x] Write tests for prompt assembly with fixture identity/memory data
- [x] Write tests for LLM provider protocol with a stub/mock provider
- [x] Write tests for streaming response handler: fixture SSE chunks → assert events emitted
- [x] Write test for terminal input capture: simulate key presses + Enter → assert a `:user/message` event is dispatched with the buffer contents and the buffer is cleared

---

## Phase 4 — Tool System (Filesystem)

Goal: Deterministic, observable, safe tool execution — establish the full tool
machinery, exercised end-to-end by the filesystem tools only. Network-backed
tools (web search, webpage retrieval) are deferred to Phase 4b and YouTube
transcripts to Phase 4c, so this phase can ship without HTTP concerns.

Infrastructure:

- [x] Define tool registry: a map of `tool-name → {:fn, :schema, :description}`
- [x] Implement tool invocation as an effect type (`{:effect/type :tool/invoke, :tool/name ..., :tool/args ...}`)
- [x] Add dry-run mode: log effect descriptor without executing
- [x] Add structured logging for every tool invocation (tool, args, result, duration)
- [x] Wire tool results back into the event bus as `:tool/result` events

Filesystem access policy:

The allowlist is **one list of roots, each carrying per-root capability flags**, so
read and write scopes can differ and sensitive paths can be explicitly excluded.
It lives in `assistant-data/system/tools.md` (the infrastructure cheat sheet) and
is the single source of truth for what the filesystem tools may touch.

- Capabilities per root: `read`, `write`, `deny`. Example:

  ```text
  ~/Projects        read write
  ~/Documents       read
  assistant-data/   read write
  ~/.ssh            deny
  ```

- Resolution rules:
  - Canonicalize the requested path first (expand `~`, resolve `..` and symlinks to a real absolute path) — capability checks run on the *resolved* path, not the literal argument.
  - Match against the **most-specific (longest-prefix) matching root**.
  - `deny` always wins: if the resolved path falls under any `deny` root, reject regardless of other matches.
  - A path matching no root is rejected (default-deny).
  - `write` does not imply `read` unless both flags are present on the root.

Tasks:

- [x] Backfill the Phase 2 bootstrap gap: `system/tools.md` was never added to first-startup template generation, so extend bootstrap to write a default `assistant-data/system/tools.md` whose allowlist grants `read write` on a dedicated `workspace/` sandbox only (the assistant's own identity/events/sqlite stay non-tool-writable; safe default-deny baseline)
- [x] Parse the allowlist (roots + capability flags) out of `system/tools.md` at startup into an in-memory policy structure
- [x] Implement a path resolver: canonicalize the requested path, find the longest-prefix matching root, and return the granted capability set (honoring `deny`-wins and default-deny)
- [x] Implement `read-file` (path → contents, with schema) — requires `read` on the resolved path
- [x] Implement `list-dir` (path → entries, with schema) — requires `read` on the resolved path
- [x] Implement `write-file` (path + contents → write, with schema) — requires `write` on the resolved path
- [x] Follow-on tools (the initial three were too sparse): `make-dir`, `delete` (recursive behind a flag; refuses to delete an allowlist root), `move`/rename (needs `write` on both ends), `file-info` (exists/type/size). All gated under existing `read`/`write` capabilities.

Tests:

- [x] Write tests for each filesystem tool with a mocked/temp filesystem
- [x] Write tests for the path resolver: out-of-root paths, `..` traversal, and symlink escape are all rejected
- [x] Write tests for per-root capabilities: a `read`-only root rejects `write-file`/refuses writes; a `deny` root rejects reads even if a broader root would allow them; longest-prefix root wins
- [x] Write tests for dry-run mode: assert no side effects occur, correct effect descriptor is logged

LLM tool use (multi-hop as of Phase 4b):

- [x] Let the LLM call a tool and continue the turn in one hop — a scope addition beyond the original Phase 4 list. The provider protocol returns `{:content :tool-calls}`; `:user/message` advertises the registered tools; a tool request becomes `:assistant/tool-call → :tool/invoke → :tool/result → ` a follow-up `:llm/invoke`. Multiple tool calls in one turn run sequentially, with the follow-up deferred until all have results. Streamed `tool_calls` are assembled in the OpenAI provider. (Phase 4b extended this to re-advertise tools on follow-up, enabling multi-hop chains like search → fetch → answer.)

(Tool-argument schema validation — enforcement + property tests — is carried
into Phase 4b, where LLM-supplied arguments to network tools make it matter
most. Phase 4 registers each tool's `:schema` and advertises it to the LLM but
does not yet validate args against it.)

---

## Phase 4b — Tool System (Network)

Goal: Extend the Phase 4 tool machinery with network-backed tools, reusing the
registry, effect type, dry-run, logging, and event-bus wiring already in place —
and harden the shared machinery with argument-schema validation carried over
from Phase 4.

Shared machinery:

- [x] Enforce tool-argument schemas in `:tool/invoke`: validate `:tool/args` against the registered `:schema` before executing; on failure emit a `:tool/result` error (`:type :tool/invalid-args`) and do not run the tool. Applies to all tools, filesystem and network.
- [x] Write property-based (test.check) tests for tool schema validation

Network tools:

- [x] Implement web search tool (DuckDuckGo or similar, no API key required initially)
- [x] Implement webpage retrieval tool: fetch + reduce to readable text/markdown (parse with a small library, not regex — strip scripts/styles/markup; raw HTML behind a `:format` flag; full main-content Readability is a later nice-to-have). Apply the domain/IP allow-deny + SSRF guard from [design-notes.md](design-notes.md) (resolve host → reject private/link-local ranges), which also covers the extraction approach.
- [x] Write tests for each tool with mocked HTTP

---

## Phase 4c — Tool System (YouTube Transcripts)

Goal: A transcript-retrieval tool for YouTube videos, reusing the Phase 4 tool
machinery. Split out from the network tools because it leans on a different
mechanism (a `yt-dlp` subprocess or a transcript API) with its own dependency
footprint and failure modes (no captions, age/region restrictions).

- [x] Decide and document the mechanism — `yt-dlp` subprocess vs a transcript API — and its dependency/runtime footprint
- [x] Implement the transcript tool: accept a URL or video id, return the transcript text
- [x] Surface the no-transcript / unavailable / restricted cases as clean `:tool/result` errors, not crashes
- [x] Write tests with the subprocess/HTTP boundary mocked

---

## Phase 4d — Command History

Goal: Improve terminal UX with persistent, navigable command history — up/down arrows cycle through past commands, history survives restarts.

Storage:

- [x] Extend first-startup bootstrap to create `assistant-data/history/history.edn` (empty file, same append-only EDN format as `events.edn`)
- [x] Define history entry schema: `{:history/id ... :history/text ... :history/timestamp ...}`
- [x] Load last 50 entries from `history.edn` at startup into `:ui/history` in runtime state

History persistence:

- [x] Implement `:history/append` effect type: appends a new entry to `history.edn` and updates `:ui/history` in runtime state
- [x] Wire `:history/append` into the `:user/message` handler — every submitted message appends to history
- [x] Deduplicate consecutive identical entries (skip append if the new text matches the most recent entry)

UI navigation (ephemeral, UI-local state — not in runtime db):

- [x] Track a navigation index and a "draft" buffer (text typed before first ↑ press) in UI component state
- [x] ↑ arrow: move backward through history, fill input buffer with that entry
- [x] ↓ arrow: move forward; when past the most recent entry, restore the saved draft
- [x] Any character typed while navigating resets the navigation index (treats it as a new command draft)

Tests:

- [x] Load fixture `history.edn` → assert entries parsed correctly and last 50 are loaded
- [x] Submit message → assert new entry appended to file and `:ui/history` state updated
- [x] Consecutive duplicate suppression: submitting the same text twice → assert only one entry added
- [x] Navigation state machine: assert ↑/↓ cycle correctly through entries and draft is preserved and restored

---

## Phase 4e — Multiline Input

Goal: Support multiline messages — both pasted from the clipboard and typed manually — without treating embedded newlines as premature submits.

Problems to solve:
- Pasting a multiline string currently fires one `:user/message` per line, which can cause unintended tool calls (e.g. pasting documentation triggers a cascade of LLM invocations)
- There is no way to insert a newline manually (e.g. Shift+Enter or Alt+Enter) to compose a multiline message before submitting

Input capture:
- [x] Intercept paste events: detect multi-line clipboard content and insert it into the buffer verbatim instead of dispatching each line as a separate submit
- [x] Add a manual newline key binding (Alt+Enter / Option+Return): insert `\n` into the input buffer without submitting
- [x] Enter alone still submits (existing behaviour preserved)

Buffer display:
- [x] Render the input buffer as a multi-line widget when it contains newlines (the input area grows or scrolls to accommodate the content)

Submission:
- [x] The full buffer (including embedded newlines) is dispatched as a single `:user/message` event on Enter

Tests:
- [x] Paste simulation: inject a multiline string into the buffer → assert single entry in `:ui/history`, single `:user/message` dispatched
- [x] Alt+Enter inserts newline without submitting
- [x] Enter on a buffer containing newlines dispatches the whole text as one event
- [x] Regression: single-line Enter submit path unchanged

---

## Phase 5 — Memory Retrieval

Goal: Make the assistant context-aware using retrieved memory. This phase focuses
on retrieval (reading) — automatic memory extraction (writing) is a Phase 6
background cognition concern.

### MEMORY.md — Always-Loaded Wisdom File

The original project vision distinguished two memory tiers:

- `MEMORY.md` — curated, always in context: key facts, decisions, lessons — the
  assistant's "permanent working memory" about the user and their world
- `memory/daily/` — high-volume episodic notes, subject to retrieval scoring

The roadmap collapsed these into one pool but they are meaningfully different.
`MEMORY.md` content should always be injected into the system message alongside
`identity.md` and `user.md` — never scored out by relevance decay. An episodic
note about a past conversation lives in the daily pool; a permanent fact like
"user is building a personal assistant in Clojure" belongs in `MEMORY.md`.

The assistant writes to `MEMORY.md` deliberately. Phase 6 background cognition
may also promote distilled wisdom there.

- [x] Add `assistant-data/memory/memory.md` template to first-startup bootstrap (minimal — just a header, empty content)
- [x] Extend `pa.storage.identity/load-all` to parse and return `memory.md` alongside the three identity files
- [x] Update `pa.llm.prompt/assemble` to inject `memory.md` content as an always-present system message section

### Schema Updates

The `memories` table was created in Phase 2 but is currently empty (smoke-test
data only), so migrations are free.

- [x] Change `created_at` column from TEXT to INTEGER (Unix epoch milliseconds) — cleaner for age-based decay arithmetic; update `record->row` and `row->record` in `pa.db.memory` accordingly
- [x] Add FTS5 virtual table: `CREATE VIRTUAL TABLE memories_fts USING fts5(id UNINDEXED, title, summary, tags)` in `pa.db.schema/init!`
- [x] Update `pa.db.memory/index!` to upsert into `memories_fts` alongside `memories` (use `INSERT OR REPLACE`)
- [x] Update `pa.memory.indexer/rebuild-memory-index!` to drop and recreate both `memories` and `memories_fts`

### Retrieval Query Schema

- [x] Define retrieval query spec: `{:query/text <string>, :query/types <keyword-set, optional>, :query/limit <int>}`

### Retrieval Functions

`pa.db.memory` already has `recent`, `by-type`, and `by-tags`. Add:

- [x] Implement `by-keyword`: FTS5 full-text search over `title`, `summary`, `tags`; returns records ordered by FTS rank
- [x] Implement relevance decay scoring function: `score = match_score * exp(-λ * age_days)`; `match_score` is 1.0 for keyword hits, 0.5 for recency-only; λ is a tunable config constant (start with a 30-day half-life)
- [x] Implement combined retrieval: union of recency + keyword result sets → apply decay scoring → deduplicate by id → return top-N

### `:memories` Coeffect

Retrieval is a side-effecting operation (SQLite read) and cannot happen inside a
pure handler. The coeffect injection step runs before the handler, has access to
the triggering event, and is the right place to call retrieval.

- [x] Add `:memories` coeffect injector to `pa.runtime.coeffects`: reads the `:event` coeffect for query text (`:content` of a `:user/message` event, empty string otherwise), calls combined retrieval, injects result as `{:memories [...]}` into the coeffect map
- [x] Register the `:memories` injector so it runs for `:user/message` events (either globally or as a per-handler interceptor)

### Wire into Prompt Assembly

- [x] Update `assemble-for` in `pa.runtime.handlers` to read `:memories` from the coeffect map instead of hardcoding `[]`
- [x] REPL verification: send a message about a topic with a prior memory record → confirm assistant references it without being told to

### Tests

- [x] Unit tests for `by-keyword` FTS with fixture memory records inserted into an in-memory SQLite db
- [x] Unit tests for combined retrieval: fixture records with varying age and keyword match → assert top-N ordering reflects decay scoring
- [x] Tests for relevance decay function: assert a record that is 60 days old scores lower than an identical record that is 1 day old
- [x] Test `:memories` coeffect injection: dispatch a `:user/message` event with a query term that matches a fixture record → assert `:memories` in the coeffect map is non-empty before the handler runs
- [x] Test `MEMORY.md` loader: fixture `memory.md` file → assert its content appears in the assembled system message
- [x] Test schema migration: `init!` creates both `memories` and `memories_fts`; `index!` writes to both; `rebuild-memory-index!` drops and recreates both

---

## Phase 6 — Scheduling & Background Cognition

Goal: Introduce time-based behavior and background work. Also introduces automatic
memory extraction as a background cognition job — the right granularity for
extraction is a session or time window, not a single message, so it lives here
rather than inline with every LLM response.

- [ ] Define scheduled task schema (`:task/id`, `:task/cron`, `:task/type`, `:task/payload`)
- [ ] Implement cron-style scheduler as an Integrant component (fire events on schedule)
- [ ] Implement reminder task type: emit a `:reminder/due` event at scheduled time
- [ ] Implement periodic reflection job: summarize recent memory into `cognition/reflections/`
- [ ] Implement memory consolidation job: merge daily memory files into longer-term summaries
- [ ] Integrate `HEARTBEAT.md` as a checklist loaded and executed by the scheduler
- [ ] Persist scheduled tasks to `tasks/scheduled/` as EDN files
- [ ] Move completed tasks to `tasks/completed/`
- [ ] Expose scheduler state via Portal

Memory extraction (background job):

- [ ] Implement `memory.md` writer: reads current `memory.md` content, merges in new permanent facts (add new bullet items, deduplicate against existing content, optionally remove entries explicitly marked stale), writes back — distinct from the append-only daily writer because `memory.md` is a curated document, not a log
- [ ] Implement end-of-session memory extraction job: after the conversation reaches N turns (configurable threshold), run an extraction prompt against recent conversation history; the prompt classifies each extracted item as ephemeral (→ `memory/daily/` via `:memory/write` effect) or permanent (→ `memory.md` via the wisdom writer); N is a config parameter (start with 10 turns)
- [ ] Extraction runs asynchronously and does not block user input or app shutdown — fire-and-forget via `dispatch!` from the scheduler
- [ ] Write tests for the extraction job: fixture conversation of N turns → assert ephemeral items produce `:memory/write` effects and permanent items are merged into `memory.md`
- [ ] Write tests for the `memory.md` writer: fixture current content + new items → assert output contains new items, deduplicates exact matches, preserves unrelated existing content

Scheduler tests:

- [ ] Test: schedule a task, let it fire, confirm event appears in log
- [ ] Write unit tests for scheduler: mock clock, assert tasks fire at correct intervals
- [ ] Write tests for reflection and consolidation jobs with fixture memory data

---

## Phase 7 — Explicit Cognitive Pipeline

Goal: Formalize and make inspectable all cognition stages.

- [ ] Define slash command registry: map of `/command-name → handler-fn`; wire UI input parser to dispatch commands as events before entering the cognition pipeline (e.g. `/rebuild-memory-index` → `:memory/rebuild-index` event → calls `(:rebuild! indexer)`)
- [ ] Define pipeline stage protocol: each stage takes context map → returns updated context map
- [ ] Implement `interpret` stage: classify user intent, extract entities
- [ ] Implement `retrieve` stage: call memory retrieval (Phase 5) and attach results to context
- [ ] Implement `plan` stage: decide which tools (if any) are needed
- [ ] Implement `tool-select` stage: emit tool invocation effects (Phase 4)
- [ ] Implement `respond` stage: assemble final prompt and call LLM (Phase 3)
- [ ] Implement `extract` stage: trigger background extraction if session threshold is met (Phase 6)
- [ ] Implement `consolidate` stage: trigger background consolidation if thresholds are met
- [ ] Emit a `:cognition/pipeline-trace` event capturing the full context map at each stage
- [ ] Make each stage independently testable with fixture context maps
- [ ] Write unit tests for every pipeline stage: fixture context map in → assert expected context map out
- [ ] Write integration test for full pipeline run with stub LLM and mocked tools
- [ ] Write property-based tests for pipeline stage composition (any valid context in → valid context out)

---

## Phase 8 — Personality & Long-Term Evolution

Goal: Evolve the assistant into a durable long-term system.

- [ ] Define personality schema in `identity.md` (name, traits, communication style, values)
- [ ] Inject personality into prompt assembly as a stable system prefix
- [ ] Implement user model evolution: update `user.md` when new facts are extracted
- [ ] Implement memory decay: lower retrieval weight for memories beyond age/access thresholds; tune λ against real usage
- [ ] Implement summarization pipeline: run an LLM summarization pass over old episodic daily memories (older than a configurable age threshold), write distilled summaries as new semantic memory entries in `memory/semantic/`
- [ ] Implement `memory.md` promotion step in the consolidation job: after summarization, identify key insights worth permanent storage and merge them into `memory.md` via the wisdom writer from Phase 6; this is the bulk/background path complementing the per-session extraction path
- [ ] Implement reflection system: periodic self-assessment of assistant behavior and user patterns
- [ ] Store reflections in `cognition/reflections/` and inject top-N into context
- [ ] Confirm personality remains consistent across sessions with identity reload
- [ ] Write tests for user model evolution: assert `user.md` updates correctly from extracted facts
- [ ] Write tests for summarization pipeline with fixture episodic memory sets
- [ ] Write regression tests for personality consistency: fixture session → assert identity fields stable across reload

---

## Phase 9 — Optional Advanced Features

These are explicitly deferred and not required for a complete system.

- [ ] Local model support (Ollama or similar)
- [ ] Voice input / output
- [ ] Web UI (optional complement to terminal)
- [ ] Mobile interface
- [ ] Graph-based memory (entities + relationships)
- [ ] Semantic planning (multi-step goal decomposition)
- [ ] Multi-agent experimentation
- [ ] Autonomous task execution (self-initiated without user trigger)

Semantic memory retrieval (embeddings):

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
