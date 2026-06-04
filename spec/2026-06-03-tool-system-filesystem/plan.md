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
- [x] Extend the LLM provider protocol: `invoke`/`stream` now return `{:content :tool-calls}` (provider/text-result helper) instead of a bare string
- [x] OpenAI tool advertisement + tool-call parsing: `request-body` translates advertised tools → function specs (slash-encoded names); streaming assembles `tool_calls` fragments and non-streaming parses `message.tool_calls`; tool/assistant-tool-call turns serialize to OpenAI message shapes
- [x] Anthropic provider remains a protocol-conforming stub (throws; unchanged)
- [x] Extend the path: `:user/message` advertises tools; the `:llm/invoke` effect branches to `:assistant/tool-call` on a tool request; that handler records the request turn and emits `:tool/invoke {:tool/call-id :llm/follow-up?}`; `:tool/result` appends a `:role :tool` turn and re-invokes the LLM with **no tools** (single hop enforced structurally)
- [x] Support multiple tool calls in one turn: the calls run **sequentially** — each `:tool/result` fires the next unresolved call (computed from conversation state by `unresolved-tool-calls`), and the LLM is re-invoked only once every call has a result, so the assistant message's N `tool_calls` are matched by N tool results (fixes an OpenAI 400)

### Group 5 — Tests & validation
- [x] Per-tool tests for `read-file` / `list-dir` / `write-file` against a temp filesystem (+ end-to-end through `:tool/invoke`)
- [x] Adversarial resolver tests: `..` traversal, symlink escape, out-of-root, default-deny, longest-prefix precedence, `deny` beats a broader allow
- [x] Per-root capability-matrix tests: a `read`-only root rejects `write-file`; a `write`-only root rejects reads; both flags allow both
- [x] Dry-run tests: assert zero filesystem side effects and that the correct effect descriptor is logged / `:tool/result` marked `:dry-run`
- [~] Observability tests: a replay test asserts the tool fn is not re-executed (done). Still want an explicit assertion that each invocation emits a structured log line.
- [ ] `test.check` property tests for tool argument-schema validation (schema validation is not yet enforced in `:tool/invoke` — pairs with that)
- [x] Single-hop round-trip test with a stub provider (`tool_call_test`): the `:llm/invoke` tool-call branch, the `:assistant/tool-call` and `:tool/result` handlers, and a replay proving the four-event turn (`:user/message` → `:assistant/tool-call` → `:tool/result` → `:assistant/response`) reconstructs the conversation with no LLM or tool execution

## Notes

- **Sequencing:** Group 2's resolver is a hard dependency of every Group 3 tool — build the policy + resolver before the tools. Group 1's machinery underpins all groups and should land first. Group 4 depends on Groups 1–3 being in place. Group 5 is written alongside each group, not deferred to the end (the done bar is adversarial, so resolver/capability tests should track the resolver's implementation closely).
- **Naming reconciliation:** the roadmap and earlier specs say `assistant-data/`; the actual data root in code is `pa.storage.fs/pa-home` (`$PA_HOME` or `~/.config/personal-assistant`). `system/tools.md` resolves under that root. Treat `assistant-data/` as conceptual shorthand.
- **Replay safety mirrors existing patterns:** model `:tool/invoke` → `:tool/result` on the proven `:llm/invoke` → `:assistant/response` and `:memory/write` → `:memory/stored` pairs. The effect is the impure hop (skipped on replay); the dispatched event is the persisted, replayable record.
- **Blast-radius caution (Group 4):** the tool-call path touches Phase 3's `:llm/invoke`/response flow. Keep it strictly one hop and behind the extended provider protocol so the streaming-text path (no tool call) is unchanged; the recursive select-loop stays out until Phase 7.
- **Known follow-ups after Group 4:**
  - *UI rendering of tool turns* — the conversation view renders a `:role :tool` turn with a faint generic label and an assistant tool-call turn (empty content) as a bare label. It doesn't crash, but a nicer rendering (e.g. "→ called fs/read-file(…)") is deferred.
  - *Parallel tool execution* — multiple tool calls in one turn now run, but **sequentially** (one `:tool/invoke` per result, since a handler's effects map has a single `:tool/invoke` key). True parallel fan-out (batching the effect / fan-in counting) belongs with the Phase 7 cognition pipeline.
  - *Schema enforcement + `test.check`* — args are advertised but not yet validated against `:schema` inside `:tool/invoke`; the property tests pair with that enforcement.
