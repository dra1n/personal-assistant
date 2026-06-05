# Validation: Phase 4e — Multiline Input

## Definition of done

Phase 4e is complete when: pasting multiline clipboard content into the running terminal
app inserts the full text into the input buffer as a single string and dispatches exactly
one `:user/message` event on Enter; Shift+Enter inserts a `\n` without submitting; the
input widget renders multiline content with layout reflow; and all of this is confirmed
by both automated tests and an OS-level clipboard roundtrip in the running app, with no
regression in history navigation or single-line submit behavior.

## Checklist

### Tests

- [ ] Paste simulation unit test passes: synthetic paste event → buffer contains full multiline string, zero `:user/message` events fired
- [ ] Alt+Enter unit test passes: `\n` inserted, no `:user/message` dispatched
- [ ] Enter-on-multiline-buffer unit test passes: full text (with `\n`) dispatched as one event
- [ ] Single-line Enter regression test passes: existing submit path unchanged
- [ ] History navigation regression tests pass: ↑/↓ cycling and draft restoration unaffected
- [ ] History entry test: submitted multiline message stored as a single entry in `:ui/history` and `history.edn`

### Behaviors

- [ ] OS-level clipboard roundtrip: copy a multiline string from another app, paste into the running PA terminal → buffer shows full text, display grows to fit, single `:user/message` dispatched on Enter
- [ ] Alt+Enter in running app: inserts visible newline in input widget, does not submit
- [ ] Enter in running app on a multiline buffer: submits the whole text, input clears
- [ ] LLM receives full text: after submitting a multiline message, the LLM prompt contains the embedded `\n` characters intact (verify via Portal tap or log output)
- [ ] Input widget reflows: when buffer contains `\n`, the layout adjusts visibly; when buffer is cleared, it returns to single-line height

### Integration

- [ ] History navigation (↑/↓) works correctly after multiline changes — no state machine corruption
- [ ] Single-line Enter submit path: no change in behavior (regression check against Phase 3/4d baseline)
- [ ] `history.edn` on disk: multiline submissions appear as single entries with `\n` in the `:history/text` field

## Merge criteria

All of the following must be true before this branch is merged:

1. All automated tests in Group 4 pass
2. OS-level clipboard roundtrip confirmed manually in the running app
3. LLM newline fidelity confirmed via tap/log (embedded `\n` reaches the prompt)
4. History navigation regression confirmed (↑/↓ still works)
5. Single-line Enter regression confirmed (existing submit path unchanged)
6. No new warnings or errors in Timbre log during multiline paste or submit
