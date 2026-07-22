# Requirements: Phase 7 — Slash Command Framework

## Goal

Build a robust, extensible foundation for slash commands: commands typed at the
input, parsed before the message reaches the LLM, and dispatched as first-class
runtime events through the existing dispatch → coeffect → handler → effect
pipeline. The phase delivers the bedrock (pure parser, runtime-mutable registry,
argument resolution for three kinds), an **interactive command selector** (a
completion overlay that opens the moment `/` is typed), a new reusable charm
overlay/list UI surface, a runtime settings store, and four working example
commands that exercise the machinery end-to-end.

## Scope

### In scope

- **Parsing** — `pa.commands.parse`: pure fn, input string → `{:command <name>,
  :raw-args <string>}` when it starts with `/` and names a registered command,
  else `nil`. Handles bare `/`, unknown command, leading whitespace.
- **Registry** — `pa.commands.registry`: runtime-mutable atom + `reg-command` /
  `registered-commands`, mirroring `pa.tools.registry`. Spec shape
  `{:command :description :arg-spec :->event}` plus optional `:hint` override.
- **Usage-hint derivation** — `:hint` if present, else derived from arg-spec
  (`:enum` → `:values` joined `on | off`; `:free-text`/`:select` → `:placeholder`;
  `:none` → blank).
- **Argument resolution** per kind: `:none`, `:free-text` (rest-of-line verbatim,
  spacing preserved), `:enum` (validate against allowed set → structured usage
  error on miss/unknown). `:select` shape is documented but not wired.
- **Enum ghost placeholder** — when a recognised `:enum` command awaits its
  argument (name + trailing space, no token), the input renders the current value
  (`:current-fn`) as dim placeholder text. Pure derivation from model + registry;
  no new runtime state.
- **Command selector (interactive)** — a new reusable charm **overlay list
  component** (scrollable, keyboard-navigable, highlighted row, right-aligned hint
  column, help line for the highlighted row), plus a UI-local ephemeral selector
  state machine (sibling to `pa.ui.input`'s history navigation). Opens on leading
  `/`, filters by command-name prefix as more is typed, ↑/↓ move highlight,
  Enter/Tab complete the highlighted command into the buffer, Esc / deleting the
  `/` dismisses.
- **Dispatch wiring** — branch the Enter path in `pa.ui.app`: a recognised command
  builds its event via `:->event` and dispatches it (bypassing `:user/message` and
  the LLM); a non-command submits as today. Unknown `/x` or bad args surface an
  inline error/notification — never an LLM call.
- **Runtime settings store** — add `:settings` to initial runtime state
  (`pa.state.db`), a `set-setting` transition in `pa.state.transitions` (mutated
  only via `:db`), and a `setting` selector in `pa.state.queries`; plus the
  effect/handler path so a command persists a setting change through `:db`.
- **Four example commands**: `/help` (`:none`), `/memory <text>` (`:free-text`),
  `/markdown on|off` (`:enum`), `/clear` (`:none`).
- Full Phase 7 test matrix from the roadmap.

### Out of scope

- The `:select` argument kind wired to a real command. Its arg-spec contract
  (`:options-fn` → choices → resolved event) and its reuse of the overlay
  component are **documented as a deferred extension point**, but no concrete
  `:select` command ships — `/load` needs a sessions storage layer that does not
  yet exist.
- A **sessions** storage layer (save/name/restore conversations).
- `/reflect` and `/model` commands (illustrative in the roadmap, not in this
  branch's command set).
- Actual markdown **rendering** in the terminal — `/markdown` only flips the
  `:markdown` flag; rendering is a separate future feature.
- Saving/naming/restoring conversations — `/clear` starts a fresh context but does
  not persist or reload named sessions; that is the deferred sessions layer.
- Persisting settings back to `<PA_HOME>/config.edn` — in-session settings suffice
  for the bedrock; persistence is a later nice-to-have.
- `@`-style resource mention overlay — noted as a future reuse of the same widget,
  not built here.

## The command spec

A command is a plain map registered via `reg-command`. The shape, drawn from the
roadmap, is `{:command :description :arg-spec :->event}` plus an optional `:hint`
override. `:arg-spec` is the polymorphic part — its `:kind` selects the argument
shape and drives both resolution and the selector's usage hint. The examples
below are annotated with their status in this branch (**ship** / **illustrative**
/ **deferred**).

```clojure
;; :free-text — the rest of the line, verbatim, as one string.  [ship]
{:command     "memory"
 :description "Append a note to the assistant's permanent memory"
 :arg-spec    {:kind :free-text :required true :placeholder "<text>"}
 :->event     (fn [args] {:event/type :memory/note :text (:text args)})}

;; :enum — a fixed, validated set of tokens; hint derives from :values.  [ship]
{:command     "markdown"
 :description "Toggle terminal markdown rendering"
 :arg-spec    {:kind       :enum
               :values     ["on" "off"]         ; hint derives from these → "on | off"
               :current-fn (fn [db] (if (queries/setting db :markdown) "on" "off"))}
 :->event     (fn [args] {:event/type :settings/set :key :markdown :value (= "on" (:token args))})}

;; :none — no argument; blank hint.  [ship]
{:command     "clear"
 :description "Start a fresh conversation context (persisted events are kept)"
 :arg-spec    {:kind :none}                      ; no argument → blank hint
 :->event     (fn [_] {:event/type :conversation/clear})}

;; :none — reads the registry to list commands.  [ship]
{:command     "help"
 :description "List the available slash commands"
 :arg-spec    {:kind :none}
 :->event     (fn [_] {:event/type :command/help})}

;; :enum with a curated :hint override (long value list).  [illustrative — not this branch]
{:command     "model"
 :hint        "gpt5.4, gpt5-mini, …"            ; curated, overrides the derived hint
 :description "Switch the active LLM model"
 :arg-spec    {:kind :enum :values [...] :current-fn ...}
 :->event     (fn [args] {:event/type :settings/set :key :model :value (:token args)})}

;; :free-text, optional, with a curated :hint.  [illustrative — not this branch]
{:command     "reflect"
 :hint        "[topic]"                          ; curated: optional free-text
 :description "Ask the assistant to reflect now, optionally on a topic"
 :arg-spec    {:kind :free-text :required false :placeholder "[topic]"}
 :->event     (fn [args] {:event/type :cognition/reflect :topic (:text args)})}

;; :select — a dynamic, interactive argument picker.  [deferred — needs the sessions layer]
{:command     "load"
 :description "Load a previous conversation session"
 :arg-spec    {:kind       :select
               :options-fn (fn [db] (sessions/list db))   ; [{:label .. :value ..}]
               :placeholder "<session>"}          ; hint until options resolve
 :->event     (fn [args] {:event/type :session/load :id (:value args)})}
```

Notes on the shape:

- The selector renders each command as its name plus a right-aligned **usage
  hint** — the possible values or argument shape (`on | off`, `<text>`,
  `gpt5.4, gpt5-mini, …`). The hint **derives from the arg-spec by default**
  (`:enum` → `:values`; `:free-text`/`:select` → `:placeholder`; `:none` →
  blank); the optional `:hint` string overrides it when a curated label reads
  better. The fuller `:description` is the help line for the highlighted command
  (and drives `/help`).
- `:current-fn` (on `:enum`) reads the present value from runtime state; it feeds
  the dim ghost/placeholder shown once the command is recognised and a space is
  typed (`/markdown ⎵` → dim `on`).
- `model` and `reflect` are illustrative only — they exercise the curated-`:hint`
  and optional-`:free-text` paths but are **not** in this branch's command set.
  `load` shows the full `:select` shape but is **deferred** (see Out of scope).

## Design decisions

1. **Commands are events.** A parsed command becomes a runtime event with a
   registered handler returning declarative effects — identical to the
   `:user/message` pipeline. The UI never performs a command's side effect
   directly. Preserves observability, traceability, replayability, and the
   thin-UI boundary (mission.md: *Explicit over magic*, *Replayable*).
2. **Registry mirrors the tool registry.** `pa.commands.registry` is a
   runtime-mutable atom of `name → command-spec`, exactly like
   `pa.tools.registry`. Adding a command is one `reg-command` entry — satisfies
   the success-picture goal "adding a capability touches one layer only."
3. **Parsing is pure and lives at the single input choke point.** Enter in
   `pa.ui.app` already turns the buffer into a `:user/message`; parsing branches
   there. A leading `/` routes to command handling; everything else stays the
   existing `:user/message` path, unchanged.
4. **Non-UI machinery is built and tested before the overlay.** Parser, registry,
   arg resolution, settings store, and dispatch wiring are pure/data-driven and
   fully testable without charm. The overlay component + selector state machine —
   the riskiest, charm-dependent piece — is layered on last, on top of a proven
   pipeline. (Sequencing decision from interview.)
5. **The overlay stays a thin client.** While open it reads from
   `registered-commands` and edits only the UI-local input buffer and its own
   ephemeral selection state (a small state machine). No runtime event is
   dispatched until the completed command is submitted with Enter.
6. **Settings live in runtime state, not config.** `:settings` is a map in runtime
   state changed only via the `:db` effect through a `pa.state.transitions` fn (no
   direct mutation), read by consumers via `pa.state.queries`. `pa.config` remains
   read-once/read-only.
7. **Hint derives by default, overrides by exception.** The selector's usage hint
   comes from the arg-spec unless a curated `:hint` string reads better (long enum
   value lists, optional arguments).
8. **Command set for this branch: `/help`, `/memory`, `/markdown`, `/clear`.**
   These exercise `:none`, `:free-text`, and `:enum` plus the selector; `:select`
   is deferred. (Scope decision from interview.)
9. **The command framework's guaranteed deliverable is dispatch — handler
   availability is a separate concern.** A slash command's maximum job in this
   phase is to parse → build its event via `:->event` → dispatch it into the
   runtime. Whether a registered *handler* exists for that target event is
   orthogonal: a command that dispatches an event with no handler yet is still
   complete at the framework level (the dispatch is a no-op, not an error, and
   never falls through to the LLM). Any such not-yet-handled target event is
   recorded in **Tracked deferred functionality** below so the next phase can
   implement the behavior without reworking the command layer. This keeps the
   framework decoupled from downstream cognition/runtime features and lets
   commands be registered ahead of the handlers they will eventually drive.

## Context

- **Namespace layout already matches the spec's assumptions.** `pa.state.db`,
  `pa.state.queries`, `pa.state.transitions`, `pa.ui.app`, `pa.ui.input`, and
  `pa.tools.registry` all exist — the new work slots into established patterns
  rather than inventing structure.
- **`pa.tools.registry` is the template** for `pa.commands.registry` (mutable
  atom, `reg-*` / `registered-*` accessors).
- **`pa.ui.input`'s history navigation is the sibling pattern** for the selector
  state machine: UI-local, ephemeral, pure transitions where possible, keys
  intercepted in `pa.ui.app` before the text-input path.
- **`/memory` reuses the Phase 6 wisdom writer** (`pa.storage.memory-wisdom`) to
  append permanent memory — no new persistence path invented.
- **`/markdown` and `/clear` reference existing runtime concepts** — the new
  settings store, and a `:conversation/clear` event that starts a fresh
  conversation context (a new working session): it resets the active
  `:conversation` in runtime state so subsequent LLM turns carry no prior turns.
  This is a context reset, not a screen wipe — persisted events on disk are
  untouched, and it is distinct from the deferred sessions layer (which
  saves/names/restores conversations; `/clear` only begins a new one).
- **Merge bar (from interview): the full Phase 7 test matrix green.** Every test
  bullet in the roadmap's Phase 7 Tests section must pass; that is the definition
  of done for this branch.

## Tracked deferred functionality

Per design decision 9, a command is complete once it dispatches its event; the
target-event handler is a separate deliverable. This table tracks each example
command's target event and whether its handler is implemented in this phase.
Rows marked *deferred* ship the command (registry entry + `:->event` + dispatch)
but leave the handler for a later phase — the command dispatches into the runtime
as a no-op until then. Update the status column as handlers land.

| Command             | Target event          | Handler status this phase |
| ------------------- | --------------------- | ------------------------- |
| `/help`             | `:command/help`       | Implemented — handler reads `registered-commands`; required by the `/help` test |
| `/markdown on\|off` | `:settings/set`       | Implemented — the Group 3 settings path; required by the settings round-trip test |
| `/memory <text>`    | `:memory/note`        | Implemented (thin) — appends via `pa.storage.memory-wisdom`; deferrable if it grows beyond a straight append |
| `/clear`            | `:conversation/clear` | Implemented (thin) — resets `:conversation` via `:db`; deferrable if "fresh context" turns out broader than resetting `:conversation` |

Notes:
- Only `/markdown` and `/help` handlers are **required** by the merge-bar test
  matrix. `/memory` and `/clear` handlers are in scope as thin implementations but
  may be reduced to dispatch-only (moved to *deferred*) if their behavior expands
  beyond a one-step effect — the command layer does not change either way.
- Any future command can be registered now and left dispatch-only; add a row here
  rather than blocking on its handler. `:select` / `/load` remains the largest
  deferred case (needs the sessions layer).
