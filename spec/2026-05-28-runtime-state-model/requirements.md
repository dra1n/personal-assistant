# Requirements: Phase 1 — Runtime & State Model

## Goal

Build the foundational event-driven cognitive runtime. This phase establishes the complete re-frame-inspired pipeline: event dispatch, coeffect injection, event handler invocation, declarative effect production, and runtime effect execution. By the end, the system supports deterministic event processing, observable state transitions, structured tracing, and replay/debugging foundations — without any AI or LLM behavior.

## Scope

### In scope

- Event schema: immutable EDN maps with `:event/type`, `:id`, `:timestamp`, and top-level payload keys
- Event dispatcher: routes events to registered handlers by `:event/type`; wired as an Integrant component
- Coeffect system: injects `{:db :now :config :runtime :event}` into handlers before invocation
- Effect executor: receives effects map from handler, dispatches each effect type via `defmulti`; wired as an Integrant component
- Effect types: `:state`, `:dispatch`, `:dispatch-later`, `:log/info`, `:trace`, `:tap`
- Persistence effect `:event/store` defined but not implemented (Phase 2)
- Runtime state atom: shape `{:conversation [], :tasks {}, :events/recent [], :ui {}}`; transitions only via `:state` effect
- Interceptor chain: tracing → coeffect injection → handler → effect validation → effect execution; all dispatch flows through it
- Replayability foundations: accumulate events in `:events/recent`; in-memory replay function; expose state via `tap>`
- UI boundary enforcement: charm.clj dispatches events and reads state only — no direct mutation, no cognition calls
- Unit tests, property-based tests (test.check), and replay test
- REPL demo session: dispatch → coeffect injection → handler → state transition, plus successful replay run

### Out of scope

- Event persistence to disk (Phase 2)
- Disk-based replay (Phase 2)
- LLM invocation (Phase 3)
- Tool execution (Phase 4)
- Memory retrieval or embeddings (Phase 5)
- Scheduler / cron system (Phase 6)
- Any autonomous cognition loops

## Design decisions

1. **Re-frame pipeline** — The canonical dispatch flow is: `dispatch event → inject coeffects → run handler → obtain effects map → execute effects`. Handlers are pure-ish functions: coeffect map in, effects map out. No direct side effects inside handlers.

2. **Effect execution via `defmulti`** — `execute-effect` is a multimethod dispatched on effect type. This makes the registry open for extension in later phases without modifying the executor core.

3. **Single state atom, `:state` effect only** — Runtime state lives in one atom. All transitions go through the `:state` effect executor method. Direct `swap!` or `reset!` outside the executor is prohibited. This enforces replayability and makes every state change observable.

4. **Integrant for component lifecycle** — Dispatcher and effect executor are Integrant components. Coeffect injectors and the interceptor chain runner compose within or alongside these components, consistent with Phase 0's architecture.

5. **Interceptor chain required for all dispatch** — No event bypasses the chain. This ensures tracing, validation, and coeffect injection are always applied, and the chain can be extended (e.g. timing, auth checks) without touching the dispatcher.

6. **`:dispatch-later` is in scope** — Included as specified in the roadmap. Implementation uses a `core.async` timeout channel; the full cron scheduler is deferred to Phase 6.

7. **In-memory replay only** — The replay function consumes `:events/recent` from runtime state. Disk-backed replay (loading from `events.edn`) is explicitly Phase 2.

8. **UI boundary is a structural rule, not a runtime guard** — The constraint is enforced by code organization (charm.clj only calls `dispatch` and reads from a subscription or state deref), not by a runtime enforcement mechanism.

## Context

Phase 0 established the Integrant system map, Timbre logging, Portal/`tap>` plumbing, charm.clj TUI skeleton, and a minimal event bus stub. Phase 1 replaces that stub with a full production-quality runtime. The re-frame architecture pattern was chosen in Phase 0 planning (see `spec/roadmap.md`) and is the primary structural constraint shaping every design decision here. All implementation is JVM Clojure; core.async channels back the event bus per `spec/tech-stack.md`.
