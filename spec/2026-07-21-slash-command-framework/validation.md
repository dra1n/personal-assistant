# Validation: Phase 7 — Slash Command Framework

## Definition of done

The branch is merged and complete when the slash-command bedrock is in place —
a pure parser, a runtime-mutable registry mirroring `pa.tools.registry`,
argument resolution for `:none`/`:free-text`/`:enum`, a runtime settings store,
event-based dispatch wiring, the interactive command-selector overlay, and the
four example commands (`/help`, `/memory`, `/markdown`, `/clear`) — and **the
full Phase 7 test matrix passes** (the agreed merge bar). Commands are dispatched
as first-class runtime events through the existing pipeline; the UI performs no
command side effect directly and dispatches nothing until Enter. `:select` is
documented as a deferred extension point but not wired.

## Checklist

### Tests
- [ ] `pa.commands.parse`: `/memory foo  bar` → command with verbatim args;
      `/help` → `:none`; `hello` and `/unknown` → `nil` / usage error; bare-`/`
      and leading-whitespace edge cases.
- [ ] Arg resolution: `:free-text` preserves internal spacing; `:enum` accepts
      allowed tokens and rejects others with a usage error; `:none` rejects
      surplus args.
- [ ] Enum placeholder: with `:markdown` currently `on`, `/markdown ` (awaiting
      the token) exposes ghost text `on`; after `/markdown off`, the same input
      exposes `off` — placeholder tracks `:current-fn`.
- [ ] Command selector (via `pa.ui.app` update + selector state machine): typing
      `/` opens the overlay listing all registered commands with their usage hint;
      `/mar` filters to `markdown`; ↑/↓ move the highlight; Enter/Tab completes the
      highlighted command into the buffer; Esc dismisses; deleting the leading `/`
      closes it; a normal (non-`/`) line never opens the overlay (regression).
- [ ] Usage-hint derivation: `/markdown` shows `on | off` (from `:values`);
      `/memory` shows `<text>` (from `:placeholder`); a command with explicit
      `:hint` shows it verbatim (overriding the derived value).
- [ ] Dispatch branch (via `pa.ui.app` update): submitting `/markdown on`
      dispatches the command event and NOT `:user/message`; a normal line still
      dispatches `:user/message` (regression).
- [ ] Settings round-trip: `/markdown on` → `:db` transition sets `:settings
      :markdown` true; `queries/setting` reads it; a second `/markdown off` flips
      it back.
- [ ] `/help` output enumerates exactly the registered commands.

### Behaviors
- [ ] A recognised command bypasses `:user/message` and the LLM: submitting
      `/markdown on` never triggers an `:llm/invoke`.
- [ ] An unknown `/x` or bad enum arg surfaces an inline error/notification and
      never triggers an LLM call.
- [ ] Typing `/` opens the selector; the highlighted command's `:description`
      shows as a help line; Enter/Tab completes it into the buffer and inline
      argument entry (including the enum ghost placeholder) continues.
- [ ] `/memory <text>` appends the verbatim text to permanent memory via
      `pa.storage.memory-wisdom`.
- [ ] `/clear` starts a fresh conversation context: the active `:conversation` in
      runtime state is reset so the next LLM turn carries no prior turns;
      persisted events on disk are untouched (context reset, not screen wipe).

### Integration
- [ ] Command events flow through the normal dispatch → coeffect → handler →
      effect pipeline; each target event has a registered handler returning
      declarative effects (no bespoke path).
- [ ] Thin-UI boundary holds: the overlay reads `registered-commands` and edits
      only the UI-local buffer + its own ephemeral selection state; it dispatches
      no runtime event until Enter. Runtime state changes only via `:db`.
- [ ] The selector state machine coexists with `pa.ui.input`'s history navigation
      — history ↑/↓ still works when the selector is closed; selector keys are
      intercepted first when it is open.
- [ ] Non-`/` input still flows through the unchanged `:user/message` → LLM path
      (regression), including multiline/paste behavior from Phase 4e.

## Merge criteria

- All test bullets in the **Tests** section above pass — this is the agreed merge
  bar; the whole Phase 7 test suite is green.
- The four example commands (`/help`, `/memory`, `/markdown`, `/clear`) work
  end-to-end through the event pipeline.
- The thin-UI boundary is preserved: no runtime mutation from the UI, no event
  dispatched until Enter.
- `:select` is documented as a deferred extension point (contract + overlay reuse
  noted); no `:select` command is wired.
- `git status` clean; the full existing test suite still passes (no regressions in
  `:user/message`, history navigation, or multiline input).
