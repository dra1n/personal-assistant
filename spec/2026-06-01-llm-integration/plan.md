# Plan: Phase 3 — LLM Integration

Task groups are dependency-ordered: protocol & providers → prompt assembly → UI input → effect & streaming wiring → tests. Groups A, B, and C are independent and can proceed in parallel; Group D depends on all three; Group E depends on D for the integration-flavored tests but its unit tests track each prior group.

## Task groups

### Group A — LLM provider protocol & implementations
- [x] Define the LLM provider protocol in `pa.llm.provider`: `invoke` (blocking, returns full text) and `stream` (calls a per-delta callback, returns the full text on completion).
- [x] Add the `hato` dependency to `deps.edn`; confirm SSE/line-streaming works against the JVM `HttpClient`.
- [x] Implement the OpenAI provider: build the chat-completions request from a messages vector, set `stream: true`, parse `data:` SSE lines into delta strings, ignore keep-alives, terminate on `[DONE]`. Read the API key from config/env (no hard-coded secrets).
- [x] Implement the Anthropic provider stub: same protocol, minimal body (canned response or explicit "not implemented" signal) so it is a drop-in later.
- [x] Wire both providers as Integrant components and add the active provider to `pa.config/system-config`; expose the provider to the dispatcher ctx (sibling to `store-event!`, etc.).

### Group B — Prompt assembly pipeline
- [x] Create `pa.llm.prompt` with a pure `assemble` fn: `{:identity ... :conversation ... :memory-snippets ...} → [{:role :content} ...]`.
- [x] Render the identity context map (`soul`/`identity`/`user`/`agents` front-matter + prose) into a system message. _(Required fixing `pa.storage.identity/load-all`, which had been discarding prose; identity tests updated for the `{:front-matter :prose}` shape.)_
- [x] Render memory snippets — accept retrieved records (recent N via existing basic retrieval) and format `:memory/title` + `:memory/summary` into the system/context message. Keep retrieval injected, not hard-wired (Phase 5 seam).
- [x] Append the running `:conversation` as alternating user/assistant messages.

### Group C — Terminal text input capture
- [ ] Add a UI-local `:input` buffer to the `pa.ui.app` charm model.
- [ ] In `update-model`, handle character key presses (append to buffer), Backspace (trim), and Enter (commit).
- [ ] On Enter: dispatch a `:user/message` event with the buffer contents via the runtime dispatch path, then clear the buffer. No direct `:db` mutation.
- [ ] Update `view` to render the input line (and a prompt indicator) beneath the existing frame.

### Group D — Effect & streaming wiring
- [ ] Add the delta side-channel: extend `pa.ui.subscribe/make-subscription` to also create `delta-ch` (and its sink); own its lifecycle in `pa.ui.core` (create at init, close on halt) alongside `db-ch`.
- [ ] Expose `emit-delta!` as a dispatcher ctx capability (wired through `pa.config`/dispatcher, same as the existing capabilities).
- [ ] Register a `:user/message` handler: append to `:conversation` (persist via `:event/store`), assemble the prompt (Group B), and return an `:llm/invoke` effect carrying the messages vector.
- [ ] Implement the `:llm/invoke` `execute-effect` method: run the provider `stream` on a background thread, call `emit-delta!` per delta, accumulate full text, and on completion `dispatch!` a single `:assistant/response` event with the full text.
- [ ] Register an `:assistant/response` handler: append the assistant entry to `:conversation` (persist via `:event/store`).
- [ ] In `pa.ui.app`, consume `:llm/delta` messages from `delta-ch` into a UI-local `:streaming` buffer and render it live; on the next `:assistant/response`-driven `:runtime/db-updated`, clear `:streaming` and show the committed conversation.
- [ ] Confirm (assertion/REPL note) that no memory-write or tool effect is emitted anywhere in this path.

### Group E — Tests
- [x] Prompt assembly: fixture identity + fixture memory records → assert exact messages vector (`pa.llm.prompt` unit test). _(Plus front-matter/prose rendering, memory-snippet injection seam, conversation metadata stripping, empty handling.)_
- [x] Provider protocol: a stub/mock provider implementing the protocol → assert `invoke`/`stream` contract (delta callback called per chunk, full text returned). _(Done via the OpenAI provider + a fake `pa.http/HttpClient`; Anthropic stub conformance also asserted.)_
- [x] Streaming handler: fixture SSE chunk strings → assert the sequence of deltas parsed and `[DONE]` termination. _(`parse-sse-line` + `stream` accumulation tests.)_
- [ ] Terminal input capture: simulate key presses + Enter → assert a `:user/message` event is dispatched with buffer contents and the buffer is cleared.
- [ ] (Optional, deterministic) conversation-turn integration: drive `:user/message` through the runtime with the stub provider → assert `:user/message` and `:assistant/response` events are stored and `:conversation` reflects both.

## Notes

- **Sequencing:** A, B, C are independent — do them first/in parallel. D is the integration point and depends on all three. E's unit tests can be written alongside their target groups; the integration test waits for D.
- **The central constraint** (Design decision 1/2): deltas flow only over `delta-ch`; only `:user/message` and `:assistant/response` touch `:db` and the event log. When wiring Group D, never put `delta-ch` or deltas into `:db`.
- **hato** is the committed HTTP client (resolves the tech-stack open question). Validate its SSE handling early in Group A — it gates the OpenAI provider.
- **Secrets:** the OpenAI API key comes from env/config; do not commit it. A missing key should fail clearly, and the stub Anthropic provider plus the test stub provider allow all deterministic tests to run with no key present.
- **Replay check:** after Group D, a replay of a fixture event log containing one turn must reconstruct `:conversation` identically without any provider call — proving deltas were never part of state.
- **HTTP seam (added in Group A):** the concrete HTTP client is wrapped behind a `pa.http/HttpClient` protocol (production impl `HatoClient`), injected into `OpenAIProvider` via an `:http` field. This lets the provider be tested end-to-end with a fake client and no network; Phase 4 web tools can reuse the same seam.
