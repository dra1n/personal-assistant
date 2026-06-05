# Plan: Phase 4d — Command History

## Task groups

### Group A — Storage & Bootstrap

- [ ] Define history entry schema: `{:history/id <uuid> :history/text <string> :history/timestamp <inst>}`
- [ ] Extend first-startup bootstrap to create `assistant-data/history/` directory and an empty `history.edn` file
- [ ] Implement history file reader: parse `history.edn`, return the last 50 entries as a vector
- [ ] Wire history loading into system startup: read `history.edn` at boot and set `:ui/history` in the initial runtime db state (via the bootstrap/init path, not a dispatched event)

### Group B — Persistence

- [ ] Implement `:history/append` effect executor: serializes and appends a new history entry to `history.edn`; also `conj`s the entry onto `:ui/history` in the runtime db (via `:db` effect or combined effect)
- [ ] Register `:history/append` in the effect registry (alongside `:log/info`, `:dispatch`, etc.)
- [ ] Wire `:history/append` into the `:user/message` handler — emit the effect after the conversation state update
- [ ] Implement consecutive duplicate suppression in the handler: compare `(:history/text new-entry)` to `(:history/text (last (:ui/history db)))`; omit the effect if equal

### Group C — UI Navigation

- [ ] Create `pa.ui.input` namespace; define navigation state shape: `{:nav/index nil, :nav/draft ""}` (`nil` index means not currently navigating)
- [ ] Implement `navigate-back` fn: if not navigating, snapshot current buffer as draft; decrement index (clamped to 0); return the history entry text at that index
- [ ] Implement `navigate-forward` fn: increment index; if past the last history entry, restore draft and reset index to `nil`
- [ ] Implement `reset-navigation` fn: called when a printable character is typed while navigating; resets index to `nil` and prepends the character to the draft (so the typed char is not lost)
- [ ] Wire `pa.ui.input` into `pa.ui.app` key handling: intercept ↑/↓ before the existing buffer logic; delegate to `pa.ui.input` fns; pass current `:ui/history` from runtime db

### Group D — Tests

- [ ] Load fixture `history.edn` (>50 entries) → assert exactly 50 are loaded and they are the last 50
- [ ] Submit a message → assert `:history/append` effect is emitted with correct schema fields
- [ ] Consecutive duplicate suppression: handler called twice with the same text → assert effect emitted only on first call
- [ ] Navigation state machine — `navigate-back`: assert index decrements and correct entry text returned
- [ ] Navigation state machine — `navigate-forward` past end: assert draft restored and index reset to `nil`
- [ ] `reset-navigation`: assert index cleared and character preserved in output
- [ ] Regression: existing Enter-to-submit path in `pa.ui.app` unchanged; existing input handler tests still pass

## Notes

- Group A must land before Group B (effect needs `:ui/history` to exist in db for dedup check).
- Group B must land before Group D's append/dedup tests.
- Group C is independent of B and can be developed in parallel, but both must be done before the full UX walkthrough in validation.
- The `pa.ui.input` fns are pure (take history vector + current state, return new state/text) — no side effects — making them straightforward to unit test without a running UI.
