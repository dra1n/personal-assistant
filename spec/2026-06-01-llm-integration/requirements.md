# Requirements: Phase 3 — LLM Integration

## Goal

Introduce controlled, abstracted LLM interaction into the runtime. The assistant should accept a typed user message in the terminal, assemble a prompt from identity + user context + memory snippets, invoke an LLM provider over streaming SSE, display the streamed response live in the terminal, and record the conversation turn (user message + assistant response) as persisted events. The LLM only responds in this phase — it does not write memory or execute tools. Every durable state change remains a serializable event so the runtime stays replayable.

## Scope

### In scope

- **LLM provider protocol** (`invoke`, `stream`) — a thin, project-owned abstraction over raw HTTP; no third-party LLM SDKs.
- **OpenAI provider** — full implementation: HTTP via **hato**, streaming via SSE (`stream: true`), parsing `data:` chunks into delta strings, terminating on `[DONE]`.
- **Anthropic provider stub** — conforms to the same protocol; minimal implementation (canned/throwing) so a second provider is a drop-in later.
- **Prompt assembly pipeline** — pure function: `identity context + user context + memory snippets → messages list` (a vector of `{:role :content}` maps). Identity/user come from the loaded `:identity` context map; memory snippets come from existing basic retrieval (recent N records) — Phase 5 will replace retrieval, this seam stays stable.
- **`:llm/invoke` effect** — new `execute-effect` method; runs the provider stream on a background thread, emits deltas to an out-of-band UI side-channel, and on completion dispatches a single `:assistant/response` event carrying the full accumulated text.
- **Terminal text input capture** — UI-local input buffer in `pa.ui.app`; accumulate key presses; on Enter dispatch a `:user/message` event with the buffer contents and clear the buffer. UI stays a thin client: input becomes an event, no direct state mutation.
- **Delta side-channel** — a second core.async channel (sibling to the existing `db-ch`), owned by the `:pa.ui/terminal` component, exposed to the dispatcher ctx as an `emit-delta!` capability. Carries `:llm/delta` messages into the charm loop; accumulated in a UI-local `:streaming` buffer for live display.
- **Conversation turn events** — `:user/message` and `:assistant/response`, both persisted to the event log via the existing `:event/store`-driven path; both append to `:conversation` in `:db` via `add-conversation-entry`.
- **Streaming response handler** — parses fixture/real SSE chunks into deltas; the deterministic chunk→delta parsing is unit-tested independently of any live model.
- **Tests** — prompt assembly with fixture identity/memory data; provider protocol against a stub/mock provider; streaming handler against fixture SSE chunks; terminal input capture (key presses + Enter → `:user/message` dispatched, buffer cleared).

### Out of scope

- Memory **writes** or extraction from LLM responses (Phase 5).
- Tool selection / tool execution (Phase 4).
- The explicit multi-stage cognition pipeline (`interpret → retrieve → plan → …`) — Phase 7. This phase wires a direct `:user/message → assemble → :llm/invoke → :assistant/response` path, not the staged pipeline.
- Sophisticated/semantic retrieval, embeddings, cosine similarity (Phase 5). Memory snippets use existing recency retrieval only.
- Personality injection as a stable system prefix from the `identity.md` schema (Phase 8) — Phase 3 includes identity/user content in the prompt but does not formalize the personality schema.
- Live-LLM tests in CI (non-deterministic, costly — excluded per `tech-stack.md`).
- Anthropic full streaming implementation (stub only this phase).
- Slash-command parsing (`/command`) — Phase 7 builds on the input pipeline created here.
- Resolving hato-vs-http-kit as an open question — **decided in favor of hato** (see Design decisions).

## Design decisions

1. **Deltas are out-of-band UI presentation, not runtime state.** Streamed SSE deltas never enter `:db` and are never persisted to the event log. They flow over a dedicated core.async side-channel (sibling to `db-ch`) straight into the charm-local model. This preserves the core invariant from `mission.md` — every `:db`-touching event is a serializable data value, and the runtime is replayable. Putting a channel or hundreds of deltas into `:db` would break both serialization and replay; this decision avoids that entirely.

2. **A conversation turn is exactly two persisted events.** `:user/message` (full text in) and `:assistant/response` (full accumulated text out). Replay reconstructs the conversation from these two events per turn; intermediate deltas are cosmetic and are not replayed. This satisfies roadmap line 853 ("user message + assistant response as events") while keeping the event log clean.

3. **hato is the HTTP client.** Resolves the `tech-stack.md` open question. hato wraps the JVM `HttpClient`, which has first-class SSE/line-streaming support and no extra async machinery to manage. http-kit is not adopted.

4. **OpenAI is implemented fully; Anthropic is a protocol-conforming stub.** Both sit behind the LLM provider protocol. The stub exists so adding the real Anthropic provider later touches only one namespace — honoring the mission's "one layer only" success criterion.

5. **No LLM SDKs.** Raw OpenAI HTTP API through a thin project-owned protocol, per `tech-stack.md` ("prefer raw API calls through a thin project-owned abstraction").

6. **`:llm/invoke` is a declarative effect; the provider call is the only impure part.** The handler returns an effects map containing `:llm/invoke`; the executor runs the provider stream against the dispatcher ctx capabilities (`emit-delta!`, `dispatch!`). Cognition produces effect descriptions; the runtime executes them — per `mission.md` "declarative effects."

7. **Prompt assembly is a pure function** (context map in → messages vector out) — unit-testable with fixture identity/memory data, no LLM boundary crossed. Memory-snippet retrieval is injected, not hard-wired, so Phase 5 retrieval drops into the same seam.

8. **The delta side-channel is owned by the UI Integrant component**, created alongside `db-ch` in the subscription bridge, and exposed to the dispatcher ctx as a runtime capability. Lifecycle (creation/close) follows the existing `db-ch` pattern in `pa.ui.core` / `pa.ui.subscribe`.

9. **Test only up to the LLM boundary.** Deterministic units — prompt assembly, stub provider, fixture-SSE chunk parsing, input capture — are covered by `clojure.test`. A real round-trip is validated by a manual REPL smoke test, never in CI.

## Context

Phases 0–2 are complete: an event-driven runtime (dispatch → coeffect injection → interceptor chain → declarative effects → executor), durable EDN event log with replay, canonical Markdown memory with a rebuildable SQLite index, and identity loading into a `:identity` context map injected at startup.

This phase plugs the first LLM interaction into that substrate without violating its invariants. Key existing seams it builds on:

- **Effects** are a multimethod `pa.runtime.executor/execute-effect` dispatched on effect-type keyword; handlers (`pa.runtime.handlers` via `pa.runtime.registry/reg-handler`) return an effects map. The dispatcher ctx already carries runtime capabilities (`dispatch!`, `store-event!`, `write-memory!`, `index-memory!`); this phase adds `emit-delta!` and the LLM provider.
- **State** `pa.state.db` already has `:conversation []` with `add-conversation-entry` (transitions) and a `conversation` query. The `:identity` context map is already populated by `pa.storage.identity/load-all` (`identity.md`, `user.md`, `agents.md`; `soul.md` retired in Group F).
- **The UI** (`pa.ui.app`) is output-only today: `update-model` handles `ctrl+c` and `:runtime/db-updated`, everything else is a no-op, and the model already reserves room for "input buffer, scroll" UI-local state. The subscription bridge (`pa.ui.subscribe`) demonstrates the exact channel-into-charm-loop pattern the delta side-channel reuses.
- **Persistence** is event-driven: any event that should be durable goes through the `:event/store` path; replay reconstructs `:db` from `events.edn` alone.
