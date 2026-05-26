# Phase 0 — Foundation & Boilerplate: Plan

## Group 1 — Project Init

- [ ] 1. Create `deps.edn` with initial dependencies: Integrant, Timbre, core.async, Portal
- [ ] 2. Define namespace layout: `pa.core`, `pa.system`, `pa.runtime`, `pa.dev` (and any additional stubs)
- [ ] 3. Create directory structure: `src/`, `dev/`, `test/`, `resources/`

## Group 2 — System Wiring

- [ ] 4. Wire Integrant system map in `pa.system` with placeholder components (no real logic yet)
- [ ] 5. Add Timbre logging with a basic console appender wired as an Integrant component
- [ ] 6. Set up Portal integration and `tap>` plumbing — confirm values appear in Portal at REPL
- [ ] 7. Expose runtime state via `tap>` so Portal reflects live system state

## Group 3 — Dev Ergonomics

- [ ] 8. Add `dev/user.clj` with REPL helpers: `start`, `stop`, `reset` (wrapping Integrant lifecycle)
- [ ] 9. Confirm system starts, stops, and resets cleanly from the REPL without leaking state
- [ ] 10. Verify nREPL is reachable from the configured editor (see requirements.md for version pins)

## Group 4 — Terminal UI Stub

- [ ] 11. Add charm.clj dependency (version pinned per requirements.md)
- [ ] 12. Render a static "hello" terminal frame — no interaction, just a boot-time render confirming TUI works
- [ ] 13. Confirm the frame renders on `start` and is cleaned up on `stop`

## Group 5 — Event Bus Stub

- [ ] 14. Create a minimal event bus: a core.async channel with a dispatcher stub
- [ ] 15. Wire the event bus as an Integrant component (starts a consumer loop, halts cleanly)
- [ ] 16. Confirm channel opens and closes without error; dispatcher stub logs received events via Timbre

## Group 6 — Smoke Tests

- [ ] 17. Write smoke test: system starts — all Integrant components initialize without error
- [ ] 18. Write smoke test: system halts — all components halt without error or hanging threads
- [ ] 19. Write smoke test: `tap>` emits a value and Portal receives it (or assert via test sink)
- [ ] 20. Run full test suite; confirm all green before marking phase complete
