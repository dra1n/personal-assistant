# Plan: Phase 7 — Slash Command Framework

Sequencing: non-UI machinery first (pure, fully testable without charm), the
interactive overlay UI last. Groups 1–4 land the bedrock and can be verified in
isolation; Group 5 builds the charm surface on top; Group 6 closes the test
matrix.

## Task groups

### Group 1 — Parser & registry (pure core)
- [x] `pa.commands.registry` — runtime-mutable atom plus `reg-command` /
      `registered-commands`, mirroring `pa.tools.registry`. Spec shape
      `{:command :description :arg-spec :->event}` + optional `:hint`.
- [x] `pa.commands.parse` — pure fn: input string → `{:command <name>, :raw-args
      <string>}` when it starts with `/` and names a registered command, else
      `nil`. Handle bare `/`, unknown command, leading whitespace.
- [x] Usage-hint derivation fn: `:hint` if present, else derived from arg-spec
      (`:enum` → `:values` joined `on | off`; `:free-text`/`:select` →
      `:placeholder`; `:none` → blank).

### Group 2 — Argument resolution
- [x] Argument resolution per kind: `:none` (reject surplus args), `:free-text`
      (rest-of-line verbatim, internal spacing preserved), `:enum` (validate
      against allowed set). Invalid/missing args produce a structured usage error,
      not an event.
- [x] Document the `:select` arg-spec contract (`:options-fn` → choices → resolved
      event) as a deferred extension point — no runtime wiring.

### Group 3 — Runtime settings store
- [ ] Add `:settings` to initial runtime state in `pa.state.db`.
- [ ] `set-setting` transition in `pa.state.transitions` (mutated only via `:db`).
- [ ] `setting` selector in `pa.state.queries`.
- [ ] Effect/handler path so a command persists a setting change into runtime
      state via `:db` (event → handler → `:db` effect).

### Group 4 — Dispatch wiring & example commands
- [ ] Branch the Enter path in `pa.ui.app`: a recognised command builds its event
      via `:->event` and dispatches it (bypassing `:user/message` + LLM); a
      non-command submits as today; unknown `/x` or bad args surface an inline
      error/notification — never an LLM call.
- [ ] Ensure command events flow through the normal dispatch → coeffect → handler
      → effect pipeline; each command's target event has a registered handler
      returning declarative effects.
- [ ] `/help` (`:none`) — lists registered commands + descriptions, read from the
      registry (full-panel complement to the inline selector).
- [ ] `/memory <text>` (`:free-text`) — appends text to permanent memory via
      `pa.storage.memory-wisdom`.
- [ ] `/markdown on|off` (`:enum`) — toggles the `:markdown` runtime setting
      (flag only; no rendering).
- [ ] `/clear` (`:none`) — starts a fresh conversation context: dispatches
      `:conversation/clear`, whose handler resets the active `:conversation` in
      runtime state via `:db` so the next LLM turn carries no prior turns.
      Persisted events on disk are untouched; this is a context reset, not a
      screen wipe.

### Group 5 — Interactive command selector (charm UI, last)
- [ ] Overlay list component (new charm surface): scrollable, keyboard-navigable
      list rendered above the input, highlighted row, right-aligned hint column,
      help line for the highlighted row. Built for reuse by the future `:select`
      picker. Lives under `pa.ui.*`.
- [ ] Selector state machine (UI-local, ephemeral, sibling to `pa.ui.input`): open
      on leading `/`, track filter text + highlight index, close on Esc / submit /
      deleting the `/`. Pure transitions where possible.
- [ ] Filtering: match typed prefix against `registered-commands` by command name;
      render name + usage hint per row (+ `:description` help line for the
      highlighted one); handle empty-match state.
- [ ] Keyboard handling in `pa.ui.app` while selector is open: ↑/↓ move highlight,
      Enter/Tab complete highlighted command into the buffer (then normal argument
      entry continues, including the enum ghost placeholder), Esc dismisses. These
      keys are intercepted before history-navigation and text-input paths.
- [ ] Enum ghost placeholder: when input is a recognised `:enum` command awaiting
      its argument (name + trailing space, no token), render the current value
      (`:current-fn`) as dim placeholder text. Pure derivation from model +
      registry; no new runtime state.

### Group 6 — Tests (full matrix)
- [ ] `pa.commands.parse`: `/memory foo  bar` → verbatim args; `/help` → `:none`;
      `hello` and `/unknown` → `nil`/usage error; bare-`/` and leading-whitespace
      edge cases.
- [ ] Arg resolution: `:free-text` preserves internal spacing; `:enum` accepts
      allowed tokens, rejects others with a usage error; `:none` rejects surplus.
- [ ] Enum placeholder: `:markdown` currently `on` → `/markdown ` exposes ghost
      `on`; after `/markdown off`, same input exposes `off` (tracks `:current-fn`).
- [ ] Command selector (via `pa.ui.app` update + selector state machine): `/` opens
      overlay listing all commands with hints; `/mar` filters to `markdown`; ↑/↓
      move highlight; Enter/Tab completes into buffer; Esc dismisses; deleting the
      leading `/` closes it; a normal line never opens the overlay (regression).
- [ ] Usage-hint derivation: `/markdown` → `on | off`; `/memory` → `<text>`; a
      command with explicit `:hint` shows it verbatim (overriding derived).
- [ ] Dispatch branch (via `pa.ui.app` update): `/markdown on` dispatches the
      command event and NOT `:user/message`; a normal line still dispatches
      `:user/message` (regression).
- [ ] Settings round-trip: `/markdown on` → `:db` transition sets `:settings
      :markdown` true; `queries/setting` reads it; `/markdown off` flips it back.
- [ ] `/help` output enumerates exactly the registered commands.

## Notes

- **Ordering / dependencies.** Group 1 (registry) is a prerequisite for
  everything — parse, hint derivation, dispatch, and the selector all read the
  registry. Group 3 (settings) is a prerequisite for the `/markdown` command and
  its round-trip test in Group 4/6. Groups 1–4 are independent of charm and land
  first; Group 5 (overlay + selector) builds on the finished registry and the
  ghost-placeholder derivation. Group 6 tests can be written per-group as each
  lands, but the matrix is only complete once Group 5 is in.
- **Reuse over new structure.** `pa.commands.registry` mirrors `pa.tools.registry`
  verbatim in shape; the selector state machine mirrors `pa.ui.input`'s history
  navigation; `/memory` reuses `pa.storage.memory-wisdom`. No new persistence or
  runtime primitives are introduced.
- **Thin-UI invariant to hold throughout Group 5.** The overlay dispatches no
  runtime event until Enter; it edits only the UI-local buffer + its own selection
  state. Cross-check this while wiring keyboard handling.
- **`:select` is documented, not built.** The extension-point note (Group 2) and
  the overlay's general design (Group 5) leave `/load` one wiring step away once a
  sessions layer exists — but nothing `:select` ships this branch.
- **Dispatch is the guaranteed deliverable; handlers are separable (design
  decision 9).** In Group 4, the required output for each command is the registry
  entry + `:->event` + working dispatch. A command that dispatches an event with
  no handler yet is complete at the framework level (no-op, never falls through to
  the LLM). Handler status is tracked in requirements.md → *Tracked deferred
  functionality*: `/markdown` and `/help` handlers are required by the merge-bar
  tests; `/memory` and `/clear` handlers are thin and may be moved to
  dispatch-only if their behavior expands — the command layer is unaffected
  either way.
