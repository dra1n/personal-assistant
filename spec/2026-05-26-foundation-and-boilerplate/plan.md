# Phase 0 — Foundation & Boilerplate: Plan

## Group 1 — Project Init

- [x] 1. Create `deps.edn` with initial dependencies: Integrant, Timbre, core.async, Portal
- [x] 2. Define namespace layout: `pa.core`, `pa.system`, `pa.runtime`, `pa.dev` (and any additional stubs)
- [x] 3. Create directory structure: `src/`, `dev/`, `test/`, `resources/`

## Group 2 — System Wiring

- [x] 4. Wire Integrant system map in `pa.system` with placeholder components (no real logic yet)
- [x] 5. Add Timbre logging with a basic console appender wired as an Integrant component
- [x] 6. Set up Portal integration and `tap>` plumbing — confirm values appear in Portal at REPL
- [x] 7. Expose runtime state via `tap>` so Portal reflects live system state

## Group 3 — Dev Ergonomics

- [x] 8. Add `dev/user.clj` with REPL helpers: `start`, `stop`, `reset` (wrapping Integrant lifecycle)
- [x] 9. Confirm system starts, stops, and resets cleanly from the REPL without leaking state
  - Start server: `clojure -M:dev:nrepl` — then connect editor or open a second terminal with `clojure -M:dev -e "(require 'user)" -r`
  - `(start)` / `(stop)` / `(reset)` all work cleanly via `integrant.repl`
  - Portal opens in the browser (no-arg `portal/open`); VS Code launcher requires a running extension and should not be used
- [x] 10. Verify nREPL is reachable from the configured editor (see requirements.md for version pins)

## Group 4 — Terminal UI Stub

- [x] 11. Add charm.clj dependency (version pinned per requirements.md)
- [x] 12. Render a static "hello" terminal frame — no interaction, just a boot-time render confirming TUI works
- [x] 13. Confirm the frame renders on `start` and is cleaned up on `stop`

## Group 5 — Event Bus Stub

- [x] 14. Create a minimal event bus: a core.async channel with a dispatcher stub
- [x] 15. Wire the event bus as an Integrant component (starts a consumer loop, halts cleanly)
- [x] 16. Confirm channel opens and closes without error; dispatcher stub logs received events via Timbre

## Group 6 — Smoke Tests

- [x] 17. Write smoke test: system starts — all Integrant components initialize without error
- [x] 18. Write smoke test: system halts — all components halt without error or hanging threads
- [x] 19. Write smoke test: `tap>` emits a value and Portal receives it (or assert via test sink)
- [x] 20. Run full test suite; confirm all green before marking phase complete
