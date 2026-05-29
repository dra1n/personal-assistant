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

- [x] Define event schema: EDN map with `:event/type` (namespaced keyword), `:id` (uuid), `:timestamp` (inst), and top-level payload keys
- [x] Write a `make-event` constructor that stamps `:id` and `:timestamp` automatically
- [x] Implement event dispatcher: a registry (atom of `{event-type → handler-fn}`) plus a `dispatch!` entry point that routes by `:event/type`
- [x] Wire dispatcher as an Integrant component (start: initialize registry; halt: drain and close)
- [x] **Tests:** unit tests for `make-event` shape (all required keys present, correct types); unit tests for dispatch routing (correct handler called, unknown `:event/type` no-ops without throwing)

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

- [x] Define coeffect map schema (`{:db :now :config :runtime :event}`)
- [x] Implement injector for each key: `:db` (deref state atom), `:now` (wall clock), `:config` (system config map), `:runtime` (Integrant system ref), `:event` (the triggering event)
- [x] Expose `inject-coeffects` as a composable function: takes the raw event + system context, returns the enriched coeffect map
- [x] Wire coeffect injection into the dispatch pipeline: enrich context before handler runs (this wiring happens in the interceptor chain in Group 5; document the connection point here)
- [x] Enforce handler contract: handlers receive coeffect map and return effects map — no direct side effects inside handlers; document this as a code convention and verify with a linting/grep step
- [x] **Tests:** unit tests for each injector with fixture inputs; assert coeffect map contains all five keys with correct values

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

- [x] Define effect descriptor schema: map keyed by effect type, values are params; document each type inline
- [x] Implement `execute-effect` multimethod skeleton — default method logs unknown effect type as a warning and no-ops
- [x] Annotate the registry with the pure/internal vs. external/non-deterministic distinction (comment or metadata)
- [x] Implement `:db` effect — resets runtime state atom to the new value (renamed from `:state` for symmetry with `:db` coeffect)
- [x] Implement `:dispatch` effect — enqueues a new event onto the core.async event bus channel
- [x] Implement `:dispatch-later` effect — schedules a delayed dispatch using a `core.async` timeout channel
- [x] Implement `:log/info` effect — writes a structured log entry via Timbre
- [x] Implement `:trace` effect — records a trace entry map in the runtime trace log (atom or channel)
- [x] Implement `:tap` effect — emits a value via `tap>` for Portal inspection
- [x] Add `:event/store` to the registry as a defined-but-not-implemented stub (no-op with a log warning); this makes Phase 2 a drop-in
- [x] Implement effect executor: iterates the effects map returned by a handler, calls `execute-effect` for each key
- [x] Wire effect executor into the dispatcher (no separate Integrant component needed — executor is stateless)
- [x] **Tests:** unit test each effect method in isolation (`:db` transitions atom correctly, `:dispatch` enqueues, `:dispatch-later` fires after delay, `:log/info` calls Timbre, `:tap` emits, `:trace` records); test unknown effect type no-ops and logs warning; test `:event/store` stub no-ops

### Group 4 — Runtime state

Runtime state changes only through the `:state` effect. Direct `swap!` or `reset!` outside the executor is prohibited — this enforces replayability, observability, and auditability.

Initial state (intentionally small — this is operational state, not long-term memory):
```clojure
{:conversation  []
 :tasks         {}
 :events/recent []
 :ui            {}}
```

- [x] Define the initial runtime state map with the shape above
- [x] Enforce state transition discipline: two permitted mutation sites documented — `:db` effect executor and `:events/recent` accumulation in dispatcher; all other code reads via `current-db`
- [x] Accumulate each dispatched event into `:events/recent` as part of the dispatch pipeline (before handler runs, not inside handlers)
- [x] **Tests:** assert state only changes via `:db` effect; assert `:events/recent` grows by one entry per dispatched event; assert initial state shape matches spec

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

- [x] Design interceptor protocol: `{:before fn :after fn}` map (or record); document the context map shape that flows through the chain
- [x] Implement chain runner: takes a chain vector and a context map, applies `:before` fns in order then `:after` fns in reverse, returns the final context map
- [x] Implement the tracing interceptor: records event entry and exit times into the trace log
- [x] Implement the coeffect injection interceptor: calls `inject-coeffects` and merges result into context (this replaces the manual wiring noted in Group 2)
- [x] Implement the effect validation interceptor: asserts the effects map returned by handler has only known effect types; logs unknown keys before execution
- [x] Implement the effect tracing interceptor: records which effects were executed and their params
- [x] Wire the standard chain: tracing → coeffect injection → handler invocation → effect validation → effect tracing → effect execution
- [x] Ensure all dispatch flows through the chain — dispatcher calls chain runner, not handler directly
- [x] **Tests:** unit test chain runner with fixture interceptors (assert `:before` order, `:after` reverse order); assert a throwing interceptor short-circuits without corrupting state; integration test: dispatch an event end-to-end through the chain and assert state updated correctly

### Group 6 — Replayability foundations

The architecture must support reconstructing runtime state from initial state + event history. This enables deterministic debugging, event replay, cognition inspection, and runtime tracing. Replayability is a core architectural goal.

- [x] Expose runtime state via `tap>` on every state transition (via `db-tap-interceptor` at the front of the standard chain — its `:after` fires last, after effect execution, so the emitted snapshot reflects the new state)
- [x] Write `replay` function: takes initial state + event sequence → re-runs each event through the dispatch pipeline (bypassing external/non-deterministic effects) → returns final state
- [x] Confirm `replay` produces deterministic output: same input sequence always yields the same final state
- [x] **Tests:** replay test — define fixture event sequence, call `replay`, assert reconstructed state matches expected; call a second time and assert identical result (idempotency); assert non-deterministic effects (:dispatch, :log/info, :tap) are skipped during replay

### Group 7 — UI boundary

Two distinct kinds of state exist in this system and must never be conflated:

- **UI state** — the Elm-style model managed by charm's init/update/view loop: input buffer, scroll position, focused panel. Evolves only through charm messages handled in `update`. Never touches `state/db`.
- **Runtime state** — durable operational state in `state/db`, owned by the runtime. The UI reads it directly and mutates it only indirectly — by dispatching events.

`ui.clj` is a thin runtime client: it dispatches events (write path) and reads runtime state only through the query layer (read path). charm.clj is a third-party library and is not audited.

**Subscribe mechanism:** charm.clj does not expose its internal message channel — messages can only enter the loop via commands. Commands are async functions that run outside the loop and return a message back into it. The subscribe pattern therefore is:

1. At `init` time, create a `core.async` channel (`db-ch`) and register a tap sink that puts each `{:db/transition snapshot}` emitted by `db-tap-interceptor` onto it
2. Return a `watch-db` command from `init` (and re-schedule it from `update` on every `:runtime/db-updated` message) — the command parks on `db-ch` and returns `{:type :runtime/db-updated :db snapshot}` when a snapshot arrives
3. The `update` fn handles `:runtime/db-updated` by merging `:db` into the charm model and scheduling `watch-db` again

The charm model holds `{:db <latest-runtime-snapshot> :input "" :scroll 0 ...}`. The `view` fn reads from `(:db model)` via queries — no polling, no direct atom derefs in view code.

**Query layer:** a `pa.runtime.queries` namespace of pure selector functions `(fn [db] ...)`. The view calls `(queries/conversation db)` rather than `(get-in model [:db :conversation])`. State shape changes are contained to one namespace; any future consumer (REPL tooling, replay, webhooks) uses the same functions.

- [x] Define `pa.runtime.queries` namespace: pure selector functions for each top-level key (`conversation`, `tasks`, `recent-events`, `ui-prefs`); no knowledge of how values are stored
- [x] Implement the subscribe mechanism in `ui.clj`: tap sink → `db-ch` (sliding-buffer 1) → `watch-db-cmd` parks on channel and returns `{:type :runtime/db-updated :db snapshot}`; `update` merges snapshot and reschedules the command
- [x] Audit `pa.ui.*`: no `swap!` or `reset!` on `state/db`; view reads runtime state only via `pa.runtime.queries`; only write path is `dispatch!`
- [x] **Tests:** unit test each query fn with fixture db maps; grep check — no `swap!`/`reset!` in `pa.ui.*`

---

## Notes

- **Critical path:** Groups 1–4 must be end-to-end before Group 5 can wrap them. Complete and test each group before moving to the next.
- **Group 5 (interceptors)** replaces the manual pipeline wiring from Groups 1–4; after it's in, the coeffect injection noted in Group 2 is done via the interceptor, not a direct call.
- **Group 6 (replayability)** depends on Groups 4 and 5 — `replay` needs the full chain and `:events/recent` accumulation to be in place.
- **Group 7 (UI boundary)** can proceed in parallel with Group 6 once Groups 1–4 are stable.
- **REPL demo (definition of done):** after all groups are complete, run a live REPL session — dispatch an event, observe the state transition and Portal tap> output, then call `replay` over `:events/recent` and confirm the reconstructed state matches the live atom.
