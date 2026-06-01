# Validation: Phase 3 — LLM Integration

## Definition of done

A user can type a message in the terminal, see the assistant's response stream in live token-by-token, and the turn is recorded as exactly two persisted, serializable events (`:user/message`, `:assistant/response`). The LLM is reached through a thin provider protocol (OpenAI fully implemented over hato/SSE; Anthropic a conforming stub) and produces only a response — no memory writes, no tool calls. All deterministic units (prompt assembly, provider protocol against a stub, SSE chunk parsing, terminal input capture) pass under `clojure.test`; a real end-to-end round-trip is confirmed manually at the REPL/terminal, never in CI. Streamed tokens never enter `:db` or the event log, and a fixture event log replays to an identical `:conversation` with no provider call.

## Checklist

### Tests
- [ ] Prompt assembly: fixture identity + fixture memory records → asserts exact messages vector (roles + content), including memory-snippet formatting.
- [ ] Prompt assembly: memory-snippet seam accepts injected retrieval results (recent N) without hard-wiring Phase 5 retrieval.
- [ ] Provider protocol: stub/mock provider → `stream` invokes the per-token callback for each chunk and returns the full accumulated text; `invoke` returns full text.
- [ ] Streaming handler: fixture SSE chunk strings → asserts ordered token sequence, skips keep-alives, terminates on `[DONE]`.
- [ ] Terminal input capture: simulated key presses + Enter → asserts a `:user/message` event is dispatched with buffer contents and the buffer is cleared; Backspace trims.
- [ ] Conversation-turn integration (deterministic, stub provider): driving `:user/message` through the runtime stores `:user/message` then `:assistant/response`, and `:conversation` contains both.
- [ ] No live-LLM test runs in CI (per tech-stack — confirm none was added).

### Behaviors
- [ ] Typing in the terminal and pressing Enter sends the message and clears the input line.
- [ ] The assistant response renders live, token-by-token, in the terminal (SSE streaming visible).
- [ ] On completion, the committed assistant message appears in the conversation view and the live `:streaming` buffer is cleared.
- [ ] The LLM only responds — no memory file is written and no tool is invoked during a turn.
- [ ] A missing OpenAI API key fails clearly (and does not block deterministic tests or the Anthropic stub).

### Integration
- [ ] `:llm/invoke` is registered as an `execute-effect` method and runs the provider stream against the dispatcher ctx (`emit-token!`, `dispatch!`).
- [ ] The token side-channel is created/owned by `:pa.ui/terminal` (sibling to `db-ch`) and closed on halt; tokens reach the charm loop as `:llm/token` messages.
- [ ] `:db` never contains the token channel or token deltas — only `:user/message`/`:assistant/response` entries in `:conversation`.
- [ ] Both turn events go through the `:event/store` persistence path and appear in `events.edn`.
- [ ] Replay of a fixture event log with one turn reconstructs `:conversation` identically, with no provider call made.
- [ ] Adding a real Anthropic provider later would touch only its own namespace + config (protocol unchanged).

## Merge criteria

All of the following must be true before merging `2026-06-01-llm-integration`:

1. Every box in **Tests** is checked and the full `clojure.test` suite (including prior phases) passes green.
2. No live-LLM or networked-provider call exists in the automated test suite.
3. The replay invariant holds: a one-turn fixture log replays to an identical `:conversation` without invoking any provider — proving tokens are out-of-band and state is serializable.
4. A manual REPL/terminal smoke test demonstrates a real OpenAI round-trip: typed input → live streamed tokens → committed `:assistant/response`, with both events present in `events.edn`.
5. No memory-write or tool effect is emitted on the conversation path (Phase 3 boundary respected).
6. No secrets committed; API key sourced from env/config; missing key degrades gracefully.
7. `spec/roadmap.md` Phase 3 items are checked off to match what shipped.
