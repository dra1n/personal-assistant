# Phase 0 — Foundation & Boilerplate: Requirements

## Scope

Phase 0 establishes development ergonomics and the architectural skeleton. There is no AI functionality, no LLM calls, no memory system, and no tool execution. The output is a running system that starts and stops cleanly, exposes internal state via Portal, and supports productive REPL-driven development.

---

## In Scope

- `deps.edn` with pinned dependencies
- Namespace layout and directory structure
- Integrant system map with placeholder components
- Timbre logging wired as a component
- Portal + `tap>` plumbing
- `dev/user.clj` REPL helpers (`start`, `stop`, `reset`)
- charm.clj static terminal frame
- core.async event bus stub (channel + dispatcher stub)
- Smoke tests: system lifecycle and observable state

## Out of Scope

- Any LLM integration (Phase 3)
- Memory read/write (Phase 2)
- Tool execution (Phase 4)
- Real event routing logic (Phase 1)
- CI pipeline (explicitly deferred — see Decisions below)

---

## Decisions

### Clojure & JVM Version Pins

**Decision:** Pin exact minimum versions before writing `deps.edn`.

- Minimum Clojure version: **1.12.x** (latest stable; required for JVM interop improvements used by several dependencies)
- Minimum Java version: **Java 21 LTS** (required for virtual threads available to core.async in later phases; broadly available)
- Record both in `deps.edn` under `:clojure` and in a `README.md` developer setup section
- Every contributor machine and any future CI environment must meet these pins

**Why:** Drift between Clojure/JVM versions across machines causes subtle runtime differences. Pinning early prevents future debugging.

---

### REPL Tooling & Editor Integration

**Decision:** Target nREPL-based workflows (Calva on VS Code, CIDER on Emacs). Capture the config in this phase so the REPL workflow is reproducible from day one.

- Add `nrepl` and `cider-nrepl` middleware to `deps.edn` under a `:dev` alias
- Default nREPL port: **7888** (configured in `.nrepl.edn` at project root)
- `dev/user.clj` must be auto-loaded on REPL start (via `:dev` alias `extra-paths`)
- Document the editor setup steps (connect, evaluate buffer, run `(start)`) in a `README.md` dev section

**Why:** An undocumented REPL setup creates friction. The entire development model depends on fast REPL iteration; getting this right in Phase 0 avoids fixing it later.

---

### charm.clj Maturity Assessment

**Deferred from scope.** The tech-stack spec flags charm.clj as relatively young. This phase does use it for a static frame, but a formal go/no-go assessment (maintenance trajectory, issue backlog, alternatives like Lanterna or Tock) is not required until Phase 0 completes. If charm.clj proves problematic during the static frame implementation (Group 4 in plan.md), escalate before proceeding.

---

### Event Bus as an Integrant Component

**Decision:** The core.async event bus is a first-class Integrant component, not a bare channel created at startup.

- The component key (e.g. `:pa.runtime/event-bus`) holds the channel and manages the consumer loop
- `init-key` creates the channel and starts the dispatch loop; `halt-key` closes the channel and joins/stops the loop
- Other components that need to put events onto the bus declare it as an Integrant dependency — no global state or `defonce`
- In Phase 0 the dispatcher is a stub (logs received events via Timbre); real routing is Phase 1

**Why:** A bare channel at module level couples all consumers to a global and makes halt ordering unpredictable. Wrapping it in Integrant gives the bus a clean lifecycle, explicit dependency wiring, and REPL-reset correctness from the start.

---

### CI Pipeline

**Explicitly deferred.** No GitHub Actions or equivalent pipeline is required for Phase 0. Running `clojure -T:test` locally is sufficient. CI will be addressed when the project reaches a stable enough state to justify the setup cost.

---

## Constraints

- All storage must remain local (no external services, databases, or network calls in this phase)
- The system must be startable with a single `clojure` command from the project root
- No macros that obscure component wiring — Integrant config must be data-driven and readable
- All dependencies must be available via Clojars or Maven Central (no git-deps for core components)
