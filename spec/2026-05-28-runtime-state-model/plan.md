# Plan: Phase 1 — Runtime & State Model

## Runtime loop (reference)

Every dispatch flows through this pipeline — no shortcuts:

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

Handlers are context-aware reducers: coeffect map in → effects map out. No direct side effects inside handlers.

---

## Task groups

### Group 1 — Event schema and dispatcher

Events represent immutable facts — things that already happened. They must be immutable, serializable, persistable, and traceable.

Example events:
```clojure
{:event/type :user/message
 :text "hello"}

{:event/type :scheduler/tick}

{:event/type :memory/stored}
```

- [ ] Define event schema: EDN map with `:event/type` (namespaced keyword), `:id` (uuid), `:timestamp` (inst), and top-level payload keys
- [ ] Write a `make-event` constructor that stamps `:id` and `:timestamp` automatically
- [ ] Implement event dispatcher: a registry (atom of `{event-type → handler-fn}`) plus a `dispatch!` entry point that routes by `:event/type`
- [ ] Wire dispatcher as an Integrant component (start: initialize registry; halt: drain and close)
- [ ] **Tests:** unit tests for `make-event` shape (all required keys present, correct types); unit tests for dispatch routing (correct handler called, unknown `:event/type` no-ops without throwing)

### Group 2 — Coeffects

Handlers must not directly fetch runtime dependencies. All runtime context is injected through coeffects before the handler runs. Handlers become context-aware reducers rather than imperative services.

Coeffect map shape:
```clojure
{:db ...       ; current runtime state
 :now ...      ; wall clock
 :config ...   ; system config
 :runtime ...  ; Integrant system ref
 :event ...    ; the triggering event
}
```

Future coeffects (later phases): retrieved memories, active tasks, identity/personality context, scheduler state.

- [ ] Define coeffect map schema (`{:db :now :config :runtime :event}`)
- [ ] Implement injector for each key: `:db` (deref state atom), `:now` (wall clock), `:config` (system config map), `:runtime` (Integrant system ref), `:event` (the triggering event)
- [ ] Expose `inject-coeffects` as a composable function: takes the raw event + system context, returns the enriched coeffect map
- [ ] Wire coeffect injection into the dispatch pipeline: enrich context before handler runs (this wiring happens in the interceptor chain in Group 5; document the connection point here)
- [ ] Enforce handler contract: handlers receive coeffect map and return effects map — no direct side effects inside handlers; document this as a code convention and verify with a linting/grep step
- [ ] **Tests:** unit tests for each injector with fixture inputs; assert coeffect map contains all five keys with correct values

### Group 3 — Effect system

Handlers return a declarative effects map. Effects represent intended operations, not immediate execution. The runtime owns execution.

Example effects map returned by a handler:
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

Effect vocabulary for Phase 1:

**Runtime effects:**
```clojure
:state          ; transition runtime state
:dispatch       ; enqueue a new event immediately
:dispatch-later ; schedule a delayed event dispatch
```

**Observability effects:**
```clojure
:log/info  ; structured log entry via Timbre
:trace     ; record a trace entry in the runtime trace log
:tap       ; emit a value via tap> for Portal inspection
```

**Persistence effects (stub only — implemented in Phase 2):**
```clojure
:event/store  ; defined in the registry; no-op implementation this phase
```

The effect execution registry:
```clojure
(defmulti execute-effect (fn [effect-type params ctx] effect-type))
```

Distinguish pure/internal effects from external/non-deterministic ones — this distinction matters for replay, testing, and deterministic debugging:
- **Pure/internal:** `:state`, `:dispatch` — no I/O, deterministic, safe to replay
- **External/non-deterministic:** HTTP requests, filesystem access, API calls (later phases)

Effect execution must remain observable, traceable, replaceable, and testable.

- [ ] Define effect descriptor schema: map keyed by effect type, values are params; document each type inline
- [ ] Implement `execute-effect` multimethod skeleton — default method logs unknown effect type as a warning and no-ops
- [ ] Annotate the registry with the pure/internal vs. external/non-deterministic distinction (comment or metadata)
- [ ] Implement `:state` effect — applies a transition function to the in-memory state atom
- [ ] Implement `:dispatch` effect — enqueues a new event onto the core.async event bus channel
- [ ] Implement `:dispatch-later` effect — schedules a delayed dispatch using a `core.async` timeout channel
- [ ] Implement `:log/info` effect — writes a structured log entry via Timbre
- [ ] Implement `:trace` effect — records a trace entry map in the runtime trace log (atom or channel)
- [ ] Implement `:tap` effect — emits a value via `tap>` for Portal inspection
- [ ] Add `:event/store` to the registry as a defined-but-not-implemented stub (no-op with a log warning); this makes Phase 2 a drop-in
- [ ] Implement effect executor: iterates the effects map returned by a handler, calls `execute-effect` for each key
- [ ] Wire effect executor as an Integrant component alongside the dispatcher
- [ ] **Tests:** unit test each effect method in isolation (`:state` transitions atom correctly, `:dispatch` enqueues, `:dispatch-later` fires after delay, `:log/info` calls Timbre, `:tap` emits, `:trace` records); test unknown effect type no-ops and logs warning; test `:event/store` stub no-ops

### Group 4 — Runtime state

Runtime state changes only through the `:state` effect. Direct `swap!` or `reset!` outside the executor is prohibited — this enforces replayability, observability, and auditability.

Initial state (intentionally small — this is operational state, not long-term memory):
```clojure
{:conversation  []
 :tasks         {}
 :events/recent []
 :ui            {}}
```

- [ ] Define the initial runtime state map with the shape above
- [ ] Enforce state transition discipline: no `swap!` or `reset!` outside `pa.runtime.executor`; add a comment marking the permitted location
- [ ] Accumulate each dispatched event into `:events/recent` as part of the dispatch pipeline (before handler runs, not inside handlers)
- [ ] **Tests:** assert state only changes via `:state` effect; assert `:events/recent` grows by one entry per dispatched event; assert initial state shape matches spec

### Group 5 — Interceptor chain

The interceptor chain wraps every dispatch. No event bypasses it. Potential interceptor responsibilities: tracing, logging, metrics, validation, timing, safety checks, effect auditing.

Chain order:
```text
event
 -> tracing interceptor
 -> coeffect injection
 -> handler
 -> effect validation
 -> effect tracing
 -> effect execution
```

Interceptor protocol: a map of `{:before fn :after fn}`. The chain runner applies `:before` fns in order, then `:after` fns in reverse (re-frame style).

- [ ] Design interceptor protocol: `{:before fn :after fn}` map (or record); document the context map shape that flows through the chain
- [ ] Implement chain runner: takes a chain vector and a context map, applies `:before` fns in order then `:after` fns in reverse, returns the final context map
- [ ] Implement the tracing interceptor: records event entry and exit times into the trace log
- [ ] Implement the coeffect injection interceptor: calls `inject-coeffects` and merges result into context (this replaces the manual wiring noted in Group 2)
- [ ] Implement the effect validation interceptor: asserts the effects map returned by handler has only known effect types; logs unknown keys before execution
- [ ] Implement the effect tracing interceptor: records which effects were executed and their params
- [ ] Wire the standard chain: tracing → coeffect injection → handler invocation → effect validation → effect tracing → effect execution
- [ ] Ensure all dispatch flows through the chain — dispatcher calls chain runner, not handler directly
- [ ] **Tests:** unit test chain runner with fixture interceptors (assert `:before` order, `:after` reverse order); assert a throwing interceptor short-circuits without corrupting state; integration test: dispatch an event end-to-end through the chain and assert state updated correctly

### Group 6 — Replayability foundations

The architecture must support reconstructing runtime state from initial state + event history. This enables deterministic debugging, event replay, cognition inspection, and runtime tracing. Replayability is a core architectural goal.

- [ ] Expose runtime state via `tap>` on every state transition (emit from the `:state` effect executor or an interceptor after-hook)
- [ ] Write `replay` function: takes initial state + event sequence → re-runs each event through the dispatch pipeline (bypassing external/non-deterministic effects) → returns final state
- [ ] Confirm `replay` produces deterministic output: same input sequence always yields the same final state
- [ ] **Tests:** replay test — define fixture event sequence, call `replay`, assert reconstructed state matches expected; call a second time and assert identical result (idempotency); property-based test: any valid event sequence replayed twice yields the same state

### Group 7 — UI boundary

The UI must remain a thin runtime client. The UI dispatches events and subscribes to runtime state. It must not directly mutate state, execute tools, or call cognition logic. This separation is a core architectural constraint.

- [ ] Audit charm.clj: remove any direct state mutation, atom derefs outside a designated read path, or cognition calls
- [ ] Expose a clean read API for the UI: a `subscribe` fn or a deref wrapper that returns the current state snapshot
- [ ] Confirm charm.clj only calls `dispatch!` (write) and the read API (read) — no other runtime coupling
- [ ] **Tests:** grep check — no `swap!` or `reset!` in `pa.ui.*` or charm.clj namespaces; integration smoke test: dispatch a `:user/message` event from the UI code path and assert it appears in `:events/recent` and state

---

## Notes

- **Critical path:** Groups 1–4 must be end-to-end before Group 5 can wrap them. Complete and test each group before moving to the next.
- **Group 5 (interceptors)** replaces the manual pipeline wiring from Groups 1–4; after it's in, the coeffect injection noted in Group 2 is done via the interceptor, not a direct call.
- **Group 6 (replayability)** depends on Groups 4 and 5 — `replay` needs the full chain and `:events/recent` accumulation to be in place.
- **Group 7 (UI boundary)** can proceed in parallel with Group 6 once Groups 1–4 are stable.
- **REPL demo (definition of done):** after all groups are complete, run a live REPL session — dispatch an event, observe the state transition and Portal tap> output, then call `replay` over `:events/recent` and confirm the reconstructed state matches the live atom.
