# Validation: Phase 1 — Runtime & State Model

## Definition of done

The phase is complete when all tests pass, a live REPL session demonstrates a full dispatch → coeffect injection → handler → state transition cycle, and a `replay` call over `:events/recent` reconstructs the expected final state. The charm.clj UI must dispatch events through the runtime without directly mutating state or calling cognition logic. No LLM behavior is required or expected.

## Checklist

### Tests

- [ ] Unit tests: `make-event` produces a map with `:event/type`, `:id` (uuid), `:timestamp` (inst)
- [ ] Unit tests: dispatcher routes to correct handler by `:event/type`; unknown event type no-ops without throwing
- [ ] Unit tests: coeffect injector produces all five keys (`{:db :now :config :runtime :event}`) with correct values from fixtures
- [ ] Unit tests: `:state` effect applies transition to runtime state atom correctly
- [ ] Unit tests: `:dispatch` effect enqueues event onto the bus channel
- [ ] Unit tests: `:dispatch-later` schedules a delayed dispatch (verify with a short timeout in tests)
- [ ] Unit tests: `:log/info`, `:trace`, `:tap` effects each invoke the correct underlying mechanism
- [ ] Unit tests: unknown effect type no-ops and logs a warning
- [ ] Unit tests: interceptor chain runner applies `:before` fns in order and `:after` fns in reverse
- [ ] Unit tests: broken/throwing interceptor short-circuits cleanly without corrupting state
- [ ] Unit tests: state only changes via `:state` effect; direct `swap!` is not present outside the executor
- [ ] Unit tests: `:events/recent` grows by one entry per dispatched event
- [ ] Property-based tests (test.check): any valid event schema input → `make-event` always produces a well-formed event map
- [ ] Property-based tests (test.check): effect descriptor maps with arbitrary valid keys do not throw in the executor
- [ ] Replay test: define a fixture event sequence → call `replay` → assert final state matches expected; call a second time → assert identical result (idempotent)

### Behaviors

- [ ] REPL: `(dispatch! {:event/type :user/message :text "hello"})` → state atom's `:conversation` grows by one entry
- [ ] REPL: `(tap>` call from a `:tap` effect is visible in Portal without error
- [ ] REPL: `(replay initial-state (get-in @runtime-state [:events/recent]))` returns the same state as the live atom
- [ ] REPL: Integrant system `start` → `stop` → `start` cycle completes without error and without leaking channels or threads

### Integration

- [ ] charm.clj dispatches a `:user/message` event through `dispatch!` and the event appears in `:events/recent`
- [ ] charm.clj reads runtime state only through the designated read API (`subscribe` or deref wrapper) — no other atom access
- [ ] No `swap!` or `reset!` in `pa.ui.*` or `charm.clj` namespaces (grep check)
- [ ] All Integrant components (dispatcher, effect executor) initialize and halt cleanly in the smoke test suite carried over from Phase 0

## Merge criteria

All of the following must be true before this branch is merged:

1. All tests in the checklist above pass on a clean `clj -T:test` run
2. The REPL demo behaviors above have been manually verified in a live session
3. `grep -r 'swap!\|reset!' src/` returns no results outside `pa.runtime.executor` (the one permitted location)
4. Integrant start/stop smoke tests pass (Phase 0 regression)
5. Portal is reachable and shows tap> output during the REPL demo
