# Personal Assistant

A local-first personal cognitive assistant — a long-running terminal application in Clojure that accumulates knowledge about its user, maintains a persistent identity, and operates through an explicit, inspectable architecture.

See [PROJECT.md](PROJECT.md) for the full vision, architecture decisions, and roadmap.

---

## Requirements

- **Java 21+** — check with `java -version`
- **Clojure CLI 1.12+** — check with `clojure --version` · [install](https://clojure.org/guides/install_clojure)

---

## Running

Start the app:

```sh
clojure -M:run
```

Press `Ctrl+C` to quit.

---

### REPL (editor-driven development)

Start the nREPL server:

```sh
clojure -M:dev:nrepl
```

Then connect Calva (`Ctrl+Alt+C Ctrl+Alt+C`) or CIDER (`M-x cider-connect`) to `localhost:7888`.

For a terminal REPL prompt:

```sh
clojure -M:dev -e "(require 'user)" -r
```

Once in the REPL:

```clojure
(start)   ; start all components
(stop)    ; halt cleanly
(reset)   ; stop + reload + start
(system)  ; inspect live system map
```

---

## Tests

```sh
clojure -M:test
```

---

## Project Layout

```
src/pa/        — application source
dev/           — REPL helpers (user.clj)
test/          — smoke tests
spec/          — feature specs, plans, validation checklists
resources/     — static resources
deps.edn       — dependencies and aliases
.nrepl.edn     — nREPL port (7888)
```
