# Design notes — tool system

Captured during Phase 4 so the reasoning behind the structure isn't re-derived
later. These are forward-looking; nothing here is built in Phase 4 beyond the
filesystem tools.

## Machinery is generic; policy is per-family

- `pa.tools.registry` + the `:tool/invoke` effect + dry-run + structured logging
  + `:tool/result` are **policy-agnostic**. A new tool is just another `reg-tool`
  entry; the effect calls `(tool-fn args ctx)` and the tool enforces its own
  policy from `ctx`. This layer does **not** change for network tools.
- Access policy does **not** generalize and must not be forced to. `pa.tools.fs.policy`
  is filesystem-specific (paths, `read`/`write`/`deny`, canonicalization,
  longest-prefix). URLs and paths share no structure — a single "tool policy"
  abstraction would be the wrong abstraction.

## Code layout — sibling tool families

Each tool family is discoverable from the folder structure; each owns its policy.
There is no privileged "the policy".

```
src/pa/tools/
  registry.clj        pa.tools.registry      generic machinery (shared)
  fs.clj              pa.tools.fs            read-file, write-file, list-dir
  fs/policy.clj       pa.tools.fs.policy     fs allowlist policy
  web.clj             pa.tools.web           (future) search, fetch
  web/policy.clj      pa.tools.web.policy    (future) domains + SSRF
  youtube.clj         pa.tools.youtube       (future)
```

`registry/registered-tools` is the runtime answer to "how many tools?" — it
enumerates every registered tool, including dynamically-registered ones.

## The transferable pattern: canonicalize-then-check

`fs.policy/check` (canonicalize → allow/deny → return the safe resolved value, or
throw `:tool/access-denied`) is a **pattern** to copy, not code to extend:

- **fs:** canonicalize the path (kill `..`/symlinks) → check against roots.
  Defeats path-traversal escape.
- **web (future):** resolve hostname → IP → check against a domain *and* IP-range
  policy, rejecting private/link-local ranges (`localhost`, `10/8`, `169.254/16`,
  `file://`). This is **SSRF — the network analog of path traversal** — and is the
  single most important guard for the page-fetch tool.

## `tools.md` will need multiple named blocks

Today `pa.tools.fs.policy/parse-allowlist` reads exactly one fenced ```allowlist
block. The file-as-inspectable-config concept generalizes; the parser does not.
When web tools land, `tools.md` should hold several named blocks
(```allowlist, ```web-domains, ```rate-limits, …), each parsed by its own
family's policy loader. One inspectable file, sectioned per family.

## MCP / no-code tools — deferred, registry is ready

The registry is a runtime-mutable atom (handler-registry pattern), so tools can be
registered at startup, not just at compile time. An MCP server's tool definitions
(name, JSON schema, description) map one-to-one onto `{:fn :schema :description}`;
the `:fn` proxies the call to the server. So a future `pa.tools.mcp` family could
enumerate configured servers and register proxies — "adding a tool" becomes
"adding a server to config," no code.

Constraints to keep it true to the mission when we build it:
- MCP calls must still flow through `:tool/invoke` → `:tool/result` so they stay
  observable and replayable (no hidden execution).
- Gate it with an MCP-servers allowlist (trusted servers only) — its own policy,
  a sibling family policy like any other.
- Native tools (fs) still require code; MCP gives no-code tools only for MCP
  servers, not for everything.
