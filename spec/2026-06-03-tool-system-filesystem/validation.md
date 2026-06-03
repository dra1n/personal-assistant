# Validation: Phase 4 — Tool System (Filesystem)

## Definition of done

The runtime has a working tool layer: a registry, a declarative `:tool/invoke`
effect, dry-run mode, structured logging, and `:tool/result` events on the bus.
Three filesystem tools (`read-file`, `list-dir`, `write-file`) operate through a
capability-flagged allowlist parsed from `system/tools.md`, which bootstrap now
generates with a safe `read write`-on-data-root baseline. Path traversal and
symlink escape are provably rejected; `deny` and default-deny hold; reads and
writes honor distinct capabilities. The LLM can make a single explicit tool
round-trip and see the result, with the no-tool streaming path unchanged. Tools
never execute on replay. All of this is covered by adversarial and observability
tests in CI, with no live-LLM dependency.

## Checklist

### Tests
- [ ] `read-file` / `list-dir` / `write-file` unit tests against a temp filesystem (happy path + schema-invalid args rejected)
- [ ] Resolver adversarial tests: `..` traversal, symlink escape, out-of-root path, unmatched path (default-deny), longest-prefix precedence, `deny` overrides a broader allow
- [ ] Capability-matrix tests: `read`-only root rejects `write-file`; `write`-only root rejects reads; root with both allows both
- [ ] Dry-run tests: zero filesystem side effects occur and the correct effect descriptor is logged / `:tool/result` carries `:dry-run true`
- [ ] `test.check` property tests for tool argument-schema validation
- [ ] Replay test: a log containing a `:tool/result` reconstructs state without re-invoking the tool fn (only `:db` applied)
- [ ] Single-hop round-trip test with a fixture/stub provider: `:llm/invoke` → tool-call request → `:tool/invoke` → `:tool/result` → one follow-up `:llm/invoke`
- [ ] Bootstrap test: a fresh data root gets a `system/tools.md`; an existing one is not overwritten (idempotent)

### Behaviors
- [ ] Every tool invocation emits one structured Timbre log line (tool, args, result summary, duration) and one `:trace` entry
- [ ] Every tool invocation dispatches exactly one `:tool/result` event, persisted via `:event/store`
- [ ] A read of an allowed path returns contents; a read of a `deny`/out-of-root path is rejected with an explicit error, not a silent empty result
- [ ] A write to a `read`-only root is refused and performs no filesystem mutation
- [ ] With no tool call in the response, the Phase 3 streaming-text turn behaves exactly as before (no regression)

### Integration
- [ ] Registry + resolved policy are reachable by handlers/effects through the dispatcher `:runtime` context (not fetched directly by handlers)
- [ ] The `:tool/policy` and registry components are wired into the Integrant config graph and start/stop cleanly with the system
- [ ] `system/tools.md` parses at startup into the in-memory policy used by the resolver; the data root resolves via `pa.storage.fs/pa-home`
- [ ] The full system boots with the tool layer present and `pa.system/start!` → `stop!` round-trips without error

## Merge criteria

All of the following must hold before merging `2026-06-03-tool-system-filesystem`:

1. The full test suite passes in CI, including the adversarial resolver tests, the capability matrix, dry-run no-side-effect proof, the replay (no re-execution) test, and the `test.check` schema properties.
2. No tool can read, write, or list a path outside the allowlist; `..` traversal and symlink escape are demonstrably rejected by tests; `deny` and default-deny are enforced.
3. `:tool/invoke` performs no side effect on replay, and `:tool/result` is reconstructed purely from the event log.
4. Bootstrap generates a safe default `system/tools.md` on a fresh data root and never overwrites an existing one.
5. The single-hop LLM tool-call path works against a fixture provider; a documented manual REPL smoke check exercises each tool through a real allowlist (no live-LLM test in CI).
6. The no-tool streaming-text path from Phase 3 is unchanged (regression check green).
7. The roadmap's Phase 4 checkboxes are ticked, and Phase 4b (network tools) remains untouched.
