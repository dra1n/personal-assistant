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
- [x] Render the identity context map (`identity`/`user`/`agents` front-matter + prose) into a system message. _(Required fixing `pa.storage.identity/load-all`, which had been discarding prose; identity tests updated for the `{:front-matter :prose}` shape. `soul` was later retired and merged into `identity` — Group F.)_
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
- [x] Add input affordances: faint placeholder in the empty box and a key-hint line ("Enter send · Tab focus · ↑/↓ scroll · ^L logs · ^C quit").
- [x] Make `conversation-view` scrollable via charm's `viewport`: word-wrapped content, fixed height (terminal height − chrome), auto-pinned to the latest turn, scrolled line-by-line with the arrow keys. Scroll keys are wired via the specific scroll fns (not `viewport-update`) to avoid its default `j`/`k` bindings hijacking text input. (PgUp/PgDn and Ctrl+U/D were dropped — absent / terminal-reserved on the user's macOS keyboard.)

#### Observability: logging (the TUI shared stdout with Timbre, corrupting the frame)
- [x] Add a durable file appender (`PA_HOME/logs/pa.log`, `:debug+`) in `pa.logging`; bootstrap creates the `logs/` dir.
- [x] Add an in-app collapsable log panel: a Timbre appender (`:info+`) forwards entries over a dropping-buffer channel (`pa.ui.subscribe/make-log-subscription`) into the charm loop as `:log/appended`; `pa.ui.app` keeps a bounded ring buffer and renders a panel — collapsed summary or expanded level-coloured rows — toggled with Ctrl+L, with the conversation viewport height recomputed for the panel state.
- [x] Gate the stdout (`:println`) appender behind a `pa.logging/console?` flag, set false by `pa.core/-main` (before any component inits) and by the UI on start, restored on UI halt. A flag — not a one-shot suspend — is required because `:pa.logging/timbre` can initialise *after* the UI (Integrant orders independent components by topo-sort); re-reading the flag on init stops a late logging init from re-enabling console. File appender stays on in both modes.
- [x] Panel appender captures `:debug+` so the panel is a live tail of runtime activity (not stuck at the few startup `:info` lines).
- [x] Make the conversation and log panel both bordered, focusable regions: `:focus` (`:conversation`/`:logs`) routes the scroll keys, **Tab** switches it, the focused region gets a thick border (vs rounded), and the log panel has its own viewport (tails live unless focused-and-scrolled-up). Borders stay uncoloured (charm box-edge bug).

### Group D — Effect & streaming wiring
- [x] Add the delta side-channel. _(Deviation: the channel can't live in `make-subscription` — the dispatcher needs `emit-delta!` and initialises before the UI, which would cycle. Instead a shared `:ui/deltas` Integrant component owns the dropping-buffer channel; both the dispatcher and `:pa.ui/terminal` depend on it. `pa.ui.subscribe/watch-delta-cmd` drains it into the charm loop.)_
- [x] Expose `emit-delta!` as a dispatcher ctx capability (non-blocking `offer!` onto the shared `:ui/deltas` channel).
- [x] Register a `:user/message` handler: append to `:conversation`, persist via `:event/store`, assemble the prompt (Group B), and return an `:llm/invoke` effect carrying the messages vector.
- [x] Implement the `:llm/invoke` `execute-effect` method: run the provider `stream` on a `future`, call `emit-delta!` per delta, accumulate full text, and on completion `dispatch!` a single `:assistant/response` event (errors dispatch an error response).
- [x] Register an `:assistant/response` handler: append the assistant entry to `:conversation` (persist via `:event/store`).
- [x] In `pa.ui.app`, consume `:llm/delta` messages into a UI-local `:streaming` buffer rendered as a live trailing assistant turn; clear it on the next `:runtime/db-updated` (the committed turn). _(Bugfix: `delta-ch` and `db-ch` are independent channels, so deltas still buffered when the assistant turn committed were processed after the clear and re-grew a ghost partial turn. Added a `:streaming-open?` flag — opened on send, closed when the committed turn's last entry is an assistant message — and deltas only append while open, so stragglers are dropped.)_
- [x] Confirm (assertion) that no memory-write or tool effect is emitted on the conversation path (`:user/message` handler test asserts neither `:memory/write` nor `:tool/invoke` is present).

### Group E — Tests
- [x] Prompt assembly: fixture identity + fixture memory records → assert exact messages vector (`pa.llm.prompt` unit test). _(Plus front-matter/prose rendering, memory-snippet injection seam, conversation metadata stripping, empty handling.)_
- [x] Provider protocol: a stub/mock provider implementing the protocol → assert `invoke`/`stream` contract (delta callback called per chunk, full text returned). _(Done via the OpenAI provider + a fake `pa.http/HttpClient`; Anthropic stub conformance also asserted.)_
- [x] Streaming handler: fixture SSE chunk strings → assert the sequence of deltas parsed and `[DONE]` termination. _(`parse-sse-line` + `stream` accumulation tests.)_
- [x] Terminal input capture: simulate key presses + Enter → assert a `:user/message` event is dispatched with buffer contents and the buffer is cleared. _(Plus space/backspace/chord handling, blank-input no-op, and db-update preserving the buffer.)_
- [x] Conversation-turn integration: covered by `pa.runtime.conversation-test` — the `:user/message` handler assembles + emits `:llm/invoke`; the `:llm/invoke` effect streams via a stub provider and dispatches `:assistant/response`; the `:assistant/response` handler commits; and a replay of `[:user/message :assistant/response]` reconstructs `:conversation` with no provider call.

### Group F — Misc: retire soul.md (merge into identity.md)

Motivation: `soul.md` and `identity.md` overlapped in practice — everything intended for soul ended up in identity.md, leaving soul redundant. Collapse to a single identity file. soul's front-matter schema (`name`, `traits`, `communication-style`, `values`) folds into identity.md's front-matter (`version`, `role`, `purpose`).

- [x] `pa.storage.identity/load-all`: drop `["soul.md" :soul]` from the file→key mapping; update the docstring and the return-shape comment (three identity files now: identity/user/agents). The `:soul` key disappears from the loaded `:identity` map.
- [x] `pa.storage.fs`: remove `"soul.md"` from the template list so bootstrap no longer creates it.
- [x] Delete `resources/templates/identity/soul.md`; merge its front-matter fields (`name`, `traits`, `communication-style`, `values`) into `resources/templates/identity/identity.md`.
- [x] `pa.llm.prompt`: drop the `[:soul "Assistant identity"]` section, and rename the `:identity` section title from "Assistant role" → "Assistant identity" (it now carries the persona). Section order becomes identity → user → agents.
- [x] Tests: remove soul fixtures/assertions in `identity_test`, `prompt_test`, `fs_test`, `conversation_test`; fold the moved front-matter (name/traits/…) into the identity fixtures where those assertions still matter.
- [x] Docs: PROJECT.md (drop the `SOUL.md` line + storage-layout entry, note the merge), roadmap Phase 8 personality-schema goal (now identity.md), and the `2026-05-29-persistent-storage-memory-foundation` spec records that list `soul.md`.

### Group G — Conversation as a focusable, bordered, scrollable pane

Promote the conversation from an unbordered viewport implicitly scrolled by the input focus into a first-class focusable region alongside the input and log panel. The log panel is the template (border + own focus + freeze-on-scroll-up). Decisions: Tab-only navigation (no dedicated chord) plus Esc→input; only the focused region scrolls; bare border (no title row).

- [x] Focus model: `:focus` becomes `:input | :conversation | :logs` (was `:input | :logs`).
- [x] `pa.ui.view` conversation render: wrap the viewport in a bordered box (rounded normally, thick when focused) mirroring the log panel; wrap content to `inner-width` (width − 2). No title row.
- [x] `pa.ui.view/viewport-height`: reserve the conversation box's 2 border rows (fixed chrome 7 → 9); update the docstring (no longer "unbordered").
- [x] `pa.ui.app/refresh-conversation`: tail the latest turn unless the conversation is focused and scrolled up — then hold position so history reads without being yanked by new turns/deltas (mirror `refresh-logs`).
- [x] `pa.ui.app/scroll-focused`: route ↑/↓ to the conversation when focus is `:conversation`, the logs when `:logs`, and nothing when `:input`.
- [x] `pa.ui.app` Tab: cycle `input → conversation → logs` (logs only when open) → `input` (previously a no-op unless logs open). Esc returns focus to the input.
- [x] `pa.ui.app/focus-input`: typing snaps focus back to the input from `:conversation` as well as `:logs`.
- [x] Update the hint line + app model docstring; update/extend app & view tests (Tab cycle across regions, focused-region scroll incl. input-focus no-op, Esc→input, border-row reservation).

#### Group G polish (box padding & label spacing)
- [x] Make all three boxes flush: widen the input box from `width − 4` to `inner-width` (`width − 2`) so it shares edges with the conversation and log boxes.
- [x] Add shared horizontal inner padding (`box-padding [0 1]`) to the conversation and log boxes so text doesn't sit against the side borders; introduce `view/text-width` (= `inner-width − 2`) and wrap viewport/content to it (the padding is carved out of the box `:width`). Vertical (top/bottom) is left flush, consistent with the input box.
- [x] Give each turn's name label a one-line bottom gap so it reads as a header, and widen the between-turn gap to two blank lines so the label groups with its own body (label gap < message gap — equal gaps would float the body between two labels).
- [x] Empty state: extract the "no messages yet" placeholder into its own borderless, centred `empty-conversation-view` (filling the same rectangle so the input stays put), shown when `conversation-empty?`. Skip the conversation in the Tab focus cycle while empty — previously the placeholder inherited the box's border + focus, which looked wonky.

## Notes

- **Sequencing:** A, B, C are independent — do them first/in parallel. D is the integration point and depends on all three. E's unit tests can be written alongside their target groups; the integration test waits for D.
- **The central constraint** (Design decision 1/2): deltas flow only over `delta-ch`; only `:user/message` and `:assistant/response` touch `:db` and the event log. When wiring Group D, never put `delta-ch` or deltas into `:db`.
- **hato** is the committed HTTP client (resolves the tech-stack open question). Validate its SSE handling early in Group A — it gates the OpenAI provider.
- **Secrets:** the OpenAI API key comes from env/config; do not commit it. A missing key should fail clearly, and the stub Anthropic provider plus the test stub provider allow all deterministic tests to run with no key present.
- **Replay check:** after Group D, a replay of a fixture event log containing one turn must reconstruct `:conversation` identically without any provider call — proving deltas were never part of state.
- **HTTP seam (added in Group A):** the concrete HTTP client is wrapped behind a `pa.http/HttpClient` protocol (production impl `HatoClient`), injected into `OpenAIProvider` via an `:http` field. This lets the provider be tested end-to-end with a fake client and no network; Phase 4 web tools can reuse the same seam.
- **charm border-colour bug (Group C):** `charm.style`'s text styling downgrades box-drawing edges (`─`/`│`) to ASCII (`-`/`|`) whenever a border `:fg`/`:bg` is applied (all colour profiles, incl. `:true-color`); corners survive. Workaround: leave the input border uncoloured and accent the prompt glyph/header/labels instead.
