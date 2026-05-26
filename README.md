# Personal Cognitive Assistant CLI — Project Vision & Roadmap

## Developer Setup

### Requirements

- **Java 21 LTS or newer** — check with `java -version`
- **Clojure CLI 1.12.x or newer** — check with `clojure --version`
- Install Clojure CLI: https://clojure.org/guides/install_clojure

### REPL (nREPL on port 7888)

Start the REPL with the `:dev` alias:

```sh
clojure -M:dev
```

Connect your editor:
- **VS Code / Calva**: `Calva: Connect to a Running REPL Server` → host `localhost`, port `7888`
- **Emacs / CIDER**: `M-x cider-connect` → host `localhost`, port `7888`

Once connected, `dev/user.clj` is auto-loaded. Use these helpers:

```clojure
(start)   ; initialize and start all Integrant components
(stop)    ; halt all components cleanly
(reset)   ; stop + reload namespaces + start (no JVM restart needed)
(system)  ; inspect the live system map
```

### Running Tests

```sh
clojure -M:test
```

---

## Initial project idea

Implementation: a terminal application with access to an LLM via API key.

The assistant should:

- have a personality — name, gender, character/personality traits
- understand who you are, accumulate information about you, and use it in responses
- remember what you said and use it in future replies
- gradually forget information that is not particularly useful
- have persistent memory — when the application restarts, it should “recall” past sessions
- be able to search the internet, open web pages, and read YouTube transcripts
- safely read/write/edit files
- handle scheduled tasks: checks, reminders, etc.

Recommended architecture for memory, skills, and identity:

- `SOUL.md` — who I am, how I behave (the agent)
- `IDENTITY.md` — the agent’s name, gender, vibe/personality (can be merged with SOUL.md)
- `USER.md` — what the agent knows about the user (name, time zone, language, preferences)
- `AGENTS.md` — behavior rules, how to work with files, limitations, etc.

Memory:

- `MEMORY.md` — long-term memory, “wisdom” — key facts, decisions, lessons
- `memory/` — folder with daily notes (memory/2026-04-30.md) as well as topics the agent decided to remember

System:

- `HEARTBEAT.md` — checklist for periodic checks, cron jobs, etc.
- `TOOLS.md` — cheat sheet for your infrastructure: computer/device info, cameras, how to synthesize voice, ffmpeg paths, etc.

Tests:

Think through a system for validating/testing the entire system.

## Project Vision

Build a local-first personal cognitive assistant as a terminal application in Clojure.

The project is not intended to be:

- a chatbot wrapper
- a prompt engineering playground
- an “autonomous AI agent”
- a collection of magical abstractions

Instead, the goal is to build:

- a long-running cognitive runtime
- an inspectable assistant operating system
- a persistent personal daemon with memory
- a system with explicit architecture and observable behavior

The assistant should:

- maintain persistent memory
- develop an evolving understanding of the user
- retrieve and consolidate relevant information
- safely interact with the local machine
- execute tools and scheduled tasks
- expose its cognition and state transparently
- remain maintainable as a software system

The project prioritizes:

- architecture quality
- observability
- explicit orchestration
- inspectable state
- replayability
- maintainability
- deterministic runtime behavior

over:

- fake autonomy
- opaque agent loops
- framework-heavy abstractions
- “LLM magic”

---

## Core Functional Goals

The assistant should eventually:

### Identity & Personality

- have a consistent identity
- maintain personality traits and behavioral rules
- have a name, vibe, communication style, and long-term continuity

### Persistent User Understanding

- accumulate information about the user over time
- remember conversations, preferences, projects, and habits
- use remembered context in future responses
- maintain persistent memory between sessions

### Memory Evolution

- consolidate information into long-term memory
- summarize and distill important information
- forget or decay less useful memories over time
- avoid unbounded context growth

### Tool Usage

- search the internet
- retrieve and parse web pages
- read YouTube transcripts
- safely read/write/edit files
- execute scheduled tasks and reminders

### Runtime Behavior

- support long-running execution
- support background cognition and maintenance tasks
- expose internal state and cognition pipelines
- remain observable and replayable

---

## Technical Direction

### Language & Runtime

Primary language:

- Clojure (JVM)

The project intentionally uses:

- regular JVM Clojure
  instead of:
- babashka

Reasoning:

- stronger architecture tooling
- mature JVM ecosystem
- long-running runtime suitability
- unrestricted library compatibility
- REPL-driven development workflow

---

## Core Technology Stack

### UI

- charm.clj

The terminal UI acts as:

- a runtime client
- an observability interface
- a cognition debugger

not:

- the assistant itself

---

### System Architecture

- Integrant
- explicit component boundaries
- explicit lifecycle management

---

### Concurrency & Runtime

- core.async
- event-oriented runtime model

---

### Storage

- Markdown
- EDN
- SQLite

Storage should remain:

- human-readable
- inspectable
- editable
- portable

---

### Networking & APIs

- hato or http-kit
- OpenAI/Anthropic APIs initially
- local model support later

---

### Observability

- Timbre logging
- Portal
- tap>
- structured event tracing

---

## Architectural Principles

### Clear Layer Separation

The system should maintain strong boundaries between:

1. UI layer
2. Runtime/orchestration layer
3. Cognition layer
4. Memory layer
5. Tool layer

The UI should:

- dispatch events
- render runtime state

The cognition system should:

- assemble context
- retrieve memories
- plan actions
- produce declarative effects

The runtime should:

- execute effects
- coordinate components
- maintain lifecycle and scheduling

---

### Event-Driven Design

The system should operate primarily through events.

Examples:

- user messages
- assistant responses
- memory creation
- tool execution
- scheduled tasks
- reflections
- summarization jobs

This enables:

- replayability
- debugging
- cognition tracing
- observability
- deterministic orchestration

---

### Declarative Effects

Cognition should avoid directly mutating the world.

Instead, cognition should produce effects such as:

- tool execution requests
- memory writes
- notifications
- scheduling requests

The runtime executes those effects explicitly.

This improves:

- safety
- testability
- replayability
- observability

---

## Memory Philosophy

The project does NOT treat memory as:

- raw chat history stored forever

Instead, memory should evolve into:

- extracted facts
- episodic memory
- semantic memory
- reflections
- summaries
- distilled long-term knowledge

The architecture should support:

- memory consolidation
- summarization
- retrieval
- relevance decay
- selective forgetting

---

## Storage Layout

Planned persistent storage layout:

```text
assistant-data/
  identity/
    soul.md
    identity.md
    user.md
    agents.md

  memory/
    inbox/
    daily/
    semantic/
    episodic/
    summaries/

  cognition/
    reflections/
    plans/
    decisions/

  tasks/
    active/
    scheduled/
    completed/

  system/
    heartbeat.md
    tools.md
```

---

## Development Philosophy

The project intentionally prioritizes:

- explicit systems
- composable pipelines
- inspectable data
- simple abstractions
- incremental evolution
- deterministic behavior

The project intentionally avoids:

- giant AI frameworks
- opaque orchestration
- hidden prompt chains
- premature autonomy
- recursive agent loops
- unnecessary complexity

The goal is to evolve the system gradually from:

- runtime infrastructure
  toward:
- higher-level cognition

rather than attempting to build a fully autonomous agent immediately.

---

## Incremental Roadmap

### Phase 0 — Foundation & Boilerplate

Goal:
Establish development ergonomics and architectural skeleton.

Deliverables:

- repository structure
- Integrant system
- logging
- REPL workflow
- Portal integration
- basic charm.clj application shell
- event bus foundation

No AI functionality yet.

---

### Phase 1 — Runtime & State Model

Goal:
Build the core event-driven runtime.

Deliverables:

- runtime state model
- event processing pipeline
- effect system
- state transitions
- event persistence
- replay/debugging foundations

Still no sophisticated AI behavior.

---

### Phase 2 — Persistent Storage & Memory Foundation

Goal:
Create durable inspectable storage.

Deliverables:

- assistant-data structure
- markdown memory storage
- SQLite indexing
- memory protocols/interfaces
- identity loading
- structured persistence

---

### Phase 3 — LLM Integration

Goal:
Introduce controlled LLM interaction.

Deliverables:

- LLM abstraction layer
- provider implementations
- streaming responses
- prompt assembly pipeline
- conversation event integration

The LLM should not yet directly execute tools or mutate state.

---

### Phase 4 — Tool System

Goal:
Create deterministic and observable tool execution.

Deliverables:

- tool registry
- filesystem tools
- search integration
- webpage retrieval
- YouTube transcript support
- safe execution model
- dry-run support
- logging/tracing

---

### Phase 5 — Memory Retrieval

Goal:
Make the assistant context-aware.

Deliverables:

- retrieval pipeline
- semantic retrieval
- episodic retrieval
- embeddings integration
- memory extraction
- context assembly system

Focus on:

- distilled memory
  not:
- raw conversation accumulation

---

### Phase 6 — Scheduling & Background Cognition

Goal:
Introduce time-based behavior.

Deliverables:

- reminders
- recurring tasks
- reflection jobs
- memory consolidation
- periodic cognition tasks
- HEARTBEAT.md integration

Background work should operate through events.

---

### Phase 7 — Explicit Cognitive Pipeline

Goal:
Formalize cognition stages.

Pipeline stages:

- interpretation
- retrieval
- planning
- tool selection
- response generation
- memory extraction
- consolidation

Each stage should remain:

- inspectable
- testable
- observable

---

### Phase 8 — Personality & Long-Term Evolution

Goal:
Evolve the assistant into a persistent long-term system.

Deliverables:

- stable personality modeling
- user model evolution
- memory decay
- summarization pipelines
- reflection systems
- long-term relationship continuity

---

### Phase 9 — Optional Advanced Features

Potential future directions:

- local models
- voice input/output
- web UI
- mobile interfaces
- autonomous task execution
- graph memory
- semantic planning
- multi-agent experimentation

These are explicitly secondary to:

- runtime quality
- memory quality
- architecture quality
- observability
- maintainability
