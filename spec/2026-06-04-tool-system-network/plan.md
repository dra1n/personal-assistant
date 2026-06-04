# Plan: Phase 4b — Tool System (Network)

## Task groups

### Group 1 — Shared machinery: schema validation

- [x] Add schema validation to the `:tool/invoke` effect executor: validate
      `:tool/args` against the registered `:schema` before calling the tool's `:fn`
- [x] On validation failure, emit a `:tool/result` map with `:type :tool/invalid-args`
      and a descriptive error message; do not execute the tool
- [ ] Confirm the validation error travels through the existing
      `:tool/result → LLM follow-up` path and is visible in the chat turn
- [x] Write property-based (test.check) tests for schema validation: generate random
      args against each registered tool schema, assert valid args pass and invalid
      args are rejected with the correct error shape

### Group 2 — Web search tool

- [x] Implement `web-search` tool: accepts a `:query` string, returns a list of
      result maps (`{:title :url :snippet}`) via DuckDuckGo (no API key)
- [x] Register the tool in the tool registry with a schema and description
- [x] Write unit tests with mocked HTTP: fixture response → assert result shape
- [x] Confirm schema validation rejects a missing or wrong-type `:query` argument
      (covered by Group 1 property tests, but worth an explicit case)

### Group 3 — Webpage retrieval tool + SSRF guard

- [x] Implement the SSRF guard: resolve the hostname before any HTTP connection;
      reject if any resolved address falls in RFC-1918, link-local, loopback, or
      IPv6 private ranges; emit `:tool/result` error on rejection
- [x] Implement `fetch-page` tool: accepts `:url` (required) and optional
      `:format` (`:text` default, `:raw` for original HTML); fetches the page,
      strips scripts/styles/markup via jsoup (or equivalent JVM HTML parser), returns
      clean text
- [x] Register the tool in the tool registry with a schema and description
- [x] Write unit tests with mocked HTTP: fixture HTML → assert extracted text,
      assert `:format :raw` returns original HTML
- [x] Write SSRF guard unit tests: private-IP URLs rejected, public URLs accepted,
      DNS-rebinding scenario (public hostname resolving to private IP) rejected

### Group 4 — End-to-end validation

- [x] Run the app and conduct a live turn where the LLM calls `web-search` and
      `fetch-page` in sequence (search → pick a URL → retrieve the page → answer)
- [x] Confirm dry-run mode still works for both new tools (no HTTP calls)
- [x] Confirm existing Phase 4 filesystem tools and single-hop tool use are unaffected
      (run the full test suite; no regressions)

## Notes

Group 1 must land before Groups 2 and 3 — the network tools should be registered
only after validation is enforced, so they are never reachable without argument
checking. Groups 2 and 3 can proceed in parallel once Group 1 is done. Group 4 is
an integration/manual step that requires Groups 1–3 to be complete.

The SSRF guard (Group 3) should be implemented as a standalone function testable
in isolation before it is wired into the `fetch-page` tool, so the unit tests can
cover it without a full HTTP mock setup.
