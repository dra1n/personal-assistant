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
- [x] `:mcp/registry` — `init-key` takes `{:policy #ig/ref :tool.mcp/policy}`, connects to every enabled server concurrently, registers each connection's tools and prompts, and caches its resource/prompt listings
- [x] `halt-key!` disconnects every connected client
- [x] Wire `:mcp/registry` into `pa.runtime/dispatcher`'s ctx map alongside `:tool.fs/policy`, so tool, resource, and prompt fns can reach live clients
- [x] Tests: startup resilience — one configured server with a bad command does not prevent other enabled servers from connecting or the system from starting
- [x] Tests: `halt-key!` disconnects every client, including after a partially failed startup

### Group 5 — Tools
- [x] `pa.tools.mcp` — for each connected server, translate every `tools/list` entry (`name`, `description`, `inputSchema`) into a `reg-tool` under `:mcp.<server>/<tool-name>`
- [x] Use `inputSchema` directly as the tool's `:schema` — `pa.tools.registry/validate-args` already speaks JSON-Schema-shaped EDN, so only keywordization is needed
- [x] The registered `:fn` proxies to `tools/call` on that server's client; an MCP error response is thrown as `ex-info` with `{:type :mcp/tool-error}` so it surfaces as a normal `:tool/status :error`
- [x] Confirm no further LLM tool-calling wiring is needed — `registry/advertise` enumerates the whole registry and the Phase 4b multi-hop loop is tool-source-agnostic (if either assumption fails, fix the general mechanism, don't special-case MCP)
- [x] Tests: fixture `tools/list` → each tool `reg-tool`'d under its namespaced key with the JSON Schema carried through as `:schema`
- [x] Tests: `:tool/invoke` on an `:mcp.*` tool → fake client returns a `tools/call` result → `:tool/status :ok`; an MCP error response → `:tool/status :error`

### Group 6 — Resources & the `@`-mention
- [x] `pa.tools.mcp/list-resources` and `read-resource` — shape a resource into `{:server :uri :name :mime-type :content}`. Note `:name` and `:description` come from the *listing*; `resources/read` returns only `{:uri :mimeType :text}` per content entry, so the display name must be carried over from the cached listing rather than read back
- [x] A single resource may return several `:contents` entries — join their text, and skip binary (`:blob`) entries rather than inlining them into a message
- [x] `@`-mention affordance in the terminal input: typing `@` opens the same overlay used by the command selector, populated from every connected server's cached `resources/list`, rows labelled `server:uri`
- [x] ~~Reuse the Phase 7 selector state machine unmodified~~ — **generalized instead**, not forked. `pa.ui.selector.state` was hardwired to slash commands in two places (`name-phase?` tested for `/`; `matches` read the command registry), so "unmodified" was not reachable. It now takes a *source* — `{:trigger :anchored? :match :rows}` — and `pa.ui.selector.sources` builds one for commands and one for resources. Both overlays are the same machine, and the Phase 7 tests carry every original assertion through the command source
- [x] ~~Selecting a resource reads it and inserts its content into the input buffer~~ — **superseded by Group 6b**: the content is attached when the message is sent, not spliced into the line being typed
- [x] Tests: resource listing and read against a fake client
- [x] Tests: `@` selector, mirroring the Phase 7 `/` selector tests — typing `@` opens the overlay populated from fixture resources; selecting one inserts its content into the input buffer

### Group 6b — Mentions resolve at send time, not in the input line

Revises Group 6, which spliced a resource's text straight into the input buffer.
That put a whole document on the line a person is still writing and mixed their
words with the content in one undifferentiated blob. Instead the mention stays a
short reference while typing, and the content is attached to the message on its
way to the model.

**How the attachment is framed** — the choices below are the established
practice for putting retrieved documents in front of a model, and each one is
load-bearing:

- **Delimit with tags carrying provenance.** Each resource is wrapped in
  `<resource uri="…" name="…" mime-type="…">`, inside one `<attached-resources>`
  block. Tagged delimiters are what long-context guidance recommends over bare
  concatenation, and the metadata lets the model say *which* document it is
  answering from.
- **Documents first, question last.** The block precedes the person's own text
  in the same user message. Models attend most reliably to the end of a long
  message, and the question is what should sit there.
- **Say it is data, not instruction.** A resource is third-party text that can
  contain "ignore your previous instructions". One system-message line, added
  only when a turn carries attachments, states that attached resources are
  material the user supplied for reference and are never instructions.
- **Leave the person's words alone.** The typed text — mentions and all — is
  passed through verbatim, so the transcript reads as written and the model can
  see which reference goes with which document.
- **Cap each resource.** One oversized resource otherwise blows the context
  window, which fails the whole request rather than that one attachment.

- [x] UI: selecting a mention inserts `@server:uri ` into the buffer and nothing
      else. This removes the resource read from the update loop entirely —
      `read-resource-cmd`, the `::resource-read` message, and `:mcp-clients` in
      the model all go away, and the UI stops doing I/O
- [x] `pa.tools.mcp/parse-mentions` — pure: given text and the connected
      servers' cached resource listings, return the resources mentioned. Resolve
      against *known* `server:uri` labels rather than guessing where a URI ends,
      so trailing punctuation ("…@everything:demo://notes.") can't corrupt it. An
      unmatched mention stays literal text
- [x] `:user/message` handler: with no mentions, today's path is untouched
      (straight to `:llm/invoke`). With mentions, emit a new
      `:mcp/resolve-mentions` effect instead, carrying the resolved references
- [x] `:mcp/resolve-mentions` effect (`pa.runtime.executor`): read each resource
      off-thread through the registry's live clients — the same `future` +
      `dispatch!` hop `:llm/invoke` already uses — then dispatch
      `:mcp/mentions-resolved` with the contents
- [x] `:mcp/mentions-resolved` handler: attach to the pending user turn via a
      `tr/` transition, store the event, and emit the `:llm/invoke` that
      `:user/message` would have. Because the resolved text lives in this
      persisted event, replay reconstructs the attachments without touching MCP —
      the same reason replay never calls the LLM
- [x] Conversation turns gain `:attachments [{:server :uri :name :mime-type
      :content}]`. The turn's `:content` stays exactly what was typed, so the
      transcript and history are unchanged
- [x] `pa.llm.prompt/conversation->message` renders attachments into the outgoing
      user message per the framing above; `assemble` adds the one-line system note
      when any turn in the conversation carries attachments
- [x] Per-resource size cap with an explicit truncation marker, so a large
      document degrades to a prefix instead of failing the request
- [x] A resource that cannot be read attaches an error note rather than silently
      vanishing — the model must not believe it read something it did not — and
      logs why. The turn still goes through
- [x] Show attachments in the transcript under the user turn (name + mime type),
      so what was sent is visible rather than implied
- [x] Tests: `parse-mentions` over real label shapes — several mentions, trailing
      punctuation, an unknown server, a mention inside a word, no mentions at all
- [x] Tests: `:user/message` with no mentions still emits `:llm/invoke` directly
      (the no-MCP path must not change), and with mentions emits
      `:mcp/resolve-mentions` instead
- [x] Tests: the effect reads through a fake client and dispatches
      `:mcp/mentions-resolved`; a failing read still dispatches, carrying the error
- [x] Tests: `:mcp/mentions-resolved` attaches to the turn and emits `:llm/invoke`
- [x] Tests: prompt assembly — attachments render before the typed text, inside
      tagged delimiters with their metadata; the system note appears only when
      attachments exist; oversized content is truncated with its marker
- [x] Tests: replaying `:user/message` + `:mcp/mentions-resolved` from the event
      log rebuilds the attachments with no MCP client present
- [x] Tests: UI — selecting a mention inserts the reference and issues no command

### Group 7 — Prompts as slash commands
- [x] `pa.tools.mcp/list-prompts` and `get-prompt` — thin wrappers; `get-prompt` returns the server-rendered message list for the given arguments
- [x] Register each connected server's prompts as dynamic slash commands via `reg-command`, named `<server>.<prompt-name>` — the first concrete user of the `:select`-picker extension point Phase 7 documented but left unbuilt
- [x] Argument mapping keyed on **required** arguments, not declared ones: zero required → `:none`; exactly one required → `:free-text` (that value passed through, optional arguments omitted); 2+ required are skipped with a warning and documented as a deferred limitation. Declared-count would skip a prompt usable with a single value — see the observed shapes below
- [x] `->event` for an MCP-prompt command dispatches `:mcp/prompt-invoke`; its handler calls `get-prompt`, appends the returned messages to the conversation, and continues the turn via `:llm/invoke` — the same path a `:user/message` turn takes
- [x] Flatten each returned message's `:content` map (`{:type "text" :text "…"}`) into the plain string our conversation messages carry; a non-text content part is dropped with a warning rather than passed through as a map
- [x] Register `:mcp/prompt-invoke` in the event registry/spec alongside existing events
- [x] Tests: fixture `prompts/list` → commands registered under the right names with the right argument kinds; a 2+-argument prompt is skipped, not crashed on
- [x] Tests: invoking a prompt command dispatches `:mcp/prompt-invoke` → handler calls `get-prompt` and feeds the returned messages into `:llm/invoke`

### Group 8 — playwright-mcp end-to-end
- [ ] Confirm the shipped `:playwright` template entry is present, commented out, and `:enabled? false`, so no subprocess spawns and no package downloads until a user opts in
- [ ] Manual verification: flip `:enabled? true`, start the system, confirm `registered-tools` includes the `:mcp-playwright/*` tools (`browser_navigate`, `browser_click`, `browser_snapshot`, …)
- [ ] Manual verification: a tool-calling turn ("open example.com and tell me the page title") completes end-to-end through `:tool/invoke` → `:tool/result`
- [ ] Record the manual session outcome (tool list + the completed turn) in the PR description

### Group 9 — Roadmap & docs
- [ ] Tick off the Phase 9 items in `spec/roadmap.md` as each group lands
- [ ] Note the 2+-argument-prompt limitation and the stdio-only transport decision where a future reader will find them (roadmap Phase 9 section + `ideas-backlog.md` entry for remote transports)

## Reference shapes (observed, not assumed)

Taken from a live `@modelcontextprotocol/server-everything` 2.0.0 session on
2026-09-01, so Group 6 and 7 fixtures match what a server really sends. The last
two bugs in this phase both came from fixtures that read nicely in EDN but were
not what the transport produces.

- **capabilities**: `{:tools {:listChanged true} :prompts {:listChanged true}
  :resources {:subscribe true :listChanged true} :logging {} :completions {} :tasks {…}}`
- **`resources/list` entry**: `{:uri "demo://resource/static/document/architecture.md"
  :name "architecture.md" :mimeType "text/markdown" :description "…"}`
- **`resources/read` result**: `{:contents [{:uri "…" :mimeType "text/markdown" :text "…"}]}`
  — no `:name`, and `:contents` is a vector.
- **`prompts/list` entry**: `{:name "args-prompt" :title "Arguments Prompt"
  :description "…" :arguments [{:name "city" :description "…" :required true}
  {:name "state" :required false}]}` — note `:required` is absent on some
  arguments rather than `false`, and a prompt carries a `:title`.
- **`prompts/get` result**: `{:messages [{:role "user" :content {:type "text"
  :text "…"}}]}` — `:content` is a map, not a string, and `:description` may be absent.

The server's four prompts are `simple-prompt` (0 arguments), `args-prompt`
(1 required + 1 optional), `completable-prompt` and `resource-prompt` (2 required
each). Keying the argument mapping on *required* count makes two of the four
usable; keying it on declared count would make only one.

## Notes

- **Sequencing.** Groups 1 → 2 → 3 → 4 are a hard chain: policy feeds the client, the client
  feeds the wrappers, the wrappers feed the registry. Groups 5, 6, and 7 all depend on
  Group 4 but are independent of each other and can land in any order. Group 8 needs
  5 at minimum (tools are what playwright exercises); Group 9 trails everything.
- **Earliest useful milestone.** Groups 1–5 make playwright-mcp genuinely usable. If the
  branch grows uncomfortable, that's the natural split point — resources and prompts are
  additive surfaces that can merge separately without leaving anything half-wired.
- **Group 6 is the only UI-layer work.** It touches `pa.ui.app` and `pa.ui.selector`;
  everything else lives under `pa/tools/mcp`. The constraint that mattered turned out to be
  *don't fork the state machine*, not *don't change it* — see the struck-through task above.
  Two differences from the `/` overlay were forced by the domain and are worth keeping in
  mind: a mention is **unanchored** (it sits inside a sentence, so it opens only after
  whitespace — otherwise every email address would trigger it) and **substring-matched**
  (labels begin with a server name nobody types first). Reading a mentioned resource happens
  off the update loop via a charm command: a local server answers in milliseconds, but
  freezing the terminal on a subprocess round-trip would be worse than a moment's delay.
- **Group 5's last item is a verification, not an implementation.** If `advertise` or the
  multi-hop loop turns out to need changes, that's a general-mechanism bug surfaced by MCP,
  and it should be fixed generally.
- **Concurrency lives in two places only** — the client's reader thread (Group 2) and the
  registry's concurrent connect (Group 4). Both mirror `pa.runtime.executor`'s existing
  promise-based hop; resist introducing a third idiom.
- **No test spawns a real subprocess.** Real-server confidence comes exclusively from
  Group 8's manual run.
