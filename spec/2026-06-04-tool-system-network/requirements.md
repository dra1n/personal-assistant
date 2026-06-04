# Requirements: Phase 4b — Tool System (Network)

## Goal

Extend the Phase 4 tool machinery with network-backed tools and harden the shared
tool infrastructure with argument-schema validation. The web search and webpage
retrieval tools reuse the existing registry, effect type, dry-run mode, structured
logging, and event-bus wiring — no new infrastructure is introduced. The validation
layer applies to all tools (filesystem and network) and closes the gap left
deliberately open in Phase 4.

## Scope

### In scope

- Tool argument-schema validation: enforce `:tool/args` against the registered
  `:schema` before executing; emit `:tool/result` error (`:type :tool/invalid-args`)
  on failure, do not run the tool
- Property-based tests (test.check) for schema validation across all registered tools
- Web search tool using DuckDuckGo (no API key required)
- Webpage retrieval tool: fetch + parse to readable text/markdown; strip
  scripts/styles/markup using a library, not regex; raw HTML available behind a
  `:format` flag
- SSRF guard on webpage retrieval: resolve the requested hostname, reject any
  resolved address in private/link-local/loopback ranges (RFC 1918, RFC 4193,
  169.254.0.0/16, 127.0.0.0/8, ::1, etc.) — strict enforcement, not best-effort
- Schema validation failures surface as `:tool/result` errors visible in the chat
  turn (not silently logged); LLM sees the error and can respond
- Tests for both network tools with mocked HTTP
- End-to-end validation: a live turn where the LLM calls both tools in the running app

### Out of scope

- Readability-style full main-content extraction (nice-to-have, deferred)
- Domain/IP allowlist configuration (the SSRF guard is the safety layer; no
  per-user allowlist in this phase)
- YouTube transcript tool (Phase 4c)
- Recursive multi-step tool loop (Phase 7)
- Streaming tool results
- Tool result caching

## Design decisions

1. **DuckDuckGo as web search provider** — no API key required; use the DuckDuckGo
   Instant Answer API or HTML search endpoint. If results quality is insufficient
   for a realistic LLM turn, the implementation may switch to a structured endpoint
   but must remain keyless.

2. **Schema validation placement** — validation runs inside the `:tool/invoke` effect
   executor, before dispatching to the tool's `:fn`. This means it applies uniformly
   to all registered tools without requiring per-tool changes. Error format mirrors
   existing `:tool/result` shape so the LLM response path doesn't branch.

3. **Strict SSRF enforcement** — resolve the hostname (DNS lookup) before any HTTP
   connection is made; reject if any resolved address falls in a private/link-local
   range. Failure emits a `:tool/result` error, not a crash. This must run even if
   the provided URL appears to be a public domain (DNS rebinding defense).

4. **Text extraction via library** — use a JVM HTML parsing library (e.g. jsoup) to
   strip scripts, styles, and markup and return clean text. Regex-based HTML parsing
   is explicitly prohibited. The `:format :raw` flag returns the original HTML for
   debugging.

5. **Validation failures in chat** — `:tool/invalid-args` errors travel through the
   same `:tool/result → LLM follow-up` path established in Phase 4, so the LLM
   receives the error message and can explain or retry. No silent suppression.

6. **Namespace placement** — new tools live in `pa.tools.network.*`; the shared
   validation logic lives in `pa.tools.registry` (or wherever the executor currently
   lives), consistent with the one-way dependency direction: `runtime → tools`,
   `tools` do not depend on `runtime`.

## Context

Phase 4 deliberately deferred schema validation to Phase 4b because LLM-supplied
arguments to network tools are where argument errors matter most. The registry
already stores `:schema` per tool and advertises it to the LLM; this phase enforces
it. The SSRF guard design is specified in `spec/design-notes.md` and should be
followed. The single-hop LLM tool-use flow (Phase 4) is the conversation path that
network tools will exercise; no changes to that flow are expected.
