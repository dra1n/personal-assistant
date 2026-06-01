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
- [x] Add a UI-local `:input` buffer to the `pa.ui.app` charm model. _(Also threads `:dispatch!` from `:pa.runtime/dispatcher` into the UI component via config, and tracks `:width`/`:height` from `:window-size`.)_
- [x] In `update-model`, handle character key presses (append to buffer), Backspace (trim), and Enter (commit). _(Space arrives as the `:space` special key, not a runes string; ctrl/alt chords are ignored.)_
- [x] On Enter: dispatch a `:user/message` event (trimmed) via a charm command using `:dispatch!`, then clear the buffer. Blank input is a no-op. No direct `:db` mutation.
- [x] Update `view` to render the input line (and a prompt indicator) beneath the existing frame. _(Styled with `charm.style`: header, colour-coded turns, rounded-border input box with a cyan `›`.)_

#### Group C polish (input UX)
- [x] Render a visible cursor inside the input box (reverse-video block at the buffer end) so the caret tracks typed text, rather than relying on the stray terminal cursor.
- [x] Handle input longer than the box width: trailing-window horizontal scroll with a leading ellipsis (`visible-window`), keeping the caret in view without overflowing the box.
- [x] Add input affordances: faint placeholder in the empty box and a key-hint line ("Enter send · Tab focus · ↑/↓ ^U/^D scroll · ^L logs · ^C quit").
- [x] Make `conversation-view` scrollable via charm's `viewport`: word-wrapped content, fixed height (terminal height − chrome), auto-pinned to the latest turn, scrolled with arrows (line) + Ctrl+U/D (half-page). Scroll keys are wired via the specific scroll fns (not `viewport-update`) to avoid its default `j`/`k` bindings hijacking text input.

#### Observability: logging (the TUI shared stdout with Timbre, corrupting the frame)
- [x] Add a durable file appender (`PA_HOME/logs/pa.log`, `:debug+`) in `pa.logging`; bootstrap creates the `logs/` dir.
- [x] Add an in-app collapsable log panel: a Timbre appender (`:info+`) forwards entries over a dropping-buffer channel (`pa.ui.subscribe/make-log-subscription`) into the charm loop as `:log/appended`; `pa.ui.app` keeps a bounded ring buffer and renders a panel — collapsed summary or expanded level-coloured rows — toggled with Ctrl+L, with the conversation viewport height recomputed for the panel state.
- [x] Gate the stdout (`:println`) appender behind a `pa.logging/console?` flag, set false by `pa.core/-main` (before any component inits) and by the UI on start, restored on UI halt. A flag — not a one-shot suspend — is required because `:pa.logging/timbre` can initialise *after* the UI (Integrant orders independent components by topo-sort); re-reading the flag on init stops a late logging init from re-enabling console. File appender stays on in both modes.
- [x] Panel appender captures `:debug+` so the panel is a live tail of runtime activity (not stuck at the few startup `:info` lines).
- [x] Make the conversation and log panel both bordered, focusable regions: `:focus` (`:conversation`/`:logs`) routes the scroll keys, **Tab** switches it, the focused region gets a thick border (vs rounded), and the log panel has its own viewport (tails live unless focused-and-scrolled-up). Borders stay uncoloured (charm box-edge bug).

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
- [x] Terminal input capture: simulate key presses + Enter → assert a `:user/message` event is dispatched with buffer contents and the buffer is cleared. _(Plus space/backspace/chord handling, blank-input no-op, and db-update preserving the buffer.)_
- [ ] (Optional, deterministic) conversation-turn integration: drive `:user/message` through the runtime with the stub provider → assert `:user/message` and `:assistant/response` events are stored and `:conversation` reflects both.

## Notes

- **Sequencing:** A, B, C are independent — do them first/in parallel. D is the integration point and depends on all three. E's unit tests can be written alongside their target groups; the integration test waits for D.
- **The central constraint** (Design decision 1/2): deltas flow only over `delta-ch`; only `:user/message` and `:assistant/response` touch `:db` and the event log. When wiring Group D, never put `delta-ch` or deltas into `:db`.
- **hato** is the committed HTTP client (resolves the tech-stack open question). Validate its SSE handling early in Group A — it gates the OpenAI provider.
- **Secrets:** the OpenAI API key comes from env/config; do not commit it. A missing key should fail clearly, and the stub Anthropic provider plus the test stub provider allow all deterministic tests to run with no key present.
- **Replay check:** after Group D, a replay of a fixture event log containing one turn must reconstruct `:conversation` identically without any provider call — proving deltas were never part of state.
- **HTTP seam (added in Group A):** the concrete HTTP client is wrapped behind a `pa.http/HttpClient` protocol (production impl `HatoClient`), injected into `OpenAIProvider` via an `:http` field. This lets the provider be tested end-to-end with a fake client and no network; Phase 4 web tools can reuse the same seam.
- **charm border-colour bug (Group C):** `charm.style`'s text styling downgrades box-drawing edges (`─`/`│`) to ASCII (`-`/`|`) whenever a border `:fg`/`:bg` is applied (all colour profiles, incl. `:true-color`); corners survive. Workaround: leave the input border uncoloured and accent the prompt glyph/header/labels instead.
