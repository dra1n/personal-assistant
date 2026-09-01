# Plan: Phase 9 — MCP Support

## Task groups

Each group folds in its own tests — a group is done only when it is tested.

### Group 1 — Configuration & policy
- [x] Add a commented `:mcp {:servers {...}}` example block to `resources/templates/config.edn` — the `:playwright` entry, `:enabled? false`, matching the style of the existing commented `:llm` / `:portal` / `:settings` blocks
- [x] Wire `:tool.mcp/policy {:servers #setting [:mcp :servers]}` into `resources/system.edn`, mirroring `:llm/provider` and `:pa.observability/portal`
- [x] `pa.tools.mcp.policy` — `ig/init-key` normalizes and validates the configured `:servers` map into `{:servers {name -> {:transport :command :args :env :enabled? :connect-timeout-ms}}}`; a missing `:mcp` key yields no servers; a malformed entry is dropped with a warning, never a startup crash
- [x] Define the `connect-timeout-ms` default (15000) as a policy-level setting, per-server overridable
- [x] Tests: fixture `:servers` config map → assert normalization, `:enabled?` filtering, malformed-entry-dropped, and empty-config-yields-no-servers

### Group 2 — JSON-RPC / stdio transport
- [x] `pa.tools.mcp.client` — spawn a subprocess via `ProcessBuilder` from `:command` + `:args` + `:env`, with stderr piped to Timbre
- [x] Newline-delimited JSON-RPC 2.0 read/write over stdin/stdout, with a dedicated reader thread demuxing responses to in-flight requests by numeric id via promises (same async-hop shape as `:llm/invoke` / `:extraction/classify` in `pa.runtime.executor`)
- [x] Implement the `initialize` handshake (`protocolVersion`, `clientInfo`, `capabilities`), capture the server's declared `tools` / `resources` / `prompts` capabilities, then send `notifications/initialized`
- [x] Per-server connect timeout: a timed-out or erroring handshake logs a warning and leaves that server disconnected, without delaying other servers or app startup
- [x] Clean shutdown: close stdin (EOF), wait briefly for exit, then `.destroyForcibly`
- [x] Tests: JSON-RPC framing round-trip against a fake stdio pair (`PipedInputStream` / `PipedOutputStream`), never a real subprocess — request written, correlated response resolved
- [x] Tests: fixture `initialize` response → capabilities parsed correctly; a handshake that never responds marks the server disconnected without throwing or blocking

### Group 3 — Protocol wrappers
- [x] Thin JSON-RPC request/response wrappers over the transport: `tools/list`, `tools/call`, `resources/list`, `resources/read`, `prompts/list`, `prompts/get`
- [x] JSON→EDN keywordization at the transport boundary, so callers above never see raw JSON
- [x] Tests: each wrapper against a fake client — request shape asserted, fixture response decoded

### Group 4 — `:mcp/registry` Integrant component
- [ ] `:mcp/registry` — `init-key` takes `{:policy #ig/ref :tool.mcp/policy}`, connects to every enabled server concurrently, registers each connection's tools and prompts, and caches its resource/prompt listings
- [ ] `halt-key!` disconnects every connected client
- [ ] Wire `:mcp/registry` into `pa.runtime/dispatcher`'s ctx map alongside `:tool.fs/policy`, so tool, resource, and prompt fns can reach live clients
- [ ] Tests: startup resilience — one configured server with a bad command does not prevent other enabled servers from connecting or the system from starting
- [ ] Tests: `halt-key!` disconnects every client, including after a partially failed startup

### Group 5 — Tools
- [ ] `pa.tools.mcp` — for each connected server, translate every `tools/list` entry (`name`, `description`, `inputSchema`) into a `reg-tool` under `:mcp.<server>/<tool-name>`
- [ ] Use `inputSchema` directly as the tool's `:schema` — `pa.tools.registry/validate-args` already speaks JSON-Schema-shaped EDN, so only keywordization is needed
- [ ] The registered `:fn` proxies to `tools/call` on that server's client; an MCP error response is thrown as `ex-info` with `{:type :mcp/tool-error}` so it surfaces as a normal `:tool/status :error`
- [ ] Confirm no further LLM tool-calling wiring is needed — `registry/advertise` enumerates the whole registry and the Phase 4b multi-hop loop is tool-source-agnostic (if either assumption fails, fix the general mechanism, don't special-case MCP)
- [ ] Tests: fixture `tools/list` → each tool `reg-tool`'d under its namespaced key with the JSON Schema carried through as `:schema`
- [ ] Tests: `:tool/invoke` on an `:mcp.*` tool → fake client returns a `tools/call` result → `:tool/status :ok`; an MCP error response → `:tool/status :error`

### Group 6 — Resources & the `@`-mention
- [ ] `pa.tools.mcp/list-resources` and `read-resource` — thin wrappers returning `{:uri :name :mime-type :content}` per connected server
- [ ] `@`-mention affordance in the terminal input: typing `@` opens the same overlay used by the command selector, populated from every connected server's cached `resources/list`, rows labelled `server:uri`
- [ ] Reuse the Phase 7 selector state machine unmodified — `@` triggers it the way `/` does in `pa.ui.app`, sharing filter/highlight/Esc mechanics from `pa.ui.selector`
- [ ] Selecting a resource reads it and inserts its content into the outgoing message as attached context — not a tool call
- [ ] Tests: resource listing and read against a fake client
- [ ] Tests: `@` selector, mirroring the Phase 7 `/` selector tests — typing `@` opens the overlay populated from fixture resources; selecting one inserts its content into the input buffer

### Group 7 — Prompts as slash commands
- [ ] `pa.tools.mcp/list-prompts` and `get-prompt` — thin wrappers; `get-prompt` returns the server-rendered message list for the given arguments
- [ ] Register each connected server's prompts as dynamic slash commands via `reg-command`, named `<server>.<prompt-name>` — the first concrete user of the `:select`-picker extension point Phase 7 documented but left unbuilt
- [ ] Argument mapping: zero declared arguments → `:none`; one → `:free-text` (value passed straight through); 2+ named arguments are skipped with a warning and documented as a deferred limitation
- [ ] `->event` for an MCP-prompt command dispatches `:mcp/prompt-invoke`; its handler calls `get-prompt`, appends the returned messages to the conversation, and continues the turn via `:llm/invoke` — the same path a `:user/message` turn takes
- [ ] Register `:mcp/prompt-invoke` in the event registry/spec alongside existing events
- [ ] Tests: fixture `prompts/list` → commands registered under the right names with the right argument kinds; a 2+-argument prompt is skipped, not crashed on
- [ ] Tests: invoking a prompt command dispatches `:mcp/prompt-invoke` → handler calls `get-prompt` and feeds the returned messages into `:llm/invoke`

### Group 8 — playwright-mcp end-to-end
- [ ] Confirm the shipped `:playwright` template entry is present, commented out, and `:enabled? false`, so no subprocess spawns and no package downloads until a user opts in
- [ ] Manual verification: flip `:enabled? true`, start the system, confirm `registered-tools` includes the `:mcp.playwright/*` tools (`browser_navigate`, `browser_click`, `browser_snapshot`, …)
- [ ] Manual verification: a tool-calling turn ("open example.com and tell me the page title") completes end-to-end through `:tool/invoke` → `:tool/result`
- [ ] Record the manual session outcome (tool list + the completed turn) in the PR description

### Group 9 — Roadmap & docs
- [ ] Tick off the Phase 9 items in `spec/roadmap.md` as each group lands
- [ ] Note the 2+-argument-prompt limitation and the stdio-only transport decision where a future reader will find them (roadmap Phase 9 section + `ideas-backlog.md` entry for remote transports)

## Notes

- **Sequencing.** Groups 1 → 2 → 3 → 4 are a hard chain: policy feeds the client, the client
  feeds the wrappers, the wrappers feed the registry. Groups 5, 6, and 7 all depend on
  Group 4 but are independent of each other and can land in any order. Group 8 needs
  5 at minimum (tools are what playwright exercises); Group 9 trails everything.
- **Earliest useful milestone.** Groups 1–5 make playwright-mcp genuinely usable. If the
  branch grows uncomfortable, that's the natural split point — resources and prompts are
  additive surfaces that can merge separately without leaving anything half-wired.
- **Group 6 is the only UI-layer work.** It touches `pa.ui.app` and `pa.ui.selector`;
  everything else lives under `pa/tools/mcp`. Keeping the selector state machine unmodified
  is the constraint that protects the Phase 7 `/` behavior — if `@` seems to require
  changing `pa.ui.selector`, that's a signal to reconsider the approach, not to fork it.
- **Group 5's last item is a verification, not an implementation.** If `advertise` or the
  multi-hop loop turns out to need changes, that's a general-mechanism bug surfaced by MCP,
  and it should be fixed generally.
- **Concurrency lives in two places only** — the client's reader thread (Group 2) and the
  registry's concurrent connect (Group 4). Both mirror `pa.runtime.executor`'s existing
  promise-based hop; resist introducing a third idiom.
- **No test spawns a real subprocess.** Real-server confidence comes exclusively from
  Group 8's manual run.
