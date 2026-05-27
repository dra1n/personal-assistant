# Personal Cognitive Assistant CLI — Project Vision & Roadmap

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

#### Goal

Build the foundational event-driven cognitive runtime.

This phase establishes:

* runtime orchestration
* deterministic event processing
* effect execution
* coeffect injection
* observability foundations
* replay/debugging capabilities

This is primarily:

* runtime engineering
  not:
* AI engineering

No sophisticated cognition yet.

Avoid:

* autonomous loops
* complex memory retrieval
* embeddings
* agent frameworks
* direct tool execution from handlers

---

#### Core Architectural Model

The runtime should follow a re-frame-inspired architecture:

```text
event
  -> coeffect injection
  -> event handler
  -> declarative effects
  -> runtime effect execution
```

Handlers should:

* receive contextual inputs through coeffects
* return declarative effects
* avoid direct side effects
* remain mostly deterministic and testable

The runtime executes effects explicitly.

---

#### Runtime Flow

Conceptual runtime loop:

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

---

#### Event Model

Events represent:

* immutable facts
* things that already happened

Examples:

```clojure
{:event/type :user/message
 :text "hello"}
```

```clojure
{:event/type :scheduler/tick}
```

```clojure
{:event/type :memory/stored}
```

Events should:

* be immutable
* serializable
* persistable
* traceable

---

#### Coeffects

Handlers should not directly fetch runtime dependencies.

Instead, runtime context should be injected through coeffects.

Possible coeffects:

```clojure
{:db ...
 :now ...
 :config ...
 :runtime ...
 :event ...
}
```

Future coeffects may include:

* retrieved memories
* active tasks
* identity/personality context
* scheduler state

Handlers become:

* context-aware reducers
  rather than:
* imperative services

---

#### Effect System

Handlers return declarative effects maps.

Example:

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

Effects represent:

* intended operations
  not:
* immediate execution

The runtime owns effect execution.

---

#### State Management

Runtime state should only change through effects.

Avoid:

* direct atom mutation
* hidden swap!
* arbitrary side effects inside handlers

The `:state` effect becomes the canonical state transition mechanism.

This improves:

* replayability
* observability
* debugging
* auditability

---

#### Initial Effect Vocabulary

##### Runtime Effects

```clojure
:state
:dispatch
:dispatch-later
```

##### Observability Effects

```clojure
:log/info
:trace
:tap
```

##### Persistence Effects

```clojure
:event/store
```

Additional effect types will be added in later phases:

* HTTP
* tools
* memory persistence
* scheduling
* embeddings
* notifications

---

#### Effect Execution Layer

The runtime should maintain an effect execution registry.

Example conceptual model:

```clojure
(defmulti execute-effect ...)
```

Effect execution should remain:

* observable
* traceable
* replaceable
* testable

The runtime should distinguish between:

##### Pure/Internal Effects

Examples:

* `:state`
* `:dispatch`

and:

##### External/Non-Deterministic Effects

Examples:

* HTTP requests
* filesystem access
* API calls

This distinction becomes important for:

* replay
* testing
* deterministic debugging

---

#### Event Persistence

Important events should be persisted.

Initial persistence goals:

* replayability
* debugging
* cognition tracing
* crash investigation

SQLite may initially store:

* event history
* runtime traces
* task execution state

---

#### Replayability

The architecture should support reconstructing runtime behavior from:

* initial state
* event history

This enables:

* deterministic debugging
* event replay
* cognition inspection
* runtime tracing

Replayability is a core architectural goal.

---

#### Interceptors & Middleware

The runtime should eventually support interceptor-style processing similar to re-frame.

Potential interceptor responsibilities:

* tracing
* logging
* metrics
* validation
* timing
* safety checks
* effect auditing

Conceptually:

```text
event
 -> tracing interceptor
 -> coeffect injection
 -> handler
 -> effect validation
 -> effect tracing
 -> effect execution
```

---

#### Runtime State Model

Initial runtime state should remain intentionally small.

Example:

```clojure
{:conversation []
 :tasks {}
 :events/recent []
 :ui {}
}
```

This is:

* runtime operational state
  not:
* long-term assistant memory

Long-term memory is introduced in later phases.

---

#### UI Boundary

The UI must remain a thin runtime client.

The UI:

* dispatches events
* subscribes to runtime state

The UI must not:

* directly mutate state
* execute tools
* call cognition logic

This separation is a core architectural constraint.

---

#### Deliverables

By the end of Phase 1, the system should support:

* runtime state model
* event dispatching
* coeffect injection
* effect execution
* declarative state transitions
* event persistence
* structured tracing
* replay/debugging foundations
* observable runtime behavior
* thin UI integration

No advanced AI behavior is required yet.

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
