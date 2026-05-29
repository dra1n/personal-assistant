# Roadmap

Phases are ordered by dependency — each builds on the last. Phases 0–9 are all broken into concrete micro-steps.

Status legend: `[ ]` not started · `[~]` in progress · `[x]` done

---

## Phase 0 — Foundation & Boilerplate

Goal: Establish development ergonomics and architectural skeleton. No AI functionality.

- [ ] Create `deps.edn` with initial dependencies (Integrant, Timbre, core.async, Portal)
- [ ] Define namespace layout (`pa.core`, `pa.system`, `pa.runtime`, etc.)
- [ ] Wire Integrant system map with placeholder components
- [ ] Add Timbre logging with basic console appender
- [ ] Set up Portal integration and `tap>` plumbing in dev namespace
- [ ] Add `dev/user.clj` with REPL helpers (`start`, `stop`, `reset`)
- [ ] Add charm.clj dependency; render a static "hello" terminal frame
- [ ] Create a minimal event bus (core.async channel + dispatcher stub)
- [ ] Confirm system starts, stops cleanly, and REPL workflow is ergonomic
- [ ] Write smoke tests: system starts, all Integrant components initialize and halt without error

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

- [ ] Define event schema (EDN map with `:event/type`, `:id`, `:timestamp`; payload as top-level keys)
- [ ] Implement event dispatcher — routes events to registered handlers by `:event/type`
- [ ] Wire dispatcher as an Integrant component

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

- [ ] Define coeffect map schema (`{:db :now :config :runtime :event}`)
- [ ] Implement coeffect injector for each key: `:db` (current state), `:now` (wall clock), `:config`, `:runtime`, `:event` (the triggering event)
- [ ] Wire coeffect injection into the dispatch pipeline: enrich context before handler runs
- [ ] Handlers receive coeffect map and return effects map — no direct side effects inside handlers

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

- [ ] Define effect descriptor schema (map keyed by effect type, values are params)
- [ ] Implement `:state` effect — applies a transition function to the in-memory state atom
- [ ] Implement `:dispatch` effect — enqueues a new event onto the event bus
- [ ] Implement `:dispatch-later` effect — schedules a delayed event dispatch
- [ ] Implement `:log/info` effect — writes structured log entry via Timbre
- [ ] Implement `:trace` effect — records a trace entry in the runtime trace log
- [ ] Implement `:tap` effect — emits a value via `tap>` for Portal inspection
- [ ] Implement effect executor: receives effects map returned by handler, dispatches each effect type
- [ ] Wire effect executor as an Integrant component alongside the dispatcher

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

- [ ] Define initial runtime state shape (`{:conversation [], :tasks {}, :events/recent [], :ui {}}`)
- [ ] State transitions only via `:state` effect — no direct `swap!` or `reset!` outside the executor

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

- [ ] Design interceptor chain: tracing → coeffect injection → handler → effect validation → effect execution
- [ ] Implement interceptor protocol and chain runner; all dispatch flows through the chain

**Replayability foundations**

The architecture should support reconstructing runtime behavior from initial state + event history. This enables deterministic debugging, event replay, cognition inspection, and runtime tracing. Replayability is a core architectural goal.

- [ ] Accumulate dispatched events in `:events/recent` runtime state
- [ ] Write replay function: reconstruct state from initial state + in-memory event sequence (no persistence yet — that is Phase 2)
- [ ] Expose runtime state via `tap>` for Portal inspection

**UI boundary**

The UI must remain a thin runtime client. The UI dispatches events and subscribes to runtime state. The UI must not directly mutate state, execute tools, or call cognition logic. This separation is a core architectural constraint.

- [ ] Enforce UI boundary: charm.clj only dispatches events and reads runtime state — no direct state mutation, no cognition calls

**Tests**

- [ ] Write unit tests for dispatch → coeffect injection → handler → state transition
- [ ] Write property-based tests for event schema validation (test.check)
- [ ] Write replay test: fixture event sequence → replay → assert reconstructed state matches expected

**Deliverables**

By the end of Phase 1, the system should support: runtime state model, event dispatching, coeffect injection, effect execution, declarative state transitions, structured tracing, replay/debugging foundations, observable runtime behavior, and thin UI integration. No advanced AI behavior required yet.

---

## Phase 2 — Persistent Storage & Memory Foundation

> **Namespace refactor note (from Phase 1):** `pa.runtime.state` and `pa.runtime.queries` are currently co-located with the dispatch pipeline in `pa.runtime.*`. As Phase 2 adds event persistence, memory records, and richer state shape, consider splitting into a `pa.db` or `pa.model` namespace family (`pa.db.state`, `pa.db.queries`) with a clean one-way dependency: `pa.runtime.*` → `pa.db.*`. Wait until Phase 2 concerns are concrete before committing to the split.

Goal: Create durable, inspectable storage.

- [ ] Create `assistant-data/` directory layout (identity/, memory/, cognition/, tasks/, system/)
- [ ] Persist events to an append-only EDN log file (`assistant-data/events/events.edn`)
- [ ] Update replay function to load log from disk → re-run events → reconstruct state
- [ ] Write replay test: persist fixture events to disk log, replay, assert reconstructed state matches expected
- [ ] Write identity loader: parse `soul.md`, `identity.md`, `user.md` into EDN maps at startup
- [ ] Define memory record schema (`:id`, `:type`, `:content`, `:created-at`, `:tags`)
- [ ] Implement Markdown memory writer: serialize memory records to `memory/daily/YYYY-MM-DD.md`
- [ ] Implement Markdown memory reader: parse daily files back into memory record maps
- [ ] Initialize SQLite schema: `memories` table (id, type, content, created_at, tags)
- [ ] Implement SQLite index sync: write memory records to both Markdown and SQLite
- [ ] Write basic memory query: fetch recent N records by type or tag
- [ ] Wire memory system as an Integrant component
- [ ] Confirm identity loads and memory round-trips cleanly at REPL
- [ ] Write round-trip tests: memory record → Markdown writer → Markdown reader → assert equal
- [ ] Write integration tests: memory record → SQLite write → query → assert returned record matches
- [ ] Write tests for identity loader with fixture Markdown files

---

## Phase 3 — LLM Integration

Goal: Introduce controlled, abstracted LLM interaction.

- [ ] Define LLM provider protocol (`invoke`, `stream`)
- [ ] Implement OpenAI provider (HTTP via hato/http-kit, streaming SSE)
- [ ] Implement Anthropic provider stub (same protocol, minimal implementation)
- [ ] Build prompt assembly pipeline: identity + user context + memory snippets → messages list
- [ ] Wire LLM invocation as an effect type (`{:effect/type :llm/invoke, ...}`)
- [ ] Implement streaming response handler: emit partial tokens as events
- [ ] Display streamed response in charm.clj terminal UI
- [ ] Integrate conversation turn into event log (user message + assistant response as events)
- [ ] Confirm LLM does NOT yet write memory or execute tools — only responds
- [ ] Write tests for prompt assembly with fixture identity/memory data
- [ ] Write tests for LLM provider protocol with a stub/mock provider
- [ ] Write tests for streaming response handler: fixture SSE chunks → assert events emitted

---

## Phase 4 — Tool System

Goal: Deterministic, observable, safe tool execution.

- [ ] Define tool registry: a map of `tool-name → {:fn, :schema, :description}`
- [ ] Implement tool invocation as an effect type (`{:effect/type :tool/invoke, :tool/name ..., :tool/args ...}`)
- [ ] Implement filesystem tools: `read-file`, `write-file`, `list-dir` (with path allowlist)
- [ ] Implement web search tool (DuckDuckGo or similar, no API key required initially)
- [ ] Implement webpage retrieval tool (fetch + extract readable text)
- [ ] Implement YouTube transcript tool (yt-dlp or transcript API)
- [ ] Add dry-run mode: log effect descriptor without executing
- [ ] Add structured logging for every tool invocation (tool, args, result, duration)
- [ ] Wire tool results back into the event bus as `:tool/result` events
- [ ] Write tests for each tool with mocked HTTP/filesystem
- [ ] Write tests for dry-run mode: assert no side effects occur, correct effect descriptor is logged
- [ ] Write property-based tests for tool schema validation

---

## Phase 5 — Memory Retrieval

Goal: Make the assistant context-aware using retrieved memory.

- [ ] Define retrieval query schema (`:query/text`, `:query/types`, `:query/limit`)
- [ ] Implement recency-based retrieval: fetch N most recent memories by type
- [ ] Implement keyword-based retrieval: SQLite full-text search over content
- [ ] Integrate embeddings: generate embedding on memory write, store in SQLite
- [ ] Implement semantic retrieval: cosine similarity over stored embeddings
- [ ] Build memory extraction pipeline: after LLM response → extract facts/episodes as new memory records
- [ ] Build context assembly: retrieval results → prompt snippet injected into Phase 3 prompt pipeline
- [ ] Add relevance decay: memories older than threshold get lower retrieval weight
- [ ] Wire retrieval as part of the cognition pipeline (before LLM invocation)
- [ ] Confirm assistant references past context without being explicitly told to
- [ ] Write unit tests for recency and keyword retrieval with fixture memory records
- [ ] Write unit tests for memory extraction pipeline with fixture LLM response text
- [ ] Write tests for relevance decay: assert older records score lower than recent ones
- [ ] Write embedding round-trip test: generate embedding, store, retrieve by cosine similarity

---

## Phase 6 — Scheduling & Background Cognition

Goal: Introduce time-based behavior and background work.

- [ ] Define scheduled task schema (`:task/id`, `:task/cron`, `:task/type`, `:task/payload`)
- [ ] Implement cron-style scheduler as an Integrant component (fire events on schedule)
- [ ] Implement reminder task type: emit a `:reminder/due` event at scheduled time
- [ ] Implement periodic reflection job: summarize recent memory into `cognition/reflections/`
- [ ] Implement memory consolidation job: merge daily memory files into longer-term summaries
- [ ] Integrate `HEARTBEAT.md` as a checklist loaded and executed by the scheduler
- [ ] Persist scheduled tasks to `tasks/scheduled/` as EDN files
- [ ] Move completed tasks to `tasks/completed/`
- [ ] Expose scheduler state via Portal
- [ ] Test: schedule a task, let it fire, confirm event appears in log
- [ ] Write unit tests for scheduler: mock clock, assert tasks fire at correct intervals
- [ ] Write tests for reflection and consolidation jobs with fixture memory data

---

## Phase 7 — Explicit Cognitive Pipeline

Goal: Formalize and make inspectable all cognition stages.

- [ ] Define pipeline stage protocol: each stage takes context map → returns updated context map
- [ ] Implement `interpret` stage: classify user intent, extract entities
- [ ] Implement `retrieve` stage: call memory retrieval (Phase 5) and attach results to context
- [ ] Implement `plan` stage: decide which tools (if any) are needed
- [ ] Implement `tool-select` stage: emit tool invocation effects (Phase 4)
- [ ] Implement `respond` stage: assemble final prompt and call LLM (Phase 3)
- [ ] Implement `extract` stage: extract new memories from the response (Phase 5)
- [ ] Implement `consolidate` stage: trigger background consolidation if thresholds are met
- [ ] Emit a `:cognition/pipeline-trace` event capturing the full context map at each stage
- [ ] Make each stage independently testable with fixture context maps
- [ ] Write unit tests for every pipeline stage: fixture context map in → assert expected context map out
- [ ] Write integration test for full pipeline run with stub LLM and mocked tools
- [ ] Write property-based tests for pipeline stage composition (any valid context in → valid context out)

---

## Phase 8 — Personality & Long-Term Evolution

Goal: Evolve the assistant into a durable long-term system.

- [ ] Define personality schema in `soul.md` (name, traits, communication style, values)
- [ ] Inject personality into prompt assembly as a stable system prefix
- [ ] Implement user model evolution: update `user.md` when new facts are extracted
- [ ] Implement memory decay: lower retrieval weight for memories beyond age/access thresholds
- [ ] Implement summarization pipeline: distill old episodic memories into semantic memory entries
- [ ] Implement reflection system: periodic self-assessment of assistant behavior and user patterns
- [ ] Store reflections in `cognition/reflections/` and inject top-N into context
- [ ] Confirm personality remains consistent across sessions with identity reload
- [ ] Review and tune memory decay parameters against real usage
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
