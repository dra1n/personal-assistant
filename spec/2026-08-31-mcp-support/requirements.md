# Requirements: Phase 9 — MCP Support

## Goal

Let the assistant connect to external MCP (Model Context Protocol) servers over local stdio
and expose their **tools**, **resources**, and **prompts** through machinery that already
exists — the runtime-mutable tool registry, the `:tool/invoke` → `:tool/result` effect path,
the Phase 7 command registry, and the Phase 7 selector overlay — rather than building a
parallel execution path. Adding a server becomes a `config.edn` entry, not a code change.
`playwright-mcp` is the first server wired end-to-end, shipped disabled by default so no
subprocess spawns and no package downloads until the user opts in.

## Scope

### In scope

- **Configuration** — a new `:mcp {:servers {...}}` key in `config.edn`, read through the
  existing `#setting [path]` aero plumbing in `resources/system.edn`; a commented
  `:playwright` example block in `resources/templates/config.edn` alongside the existing
  `:llm` / `:portal` / `:settings` blocks.
- **Policy** — `pa.tools.mcp.policy`, an Integrant component (`:tool.mcp/policy`) that
  normalizes and validates the configured `:servers` map into
  `{:servers {name -> {:transport :command :args :env :enabled?}}}`.
- **Transport** — `pa.tools.mcp.client`: `ProcessBuilder` subprocess, newline-delimited
  JSON-RPC 2.0 over stdin/stdout, a reader thread demuxing responses to in-flight requests
  by numeric id via promises, stderr piped to logs, per-server connect timeout, clean
  shutdown.
- **Protocol surface** — the `initialize` handshake + `notifications/initialized`, and thin
  wrappers for `tools/list`, `tools/call`, `resources/list`, `resources/read`,
  `prompts/list`, `prompts/get`.
- **Component** — `:mcp/registry`, connecting all enabled servers concurrently at
  `ig/init-key`, registering tools and prompts per connection, disconnecting all at
  `ig/halt-key!`, and wired into `pa.runtime/dispatcher`'s ctx map beside `:tool.fs/policy`.
- **Tools** — `pa.tools.mcp` translates each `tools/list` entry into a `reg-tool` under
  `:mcp.<server>/<tool-name>`, proxying to `tools/call`; MCP errors surface as
  `:tool/status :error`.
- **Resources** — `list-resources` / `read-resource` wrappers, plus an `@`-mention
  affordance in the terminal input reusing the Phase 7 selector state machine unmodified;
  selecting a resource inserts its content into the outgoing message as attached context.
- **Prompts** — `list-prompts` / `get-prompt` wrappers; each connected server's prompts
  registered as dynamic slash commands named `<server>.<prompt-name>`; a new
  `:mcp/prompt-invoke` event whose handler appends the returned messages to the
  conversation and continues the turn via `:llm/invoke`.
- **playwright-mcp** — the template entry, plus a manual end-to-end verification run.
- **Tests** — fixture- and fake-client-based coverage of policy, framing, handshake, tool
  registration, tool proxying (ok + error), resources, the `@` selector, prompt
  registration/dispatch, and startup resilience.

### Out of scope

- **Remote transports (SSE / streamable HTTP).** Local stdio only — it covers
  playwright-mcp and most local servers. Deferred to `ideas-backlog.md`.
- **Prompts with 2+ named arguments.** A documented, deferred limitation: zero-argument
  prompts map to `:none`, single-argument prompts to `:free-text`. playwright-mcp ships no
  prompts, so nothing blocks on it.
- **Background reconnection / supervision.** No daemon and no reconnect loop; a server that
  dies stays dead until the next system restart, matching the Phase 6 session-lifecycle
  model.
- **Schema translation.** MCP `inputSchema` is JSON Schema, which `validate-args` already
  speaks; only JSON→EDN keywordization is performed.
- **A generalized transport abstraction.** No protocol layer designed to accommodate a
  future HTTP transport — add it when a second transport actually exists.
- **Enabling any server by default.** Every shipped config entry is commented out and
  `:enabled? false`.

## Design decisions

1. **MCP calls flow through `:tool/invoke` → `:tool/result` like every native tool.** No
   hidden execution path — the same observability, dry-run, structured logging, and replay
   guarantees the fs / network / YouTube tools already have. This is the mission's
   "Observable" and "Replayable" values applied directly: an MCP tool call must be visible
   in the event log and replayable from it.
2. **`pa.tools.mcp.policy` is a sibling of `pa.tools.fs.policy`, not a generalization of
   it.** An allowlist of trusted *servers*, config-shaped rather than path-shaped, sourced
   from `config.edn` rather than a dedicated `tools.md`. Resisting premature abstraction
   over two policy families keeps both readable.
3. **Session-lifecycle component, matching the Phase 6 model.** Connect (spawn + handshake
   + register) on `ig/init-key`; disconnect (close stdin, wait, `.destroyForcibly`) on
   `ig/halt-key!`.
4. **Namespaced by server.** Tools are `:mcp.<server>/<tool-name>`, prompts are
   `<server>.<prompt-name>`, resources are labelled `server:uri`. Two servers cannot
   collide, and provenance is visible in logs, `/help`, and the tool advertisement sent to
   the LLM.
5. **Degrade, don't crash.** A missing binary, a handshake timeout, or a malformed config
   entry contributes no tools/resources/prompts and logs a warning. It never blocks app
   startup, and never takes down another server. Enabled servers connect concurrently.
6. **A missing `:mcp` key yields zero servers.** Same default-deny spirit as the filesystem
   policy — absent configuration means no capability, not a permissive default.
7. **No new config-loading machinery.** `:mcp` rides the `#setting [path]` aero plumbing
   `resources/system.edn` already uses for `:llm/provider` and `:pa.observability/portal`.
8. **No new tool-calling wiring.** `registry/advertise` already enumerates the whole
   registry and the Phase 4b multi-hop tool-call loop is tool-source-agnostic, so MCP tools
   chain exactly like native ones. If either assumption proves false, fix the general
   mechanism rather than special-casing MCP.
9. **The `@`-mention reuses `pa.ui.selector` unmodified.** `@` triggers the overlay the same
   way `/` does in `pa.ui.app`, sharing filter/highlight/Esc mechanics. Resource insertion
   is *attached context*, not a tool call — the model receives content, not an invocation.
10. **`inputSchema` is used verbatim as `:schema`.** No translation layer to drift out of
    sync with the spec.
11. **The transport's async hop mirrors `pa.runtime.executor`.** Reader thread + promises
    keyed by numeric request id — the same shape already used for `:llm/invoke` and
    `:extraction/classify`, so there is one concurrency idiom in the codebase, not two.
12. **Tests never spawn a real subprocess.** `PipedInputStream` / `PipedOutputStream` stand
    in for a server; real-server confidence comes from the one manual playwright run.

## Context

- **Phase 4 foreshadowed this.** `design-notes.md`'s "MCP / no-code tools" section observed
  that the tool registry is runtime-mutable, so wiring an MCP server should be "add a config
  entry," not "write code." This phase cashes that in.
- **Phase 7 left two extension points unbuilt**, both realized here: the overlay list
  component that "could later back an `@`-style resource mention," and the `:select`
  argument kind, documented in `pa.commands.registry` but with no concrete user. MCP
  prompts-as-commands is that concrete user.
- **Existing shape to match.** `resources/system.edn` already wires `:tool.fs/policy` into
  `:pa.runtime/dispatcher`'s ctx map; `:tool.mcp/policy` and `:mcp/registry` follow the same
  pattern. The commented template blocks in `resources/templates/config.edn` establish the
  documentation style for the new `:mcp` block.
- **Local-first, per `mission.md`.** MCP servers are local subprocesses, not remote
  services — consistent with the local-first value that excluded external databases and
  vector stores. The one network concession (`npx -y` downloading a package on first run) is
  precisely why servers ship disabled.
- **Success-picture criterion 4** — "adding a new capability requires touching one layer
  only" — is what the config-entry-not-code goal is testing here.
