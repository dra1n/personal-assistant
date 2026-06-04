# Validation: Phase 4b — Tool System (Network)

## Definition of done

Phase 4b is complete when: schema validation is enforced for all registered tools
and surfaces failures visibly in the chat turn; `web-search` and `fetch-page` are
registered, tested with mocked HTTP, and protected by the SSRF guard; and the
running app supports a live end-to-end LLM turn where the model calls both network
tools in sequence to answer a question. The full test suite passes with no Phase 4
regressions.

## Checklist

### Tests

- [ ] Property-based tests (test.check) for schema validation pass — random valid
      args accepted, invalid args rejected with `:type :tool/invalid-args`
- [ ] `web-search` unit tests pass with mocked HTTP (fixture DuckDuckGo response →
      correct result shape)
- [ ] `fetch-page` unit tests pass with mocked HTTP (fixture HTML → clean extracted
      text; `:format :raw` → original HTML returned)
- [ ] SSRF guard unit tests pass: 192.168.x.x, 10.x.x.x, 172.16.x.x, 127.0.0.1,
      169.254.x.x, ::1 all rejected; a public IP passes; a public hostname that
      resolves to a private IP is rejected
- [ ] Full test suite (`clojure -M:test` or equivalent) green — no Phase 4
      regressions in filesystem tools, dry-run mode, or single-hop LLM flow

### Behaviors

- [ ] Submitting a tool call with wrong `:tool/args` (e.g. missing `:query` for
      `web-search`) produces a `:tool/result` error visible in the chat response —
      the LLM acknowledges the error, not a crash or silent drop
- [ ] `web-search` returns results in a reasonable REPL call (live network, not mocked)
- [ ] `fetch-page` retrieves and strips a real public URL to readable text in a
      REPL call (live network, not mocked)
- [ ] `fetch-page` with a private-IP URL returns a `:tool/result` SSRF rejection
      error (REPL-verified)
- [ ] Dry-run mode logs the effect descriptor for both new tools without making
      any HTTP calls
- [ ] `fetch-page` with `:format :raw` returns the raw HTML of a test URL

### Integration

- [ ] Live app turn: LLM calls `web-search` with a query, receives results, then
      calls `fetch-page` on one of the returned URLs, then answers in text — the
      full turn completes without error and is visible in the terminal UI
- [ ] The above turn is logged correctly in the event log
      (`assistant-data/events/events.edn`) with both tool calls and results recorded
- [ ] Existing Phase 4 filesystem tools (`read-file`, `write-file`, `list-dir`, etc.)
      still work in a live turn after Phase 4b is merged — no registry or executor
      regressions

## Merge criteria

All of the following must be true before this branch is merged to main:

1. All tests pass (unit, integration, property-based)
2. No Phase 4 regressions — filesystem tools, dry-run, single-hop LLM tool use
3. Schema validation failures surface in the chat turn (verified manually)
4. SSRF guard rejects private IPs (verified in REPL or tests)
5. End-to-end live LLM turn with both network tools completes successfully in
   the running terminal app
6. `fetch-page` uses a JVM HTML parsing library — no regex-based HTML parsing
