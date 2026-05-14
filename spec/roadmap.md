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

Goal: Build the core event-driven runtime.

- [ ] Define the event schema (EDN map with `:type`, `:id`, `:timestamp`, `:payload`)
- [ ] Implement event dispatcher — routes events to registered handlers
- [ ] Define effect descriptor schema (`:effect/type`, `:effect/params`)
- [ ] Implement effect executor — receives effect descriptors, dispatches side effects
- [ ] Wire dispatcher and executor through the Integrant system
- [ ] Implement in-memory state atom with transition functions
- [ ] Persist events to an append-only EDN log file
- [ ] Write a replay function: load log → re-run events → reconstruct state
- [ ] Expose runtime state via `tap>` for Portal inspection
- [ ] Write unit tests for dispatch → state transitions
- [ ] Write property-based tests for event schema validation (test.check)
- [ ] Write replay test: persist fixture events to log, replay, assert reconstructed state matches expected

---

## Phase 2 — Persistent Storage & Memory Foundation

Goal: Create durable, inspectable storage.

- [ ] Create `assistant-data/` directory layout (identity/, memory/, cognition/, tasks/, system/)
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
