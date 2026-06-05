# Requirements: Phase 4d — Command History

## Goal

Improve terminal UX by adding persistent, navigable command history. Every command the user submits is appended to `assistant-data/history/history.edn` (append-only EDN, consistent with `events.edn`). On startup the last 50 entries are loaded into `:ui/history` in the runtime db. In the terminal UI, ↑/↓ arrows navigate back and forward through that history; a saved draft buffer ensures text typed before navigating is restored when the user presses ↓ past the most recent entry.

## Scope

### In scope

- Bootstrap extension: `assistant-data/history/` directory and empty `history.edn` created on first startup
- History entry schema: `{:history/id ... :history/text ... :history/timestamp ...}`
- Startup history load: last 50 entries from `history.edn` → `:ui/history` in the runtime db atom
- `:history/append` effect type: appends to `history.edn` and updates `:ui/history` in db
- Wiring `:history/append` into the `:user/message` handler
- Consecutive duplicate suppression: skip append when new text equals the most recent entry
- New `pa.ui.input` namespace encapsulating navigation state and key-handling logic
- ↑/↓ arrow navigation with draft buffer in the UI input component
- Navigation reset when the user types a character mid-navigation

### Out of scope

- History search or filtering (Ctrl-R style)
- Limiting history to specific event or command types
- History management commands (clear, delete entry)
- Persisting navigation state across restarts (ephemeral by design)

## Design decisions

1. **`:ui/history` in the runtime db atom** — consistent with how all other shared state is managed; accessible to the full event system; transitions via the `:db` effect, not direct `swap!`.
2. **Append-only file** — `history.edn` follows the same convention as `events.edn`; only the last 50 entries are loaded into memory at startup; the file grows unboundedly on disk (acceptable for this use case — trimming is a future nice-to-have).
3. **Navigation state is UI-local and ephemeral** — the navigation index and draft buffer live only in `pa.ui.input` component state (not in the runtime db); they reset on each successful submit.
4. **New `pa.ui.input` namespace** — navigation logic is extracted out of `pa.ui.app` to remain independently testable and to keep `pa.ui.app` focused on layout and rendering.
5. **Consecutive duplicate suppression** — before emitting `:history/append`, the handler compares the new text against `(:history/text (last (:ui/history db)))`; if equal, the effect is not emitted.

## Context

The existing input handling lives in `pa.ui.app` and captures keypresses into a UI-local buffer, dispatching `:user/message` on Enter. This phase extends that model: ↑/↓ are intercepted before the buffer update, delegated to `pa.ui.input`. The runtime db already carries a `:ui {}` key in its initial state shape (Phase 1); `:ui/history` is a natural addition there. The append-only EDN file convention is established by Phase 2's `events.edn`; `history.edn` follows the same pattern.
