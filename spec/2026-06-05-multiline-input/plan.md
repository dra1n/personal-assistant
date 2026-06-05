# Plan: Phase 4e — Multiline Input

## Task groups

### Group 1 — Paste infrastructure

- [x] Investigate charm.clj paste event API: confirm the event type name, payload shape, and how clipboard content is surfaced
- [x] Handle paste event in `pa.ui.input` (or wherever key events are dispatched): on paste event, insert clipboard string verbatim into the buffer (do not split on `\n` or dispatch)
- [x] Ensure the existing character-key path is unchanged (paste event branches before reaching character append logic)

### Group 2 — Keybindings

- [x] Add Alt+Enter handler: insert `\n` into the buffer without submitting (Shift+Enter is not detectable on standard terminals; Alt+Enter arrives as ESC+\r, a distinct charm event)
- [x] Confirm Enter alone submits regardless of buffer containing `\n` (full buffer is dispatched as `:user/message`)
- [x] Confirm history navigation keys (↑/↓) are unaffected by the new bindings

### Group 3 — Display

- [x] Update the input widget to detect `\n` in the buffer and render as multiline text
- [x] Wire layout reflow: when the buffer is multiline, the input area expands and the surrounding layout adjusts (not a fixed-height scroll)
- [x] Confirm single-line rendering is unchanged when buffer contains no `\n`

### Group 4 — Tests

- [ ] Paste simulation unit test: inject a multiline string into the buffer via a synthetic paste event → assert buffer contains full string with `\n`, no submit fired
- [ ] OS-level clipboard integration test: trigger a real paste in a running app → assert single `:user/message` dispatched, history stores one entry with full text
- [ ] Shift+Enter unit test: assert `\n` inserted into buffer, no `:user/message` dispatched
- [ ] Enter-on-multiline unit test: assert full buffer (including `\n`) dispatched as a single `:user/message`
- [ ] Regression — single-line Enter: assert existing submit path unchanged
- [ ] Regression — history navigation: assert ↑/↓ still cycle correctly after multiline changes

## Notes

Group 1 must complete before Group 2 (key event dispatch path must be stable before
adding new bindings on top of it). Group 3 (display) can proceed in parallel with
Group 2 once the buffer shape is known, but should not block on Group 2 completing.
Group 4 depends on Groups 1–3 being stable.

The charm.clj paste event investigation (first task in Group 1) is the highest-risk
item — if the paste event payload is not what we expect, the intercept strategy may
need to change. Do this first before writing any other code.
