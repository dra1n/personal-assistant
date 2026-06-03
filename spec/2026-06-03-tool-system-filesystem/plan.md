# Plan: Phase 4 — Tool System (Filesystem)

Task groups are capability-themed: shared machinery first, then the access
policy the tools depend on, then the tools, then the bounded LLM tool-call
path, then tests. Groups 1–3 are the core deliverable; Group 4 is the chosen
"layer + manual LLM tool-call" extension; Group 5 is the adversarial +
observability done-bar.

## Task groups

### Group 1 — Tool machinery
- [x] Define a tool registry (`tool-name → {:fn, :schema, :description}`) with a `reg-tool` self-registration entry point — built as a **global atom** in `pa.tools.registry`, mirroring `pa.runtime.registry` (the handler registry, which is also read directly, not passed via ctx). The disk-derived access *policy* will go through the `:runtime` ctx in Group 2; the registry does not.
- [x] Add `:tool/invoke` as a `pa.runtime.executor/execute-effect` method: registry lookup → (policy guard seam: the tool fn receives ctx, so Group 2's resolver plugs in here) → run tool fn → time the call → emit structured Timbre log → dispatch `:tool/result`
- [x] Define the `:tool/result` event shape and register its handler via `reg-handler`: append to runtime state (`:tool/results` via `tr/add-tool-result`) and persist through `:event/store` (replayable as data; tool fn never re-runs on replay)
- [x] Add dry-run mode: `:tool/dry-run?` on the effect (or a ctx-level default) logs the descriptor and emits a `:tool/result` marked `:dry-run` without calling the tool fn
- [x] Emit a `:trace` entry per invocation alongside the log line so calls are visible to tap>/Portal

### Group 2 — Filesystem access policy
- [x] Backfill the Phase 2 bootstrap gap: `create-system-templates!` in `pa.storage.fs/bootstrap!` idempotently writes `system/tools.md` from a `templates/system/` resource when absent (shared `create-templates!` helper with the identity templates)
- [x] Author the default `templates/system/tools.md`: an infrastructure cheat sheet whose fenced ```allowlist block grants `read write` on a `workspace/` sandbox only (not the data root itself) — runtime state stays non-tool-writable; safe default-deny baseline. Bootstrap creates `workspace/`.
- [x] Implement allowlist parsing (`pa.tools.fs.policy/parse-allowlist`): extract the fenced ```allowlist block, parse `<path> <caps...>` lines into `{:path :caps}`, ignoring comments/blanks/prose
- [x] Implement the path resolver (`resolve-path` / `capable?` / `check`): canonicalize the requested path (expand `~`, anchor relatives to the data root, resolve `..` and symlinks via getCanonicalPath), then `deny`-wins → longest-prefix allow → default-deny; `write` does not imply `read`. `check` returns the safe canonical path or throws `:tool/access-denied`.
- [x] Wire the `:tool.fs/policy` Integrant component (sourced from `:storage/fs` root) into the config graph and expose it on the dispatcher's `:runtime` ctx as `:tool.fs/policy` (the seam Group 3 tools consume)
- [x] Sibling-family layout: policy lives at `pa.tools.fs.policy` (`src/pa/tools/fs/policy.clj`), not a generic `pa.tools.policy` — each tool family owns its policy (see [design-notes.md](design-notes.md))

### Group 3 — Filesystem tools (`pa.tools.fs`)
Tools live in `src/pa/tools/fs.clj`; each pulls `:tool.fs/policy` from ctx and calls `policy/check` with its capability before touching disk, operating on the returned canonical path.
- [x] Implement `read-file` (path → contents) with a JSON-Schema arg schema; requires `read` on the resolved path
- [x] Implement `list-dir` (path → sorted, typed entries) with an arg schema; requires `read`; errors on a non-directory
- [x] Implement `write-file` (path + contents → write, creating parent dirs) with an arg schema; requires `write` on the resolved path
- [x] Register all three tools (`:fs/read-file`, `:fs/list-dir`, `:fs/write-file`) in the registry with `:fn`, `:schema`, `:description`; wired into `pa.system` so they register at startup
- [x] Preserve thrown `ex-data` in the `:tool/invoke` error result so `:tool/access-denied` is distinguishable from a generic `:exception` (small Group 1 follow-up motivated by the tools being the first typed throwers)

### Group 4 — Minimal LLM tool-call path (single hop)
- [ ] Extend the LLM provider protocol so a response can surface a tool-call request (tool name + args) instead of final text
- [ ] Implement tool advertisement + tool-call parsing in the OpenAI provider: translate registry `:schema` → function specs, parse returned tool calls
- [ ] Keep the Anthropic provider a protocol-conforming stub under the extended protocol
- [ ] Extend the `:user/message` / `:llm/invoke` path: on a tool-call request dispatch `:tool/invoke`, append the `:tool/result` to the conversation, and dispatch a single follow-up `:llm/invoke` (one explicit hop; no recursion)

### Group 5 — Tests & validation
- [x] Per-tool tests for `read-file` / `list-dir` / `write-file` against a temp filesystem (+ end-to-end through `:tool/invoke`)
- [x] Adversarial resolver tests: `..` traversal, symlink escape, out-of-root, default-deny, longest-prefix precedence, `deny` beats a broader allow
- [x] Per-root capability-matrix tests: a `read`-only root rejects `write-file`; a `write`-only root rejects reads; both flags allow both
- [x] Dry-run tests: assert zero filesystem side effects and that the correct effect descriptor is logged / `:tool/result` marked `:dry-run`
- [~] Observability tests: a replay test asserts the tool fn is not re-executed (done). Still want an explicit assertion that each invocation emits a structured log line.
- [ ] `test.check` property tests for tool argument-schema validation (schema validation is not yet enforced in `:tool/invoke` — pairs with that)
- [ ] Single-hop round-trip test with a fixture/stub provider returning a canned tool call: `:llm/invoke` → `:tool/invoke` → `:tool/result` → follow-up `:llm/invoke` (Group 4)

## Notes

- **Sequencing:** Group 2's resolver is a hard dependency of every Group 3 tool — build the policy + resolver before the tools. Group 1's machinery underpins all groups and should land first. Group 4 depends on Groups 1–3 being in place. Group 5 is written alongside each group, not deferred to the end (the done bar is adversarial, so resolver/capability tests should track the resolver's implementation closely).
- **Naming reconciliation:** the roadmap and earlier specs say `assistant-data/`; the actual data root in code is `pa.storage.fs/pa-home` (`$PA_HOME` or `~/.config/personal-assistant`). `system/tools.md` resolves under that root. Treat `assistant-data/` as conceptual shorthand.
- **Replay safety mirrors existing patterns:** model `:tool/invoke` → `:tool/result` on the proven `:llm/invoke` → `:assistant/response` and `:memory/write` → `:memory/stored` pairs. The effect is the impure hop (skipped on replay); the dispatched event is the persisted, replayable record.
- **Blast-radius caution (Group 4):** the tool-call path touches Phase 3's `:llm/invoke`/response flow. Keep it strictly one hop and behind the extended provider protocol so the streaming-text path (no tool call) is unchanged; the recursive select-loop stays out until Phase 7.
