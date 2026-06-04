# Requirements: Phase 4 — Tool System (Filesystem)

## Goal

Establish the full tool-execution machinery — registry, a declarative
`:tool/invoke` effect, dry-run mode, structured logging, and `:tool/result`
events wired back into the event bus — and exercise it end-to-end with the
three filesystem tools (`read-file`, `list-dir`, `write-file`) only. Every file
operation is gated by a capability-flagged path allowlist sourced from
`system/tools.md`, so reads and writes can have different scopes and sensitive
paths can be explicitly excluded. As a bounded extension, wire a **minimal,
single-hop** path so the LLM can emit a tool call and receive a `:tool/result`
back, without building the autonomous Phase 7 cognition loop. Network-backed
tools (web search, page retrieval, YouTube) are deferred to Phase 4b. Every
durable state change remains a serializable event so the runtime stays
replayable, and tools are never re-executed on replay.

## Scope

### In scope

- **Tool registry** — a component holding `tool-name → {:fn, :schema, :description}`. Tools self-register (mirroring `pa.runtime.registry/reg-handler`); the registry is exposed to handlers/effects as a runtime capability via the dispatcher's `:runtime` context map.
- **`:tool/invoke` effect** — a new `pa.runtime.executor/execute-effect` method, dispatched on `:tool/invoke`. It resolves the tool from the registry, runs the access-policy guard, executes the tool fn, times the call, emits a structured Timbre log line, and dispatches a `:tool/result` event carrying the outcome. Modeled on the existing `:llm/invoke` effect (side-effecting work runs off the dispatcher loop; the authoritative result returns as a dispatched event).
- **`:tool/result` event + handler** — registered via `reg-handler`; appends the result to runtime state and persists through the existing `:event/store` path so replay reconstructs it as data. Replay applies only `:db`, so the tool fn never re-runs on replay (same guarantee `:llm/invoke` already has).
- **Filesystem access policy** — one allowlist of roots, each carrying per-root capability flags (`read`, `write`, `deny`), parsed from `system/tools.md` into an in-memory policy structure at startup. A path resolver canonicalizes the requested path (expand `~`, resolve `..` and symlinks to a real absolute path), matches the **longest-prefix** root, and returns the granted capability set. `deny` always wins; an unmatched path is rejected (default-deny); `write` does not imply `read`.
- **Bootstrap backfill** — `pa.storage.fs/bootstrap!` gains a `create-system-templates!` step that idempotently writes a default `system/tools.md` (from a `templates/system/` resource) when absent. The baseline allowlist grants `read write` on a dedicated `workspace/` sandbox under the data root only — the assistant's own identity/events/sqlite are not tool-writable. The `system/` directory is already created by `create-dirs!`; bootstrap also now creates `workspace/`.
- **Filesystem tools** — `read-file` (path → contents; requires `read`), `list-dir` (path → entries; requires `read`), `write-file` (path + contents → write; requires `write`). Each has a schema for argument validation and registers itself in the tool registry.
- **Dry-run mode** — a flag that makes `:tool/invoke` log the effect descriptor and emit a `:tool/result` marked `:dry-run true` **without** performing the side effect. Proven to cause zero filesystem mutation.
- **Structured logging** — every invocation logs tool name, args, result summary, and duration via Timbre, and emits a `:trace` entry, so each tool call is observable per the mission's inspectability value.
- **Minimal LLM tool-call path (single hop)** — advertise the registry's tool schemas to the OpenAI provider; the provider surfaces either final text (→ `:assistant/response`, unchanged) or a tool-call request. On a request, the runtime dispatches `:tool/invoke`, the resulting `:tool/result` is appended to the conversation, and a single follow-up `:llm/invoke` is dispatched so the model can incorporate it. Exactly **one** explicit round-trip — not a recursive select→invoke→observe loop.
- **Tests** — per-tool tests on a temp filesystem; adversarial resolver tests (`..`, symlink escape, deny-wins, longest-prefix, default-deny); per-root capability matrix; dry-run no-side-effect proof; observability assertions (log line + `:tool/result` event per call; tool not re-run on replay); `test.check` schema-validation property tests; a fixture-provider round-trip test for the single-hop tool call.

### Out of scope

- **Network-backed tools** — web search, page retrieval, YouTube transcripts (Phase 4b; reuse this phase's registry/effect/dry-run/logging/event-bus machinery).
- **The autonomous cognition pipeline** (`interpret → retrieve → plan → tool-select → respond`) and any multi-step / recursive tool loop — **Phase 7**. This phase wires a single explicit tool round-trip only.
- **Tool-result-driven memory extraction/writes** — Phase 5.
- **Edit-in-place / patch tooling and copy** — the toolset is `read-file`, `list-dir`, `write-file`, plus the follow-on `make-dir`, `delete`, `move`, `file-info` (see plan Group 6). In-place/diff editing and copy are still out (copy is read+write; edit-with-diff is its own feature).
- **Per-tool rate limiting, sandboxing beyond the path allowlist, or content-level secret scanning** — the allowlist (incl. `deny` roots) is the safety surface for now.
- **A full Anthropic tool-calling implementation** — the Anthropic provider remains a protocol-conforming stub; only OpenAI gets real tool advertisement.
- **Live-LLM tests in CI** — the single-hop round-trip is validated against a fixture/stub provider, never a real model (per `tech-stack.md`).
- **A general capability model for non-filesystem tools** — capabilities are `read`/`write`/`deny` over filesystem paths; richer permissions arrive with later tool classes.

## Design decisions

1. **`:tool/invoke` is a declarative effect; the tool fn is the only impure part.** Handlers return an effects map containing `:tool/invoke`; the executor resolves the tool, guards it, runs it, and dispatches `:tool/result`. This honors `mission.md` "declarative effects — cognition produces effect descriptions; the runtime executes them," and reuses the exact shape of the existing `:llm/invoke` effect.

2. **A tool call is replayable as data, never as side effect.** The side-effecting `:tool/invoke` is an effect (not replayed); the `:tool/result` event is persisted via `:event/store` and is what replay reconstructs. This mirrors `:memory/write` → `:memory/stored` and `:llm/invoke` → `:assistant/response`, preserving the "every `:db`-touching event is a serializable value, replay never performs I/O" invariant.

3. **One allowlist, per-root capability flags — the single source of truth for filesystem reach.** Resolution: canonicalize first (checks run on the *resolved* path), longest-prefix root wins, `deny` always wins, unmatched → default-deny, `write` does not imply `read`. This makes the security boundary explicit and inspectable (`mission.md`: "explicit over magic," "inspectable state — human-readable Markdown").

4. **The allowlist lives in `system/tools.md`.** This file was already in the planned `assistant-data/` layout but had no consumer; Phase 4 gives it its first one. It doubles as the infrastructure cheat sheet (device info, tool paths) from the original project idea. Bootstrap backfills a safe default because Phase 2 never generated it.

5. **The baseline allowlist is `read write` on a `workspace/` sandbox only.** Out of the box the file tools can touch only `<data-root>/workspace/`; the assistant's own identity, event log, and sqlite db are NOT tool-writable, and everything else is default-denied until the user widens `system/tools.md`. The runtime manages its own state through the storage layer (not the file tools), so this narrow default does not constrain the assistant. The allowlist is anchored to the data root — `$PA_HOME` or `~/.config/personal-assistant` (see `pa.storage.fs/pa-home`) — so it behaves identically regardless of where the assistant is launched. The roadmap's `assistant-data/` is conceptual shorthand for that data root.

6. **Canonicalize before checking — never trust the literal argument.** `..` traversal and symlink escape are defeated by resolving to a real absolute path *before* matching roots. This is the load-bearing safety step and gets explicit adversarial tests.

7. **The registry and policy are Integrant components exposed through the dispatcher `:runtime` context.** Following the established pattern (`:llm-provider`, `:write-memory!`, `:emit-delta!` are all curated `:runtime` capabilities built once at dispatcher start), the tool registry and resolved policy are added to that map, sourced from `:storage/fs` for the data root. Handlers never reach for them directly — they arrive via coeffects.

8. **The LLM tool-call path is a single explicit hop, deliberately not the Phase 7 loop.** One request → `:tool/invoke` → `:tool/result` → one follow-up `:llm/invoke`. No recursion, no planner. This satisfies the chosen scope ceiling (LLM can call a tool and see the result) while keeping Phase 7's cognition pipeline as the home for autonomous multi-step tool use — and bounds the blast radius into Phase 3's response path.

9. **OpenAI gets real tool advertisement; Anthropic stays a stub.** The provider protocol is extended so a response can surface a tool-call request instead of final text; only the OpenAI provider implements it (translating registry `:schema` → function specs). Adding real Anthropic tool-calling later touches one namespace — the mission's "one layer only" criterion.

10. **Test only up to the LLM boundary.** Tool fns, the resolver, the capability matrix, dry-run, and schema validation are deterministic `clojure.test` / `test.check` units. The single-hop round-trip is driven by a fixture/stub provider that returns a canned tool call; a real model is exercised only in a manual REPL smoke check, never CI.

## Context

Phases 0–3 are complete: an event-driven runtime (dispatch → coeffect injection → interceptor chain → declarative effects → executor), a durable EDN event log with replay, canonical Markdown memory with a rebuildable SQLite index, identity loaded into a `:identity` context map, and a streaming LLM turn (`:user/message → assemble → :llm/invoke → :assistant/response`). This phase plugs a tool layer into that substrate without violating its invariants. Key existing seams it builds on:

- **Effects** are the `pa.runtime.executor/execute-effect` multimethod, dispatched on an effect-type keyword; `execute-effects!` iterates the effects map a handler returns. `:tool/invoke` is a new method here, alongside `:llm/invoke`, `:memory/write`, `:event/store`.
- **Runtime capabilities** are a curated `:runtime` map built once in `pa.runtime.dispatcher/init-key` (`dispatch!`, `store-event!`, `write-memory!`, `index-memory!`, `llm-provider`, `emit-delta!`) and injected into every handler via `pa.runtime.coeffects/inject-coeffects`. The tool registry and resolved policy are added to this map.
- **Handlers** self-register through `pa.runtime.registry/reg-handler` (2- or 3-arity, the latter for per-handler interceptors) in `pa.runtime.handlers`. `:tool/result` is registered here; it stores and applies, exactly like `:assistant/response`.
- **Bootstrap** is `pa.storage.fs/bootstrap!` → `create-dirs!` (already makes `system/`), `create-identity-templates!` (copies from `templates/identity/*` resources), `create-event-log!`. The backfill adds a sibling `create-system-templates!` for `system/tools.md`. The data root comes from `pa.storage.fs/pa-home` (`$PA_HOME` or `~/.config/personal-assistant`).
- **The LLM effect** (`:llm/invoke`) runs `provider/stream` on a `future`, pushes deltas to the UI side-channel, and dispatches `:assistant/response` with the full text. The tool-call extension widens this so the provider can instead surface a tool-call request that drives `:tool/invoke`.
- **Persistence/replay** is event-driven: anything durable goes through `:event/store`; replay reconstructs `:db` from `events.edn` alone and applies only `:db` effects, so neither LLM calls nor tool calls fire on replay.
