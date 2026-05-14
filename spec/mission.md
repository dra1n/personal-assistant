# Mission

Build a local-first personal cognitive assistant as a long-running terminal application in Clojure — one that accumulates knowledge about its user over time, maintains a persistent identity, and operates through an explicit, inspectable architecture.

---

## What It Is

A **persistent personal daemon** — a cognitive runtime that lives alongside you, remembers you, and gradually becomes more useful the longer it runs.

A **personal operating system for cognition** — not a chatbot, but a system that manages memory, identity, context, tools, and scheduled behavior as first-class architectural concerns.

An **inspectable software system** — every decision, memory write, retrieval, and tool execution is observable, traceable, and replayable. The assistant never does anything hidden.

---

## What It Is Not

- Not a chatbot wrapper around an LLM API
- Not a prompt engineering sandbox
- Not an "autonomous AI agent" that self-directs
- Not a framework built for others to use
- Not a collection of magical abstractions hiding complexity

---

## Core Functional Goals

### Identity & Personality
- Maintain a consistent identity with a name, personality traits, communication style
- Preserve behavioral continuity across sessions and over time

### Persistent User Understanding
- Accumulate facts, preferences, habits, and context about the user
- Use that understanding to shape every response
- Persist between sessions without losing history

### Memory Evolution
- Consolidate conversations into extracted facts and episodic memory
- Summarize and distill over time
- Decay and selectively forget low-signal memories
- Never grow unbounded

### Tool Usage
- Search the web and retrieve pages
- Read YouTube transcripts
- Read, write, and edit files safely
- Execute scheduled tasks and reminders

### Runtime Behavior
- Support long-running execution as a background process
- Support scheduled and periodic cognition (reflection, summarization, consolidation)
- Expose internal state, pipelines, and cognition stages
- Remain observable, debuggable, and replayable

---

## Architectural Values

These values govern every design decision in the project:

| Value | Implication |
|---|---|
| **Explicit over magic** | No hidden prompt chains, no implicit orchestration |
| **Observable** | Every event, effect, and state transition is loggable and inspectable |
| **Replayable** | Event-driven architecture enables deterministic replay |
| **Incremental** | Grow from infrastructure toward cognition, not the reverse |
| **Inspectable state** | All storage is human-readable (Markdown, EDN, SQLite) |
| **Declarative effects** | Cognition produces effect descriptions; the runtime executes them |
| **Composable** | Layers interact through protocols and data, not entangled logic |
| **Testable** | Pure pipeline stages, declarative effects, and data-driven protocols make every layer independently testable |
| **Maintainable** | The system should remain comprehensible and modifiable as it grows |

---

## Non-Goals (Explicitly Deferred)

- Fake autonomy or recursive agent loops
- Opaque orchestration via AI frameworks
- Voice I/O, web UI, mobile — in the near term
- Multi-agent setups
- Local model inference — initially

---

## Success Picture

The system succeeds when:

1. It boots, persists state, and resumes correctly after restart
2. It remembers relevant context and uses it without being prompted
3. Its internal state can be fully inspected at any time
4. Adding a new capability (tool, memory type, cognition stage) requires touching one layer only
5. A developer can replay any past session and observe exactly what happened and why
6. Every layer has extensive test coverage — pipeline stages via unit tests, storage via round-trip tests, tools via mocked I/O, and complex logic via property-based tests
