# Tech Stack

## Layer Diagram

```
┌─────────────────────────────────────────────────────────┐
│  UI Layer                                               │
│  charm.clj  ─  terminal client, observability surface  │
└───────────────────────┬─────────────────────────────────┘
                        │ events / rendered state
┌───────────────────────▼─────────────────────────────────┐
│  Runtime / Orchestration Layer                          │
│  Integrant  ─  component lifecycle                      │
│  core.async  ─  event bus, concurrency                  │
│  Timbre + tap> + Portal  ─  observability               │
└───────────────────────┬─────────────────────────────────┘
                        │ effect descriptors / queries
┌───────────────────────▼─────────────────────────────────┐
│  Cognition Layer                                        │
│  Clojure (JVM)  ─  pipeline stages: interpret →        │
│  retrieve → plan → tool select → respond → extract     │
│  LLM abstraction  ─  hato/http-kit + OpenAI API        │
└──────────────┬────────────────────────┬─────────────────┘
               │ reads/writes           │ dispatches
┌──────────────▼──────────┐ ┌──────────▼─────────────────┐
│  Memory Layer           │ │  Tool Layer                 │
│  Markdown (human text)  │ │  Filesystem tools           │
│  EDN (structured data)  │ │  Web search                 │
│  SQLite (indexing)      │ │  Page retrieval             │
└─────────────────────────┘ │  YouTube transcripts        │
                            │  Scheduled task executor    │
                            └─────────────────────────────┘
```

---

## Stack Decisions

### Language & Runtime

| Choice | Rationale |
|---|---|
| **Clojure (JVM)** | Long-running runtime, mature ecosystem, REPL-driven development, unrestricted library access |

**Explicitly excluded:**
- *Babashka* — designed for scripting, not long-running processes; restricted library compatibility; weaker architecture tooling

---

### System Architecture

| Choice | Rationale |
|---|---|
| **Integrant** | Explicit component lifecycle and dependency graph; no implicit wiring or magic DI |

**Explicitly excluded:**
- *Mount / Component* — more implicit state management; Integrant's data-driven config better fits this system's inspectability values
- *AI agent frameworks (LangChain-style)* — opaque orchestration, hidden prompt chains, fight against the architecture goals

---

### UI

| Choice | Rationale |
|---|---|
| **charm.clj** | TUI library suited for terminal apps; acts as a runtime client and observability surface, not the assistant itself |

**Open question:** charm.clj is relatively young — maturity and maintenance trajectory should be reassessed before Phase 0 begins.

---

### Concurrency & Runtime

| Choice | Rationale |
|---|---|
| **core.async** | First-class event-driven model; channels map cleanly to the event bus; native to the Clojure ecosystem |

**Explicitly excluded:**
- *Raw Java threads / futures only* — insufficient for the event-bus model; less composable
- *Manifold / Aleph* — additional dependency with no clear advantage over core.async for this use case

---

### Storage

| Choice | Rationale |
|---|---|
| **Markdown** | Human-readable, editable by hand; identity and memory files are inspectable and portable |
| **EDN** | Structured Clojure-native data; configuration, task state, effect logs |
| **SQLite** | Indexing and querying memory; lightweight, embedded, no server required |

**Explicitly excluded:**
- *PostgreSQL / external databases* — operational overhead; contradicts local-first and portability goals
- *Vector databases (Pinecone, Weaviate)* — external services; local SQLite with embeddings is sufficient initially
- *Raw JSON* — EDN is strictly superior for Clojure-native structured data

---

### Networking & LLM APIs

| Choice | Rationale |
|---|---|
| **hato or http-kit** | Lightweight HTTP; hato is closer to the JVM HttpClient, http-kit has simpler async model — TBD |
| **OpenAI API (primary)** | GPT models; streaming support; well-documented |
| **Anthropic API (secondary)** | Fallback / alternative; abstracted behind an LLM protocol layer |

**Open question:** hato vs http-kit — evaluate streaming SSE support for both before committing in Phase 3.

**Explicitly excluded:**
- *LLM SDKs / wrappers* — add abstraction over an already-simple HTTP interface; prefer raw API calls through a thin project-owned abstraction
- *Local model inference (initially)* — deferred to Phase 9; no current tooling commitment

---

### Testing

| Choice | Rationale |
|---|---|
| **clojure.test** | Standard library test framework; no extra dependency; sufficient for unit and integration tests |
| **test.check** | Property-based testing for data-driven code — event schemas, memory records, effect descriptors |
| **with-redefs / protocols** | Mock external I/O (HTTP, filesystem) at protocol boundaries without a dedicated mocking library |

**Testing philosophy:**
- Every pipeline stage is a pure function (context map in → context map out) — unit test in isolation with fixture data
- Storage round-trips (Markdown writer + reader, SQLite write + query) are covered by integration tests
- Tools are tested with mocked HTTP/filesystem; dry-run mode enables effect verification without side effects
- Event replay is itself a correctness mechanism — tests can replay fixture logs and assert final state
- Aim for extensive coverage at the unit and integration level; avoid testing through the LLM boundary

**Explicitly excluded:**
- *Mocking the database in integration tests* — storage tests must hit real SQLite; mock/real divergence masks bugs
- *End-to-end LLM tests in CI* — LLM calls are non-deterministic and costly; test the prompt assembly pipeline, not the model output

---

### Observability

| Choice | Rationale |
|---|---|
| **Timbre** | De-facto Clojure logging library; structured, configurable |
| **tap> / Portal** | REPL-time data inspection; visualize live runtime state without extra infrastructure |
| **Structured event tracing** | Every event in the event bus is a data value — observable, storable, replayable |

**Explicitly excluded:**
- *OpenTelemetry / Jaeger* — too heavy for a local personal tool
- *println-based debugging* — replaced entirely by tap> + Portal during development
