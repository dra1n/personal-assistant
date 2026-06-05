# Validation: Phase 4d — Command History

## Definition of done

Phase 4d is complete when command history persists to disk across restarts, ↑/↓ navigation works correctly in the terminal UI (including draft preservation, deduplication, and reset-on-type), all automated tests pass, and a full UX walkthrough has been completed before merge.

## Checklist

### Tests

- [ ] History file load test: fixture `history.edn` with >50 entries → exactly the last 50 are loaded into `:ui/history`
- [ ] Append test: `:user/message` handler called → `:history/append` effect emitted with correct `:history/id`, `:history/text`, `:history/timestamp`
- [ ] Dedup test: handler called twice with identical text → effect emitted only once; second call produces no `:history/append`
- [ ] `navigate-back` unit test: index decrements correctly; entry text matches history at that position; first ↑ snapshots the draft
- [ ] `navigate-forward` past end unit test: draft restored; index reset to `nil`
- [ ] `reset-navigation` unit test: index cleared; typed character preserved in returned buffer text
- [ ] Regression: existing `pa.ui.app` input handler tests pass unchanged

### Behaviors

- [ ] Type several distinct commands and submit; ↑ arrow navigates back through them in order (most recent first)
- [ ] ↓ arrow from the oldest entry returns toward the newest; pressing ↓ past the most recent entry restores the original draft
- [ ] Type some text, press ↑ to navigate, then type a character — navigation resets and the character appears in the input
- [ ] Submit the same command twice in a row — only one entry appears in history navigation
- [ ] Restart the app; previously submitted commands are available via ↑ (loaded from `history.edn`)

### Integration

- [ ] Delete `assistant-data/` and restart — bootstrap creates `history/history.edn`; app starts cleanly with empty history
- [ ] `:ui/history` is populated in the runtime db at startup and visible via Portal inspection
- [ ] Enter-to-submit and all existing key handling in `pa.ui.app` are unbroken after the refactor into `pa.ui.input`
- [ ] `history.edn` is correctly written to disk after each submission (verify with `cat assistant-data/history/history.edn` at REPL)

## Merge criteria

- All automated tests pass (`clojure -M:test`)
- Full UX walkthrough completed: submit commands, navigate history, restart app, confirm dedup — all behaviors checked
- No regression in existing input/submit behavior
- `history.edn` persists on disk and survives restart
- `pa.ui.input` namespace exists and navigation logic is not embedded in `pa.ui.app`
