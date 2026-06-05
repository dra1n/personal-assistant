# Requirements: Phase 4e — Multiline Input

## Goal

Extend the terminal input system to support multiline messages — both pasted from the
clipboard and typed manually via Shift+Enter — without treating embedded newlines as
premature submits. The full buffer (including embedded `\n`) is dispatched as a single
`:user/message` event on Enter, eliminating the current bug where pasting multiline
content fires one LLM invocation per line.

## Scope

### In scope

- Intercept charm.clj paste events: detect the paste event type and insert clipboard
  content verbatim into the input buffer as a single string (including any embedded newlines)
- Manual newline insertion via **Alt+Enter**: inserts `\n` into the buffer without
  submitting
- Enter alone submits the full buffer contents (existing behavior preserved for single-line case)
- Multiline display: the input widget grows to fit the buffer when it contains newlines
  (whole layout reflows — not a fixed-height scrollable area)
- Single `:user/message` dispatch containing the full buffer text (including `\n` characters)
- History stores the full multiline text as a single entry

### Out of scope

- Cursor movement within multiline text (no arrow-key repositioning inside the buffer)
- Copy / cut key bindings for buffer selection
- Shift+Enter (not reliably detectable in charm.clj — Enter is a control character and Shift modifier is not propagated by standard terminals)
- Fixed-height scrollable input widget (layout reflow is the chosen approach)

## Design decisions

1. **Paste detection via charm.clj paste event** — charm.clj exposes a distinct paste event
   type; the input handler must branch on this event type and insert the clipboard payload
   verbatim rather than dispatching. No character-by-character heuristic is needed.

2. **Layout reflow for multiline display** — when the buffer contains `\n`, the input widget
   expands and the surrounding layout reflows to accommodate. This is simpler than a
   fixed-height scroll widget and more visible to the user.

3. **Alt+Enter is the manual newline binding** — Shift+Enter is not detectable in charm.clj
   on standard terminals (Enter is byte 13; the Shift modifier is not propagated for control
   characters). Alt+Enter arrives as ESC + `\r`, which charm routes as `{:type :runes :runes "\r" :alt true}` — a distinct, reliable event.

4. **No intra-buffer cursor movement** — the input remains append-only with full-buffer
   replace (from history navigation). This preserves the existing input state machine
   (from Phase 4d) without adding cursor position tracking.

5. **UI boundary preserved** — all changes live in `pa.ui.*`; the buffer contents become
   an event at submit time with no cognition-layer changes required. The runtime and LLM
   layers receive `\n`-containing text transparently.

6. **OS-level clipboard roundtrip required for validation** — paste intercept must be
   tested with a real clipboard operation in a running app, not only via buffer injection
   (per validation preference). Buffer-level simulation covers unit tests; clipboard
   roundtrip confirms the charm.clj paste event fires correctly.

## Context

Phase 4d introduced persistent command history and up/down navigation, wiring the input
buffer to a navigation state machine in UI component state. Phase 4e modifies the same
buffer and key-event dispatch path. The key constraint is non-regression: history
navigation (↑/↓) and single-line Enter submit must behave identically after this phase.

The Phase 3 LLM integration receives the `:user/message` event's `:text` field directly
into the prompt assembly pipeline. Embedded `\n` characters in that field require no
special handling at the LLM layer — OpenAI and Anthropic APIs accept multiline user
messages. The fidelity check (LLM receives full text with embedded newlines) is a
validation concern, not an implementation one.
