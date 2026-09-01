# Validation: Phase 9 — MCP Support

## Definition of done

The system can connect to local stdio MCP servers declared in `config.edn`, and each
connected server's tools, resources, and prompts are reachable through the existing
machinery: tools as `:mcp.<server>/<tool-name>` registry entries invoked via
`:tool/invoke` → `:tool/result`, resources through an `@`-mention overlay backed by the
unmodified Phase 7 selector, and prompts as `<server>.<prompt-name>` slash commands
dispatching `:mcp/prompt-invoke`. Every configured server ships disabled, so a default
install spawns no subprocess and downloads nothing. A server that fails to connect degrades
to contributing nothing and never affects startup or another server. The full suite passes
against fakes, and one recorded manual `playwright-mcp` session proves the wire format
against a real server.

## Checklist

### Tests

- [x] `pa.tools.mcp.policy`: fixture `:servers` config → normalization asserted; `:enabled?
      false` servers excluded; a malformed entry dropped with a warning rather than a throw;
      a missing `:mcp` key yields zero servers
- [x] `pa.tools.mcp.client`: JSON-RPC framing round-trip against a `PipedInputStream` /
      `PipedOutputStream` fake stdio pair — request written, correlated response resolved by
      numeric id
- [x] Handshake: fixture `initialize` response → `tools` / `resources` / `prompts`
      capabilities parsed correctly
- [x] Handshake timeout: a server that never responds is marked disconnected without
      throwing and without blocking other servers or the test
- [ ] Protocol wrappers: `tools/list`, `tools/call`, `resources/list`, `resources/read`,
      `prompts/list`, `prompts/get` — request shape asserted, fixture response decoded to
      keywordized EDN
- [ ] Tool registration: fixture `tools/list` → each tool `reg-tool`'d under
      `:mcp.<server>/<tool-name>` with `inputSchema` carried through verbatim as `:schema`
- [ ] Tool proxy success: `:tool/invoke` on an `:mcp.*` tool → fake client returns a
      `tools/call` result → `:tool/status :ok`
- [ ] Tool proxy failure: an MCP error response → `ex-info` `{:type :mcp/tool-error}` →
      `:tool/status :error`, indistinguishable in shape from a native tool failure
- [ ] Resources: listing and read against a fake client return `{:uri :name :mime-type
      :content}`
- [ ] `@` selector (mirroring the Phase 7 `/` selector tests): typing `@` opens the overlay
      populated from fixture resources; selecting one inserts its content into the input
      buffer
- [ ] Prompt registration: fixture `prompts/list` → commands registered as
      `<server>.<prompt-name>`; zero-arg → `:none`, one-arg → `:free-text`; a 2+-arg prompt
      is skipped with a warning, not a crash
- [ ] Prompt dispatch: invoking a prompt command dispatches `:mcp/prompt-invoke` → handler
      calls `get-prompt` and feeds the returned messages into `:llm/invoke`
- [ ] Startup resilience: one configured server with a bad command does not prevent other
      enabled servers from connecting or the system from starting
- [ ] The existing suite passes unchanged — no regressions in the tool registry, the Phase 7
      `/` command selector, the multi-hop tool-call loop, or system start/stop smoke tests

### Behaviors

- [ ] With no `:mcp` key in `config.edn`, the system starts exactly as before: no MCP tools
      registered, no subprocess, no added startup latency
- [ ] With the shipped template (`:playwright` commented out, `:enabled? false`), the system
      still spawns nothing and downloads nothing
- [ ] With `:playwright` enabled, `registered-tools` includes the `:mcp.playwright/*` tools
      (`browser_navigate`, `browser_click`, `browser_snapshot`, …), namespaced by server
- [ ] An MCP tool call appears in the event log as a normal `:tool/invoke` → `:tool/result`
      pair — observable, structured-logged, and replayable like any native tool
- [ ] Server provenance is visible: MCP tools are distinguishable by name in logs, in
      `/help`, and in the tool advertisement sent to the LLM
- [ ] Two servers exposing an identically named tool do not collide
- [ ] A server killed mid-session degrades gracefully — its tool calls fail as
      `:tool/status :error`, and the rest of the assistant keeps working
- [ ] `ig/halt-key!` leaves no orphaned subprocesses: stdin closed, process exited (or
      forcibly destroyed) for every connected server
- [ ] Typing `@` in the terminal opens the resource overlay; selecting a resource inserts
      its content as attached context, and the `/` command selector still behaves exactly as
      it did in Phase 7

### Integration

- [ ] `:tool.mcp/policy` and `:mcp/registry` initialize and halt cleanly as part of the full
      Integrant system, in both enabled and disabled configurations
- [ ] `:mcp/registry` reaches the dispatcher ctx map alongside `:tool.fs/policy`, and tool,
      resource, and prompt fns can reach live clients through it
- [ ] `:mcp` config resolves through the existing `#setting [path]` aero plumbing — no new
      config-loading code
- [ ] `registry/advertise` includes MCP tools with no MCP-specific changes, and the Phase 4b
      multi-hop loop chains an MCP tool call like a native one
- [ ] `pa.ui.selector` is unmodified, or changed only in ways the Phase 7 `/` selector tests
      still fully cover
- [ ] `:mcp/prompt-invoke` is registered in the event registry/spec and validates like every
      other event

### Manual verification (gates merge)

- [ ] Flip `:playwright` to `:enabled? true`, start the system, and confirm the
      `:mcp.playwright/*` tools appear in `registered-tools`
- [ ] Run "open example.com and tell me the page title" and confirm the turn completes
      end-to-end through `:tool/invoke` → `:tool/result` with a correct answer
- [ ] Stop the system and confirm the playwright subprocess is gone (no orphan in `ps`)
- [ ] Record the tool list and the completed turn in the PR description

## Merge criteria

All of the following must be true:

1. Every checklist item above is checked, including the manual `playwright-mcp` run — the
   automated suite runs entirely against fakes, so the manual session is the only evidence
   that the wire format matches a real server.
2. The full test suite passes with no regressions, particularly in the tool registry, the
   Phase 7 command selector, and system start/stop smoke tests.
3. A default install (no `:mcp` key, or the shipped commented template) spawns no
   subprocess, performs no network access, and adds no measurable startup latency.
4. No MCP-specific execution path exists outside `:tool/invoke` → `:tool/result` — MCP tool
   calls are as observable and replayable as native ones.
5. Failure modes are covered and non-fatal: bad command, handshake timeout, malformed config
   entry, and mid-session server death each degrade to "no tools from that server" with a
   warning.
6. Shutdown leaves no orphaned subprocesses.
7. The deferred limitations — stdio-only transport, and prompts with 2+ named arguments —
   are recorded in the roadmap and `ideas-backlog.md`, not left as silent gaps.
8. Phase 9 items in `spec/roadmap.md` are ticked off to match what actually landed.
