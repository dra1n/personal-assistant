# Phase 0 — Foundation & Boilerplate: Validation

Phase 0 is mergeable when all automated checks pass **and** the manual REPL demo below runs cleanly.

---

## Automated Checks

Run from project root:

```sh
clojure -T:test
```

All of the following must pass:

| Test | What it asserts |
|---|---|
| System start | All Integrant components initialize without throwing |
| System halt | All components halt cleanly; no hanging threads |
| `tap>` round-trip | A value emitted via `tap>` is received by a test sink (or Portal in dev mode) |

Zero failures, zero errors.

---

## Manual REPL Demo

Perform this session once before marking the branch ready for merge. Document the result (pass/fail notes) as a comment on the PR or inline below.

### Steps

1. Start a REPL with the `:dev` alias:
   ```sh
   clojure -M:dev
   ```

2. At the REPL prompt, verify `dev/user.clj` was auto-loaded:
   ```clojure
   (start)
   ; Expected: no exceptions; Timbre logs "system started" or equivalent
   ```

3. Open Portal (should auto-launch or be opened via `tap>`):
   ```clojure
   (tap> {:test "hello from phase-0"})
   ; Expected: value appears in Portal UI
   ```

4. Inspect that the Integrant system map is visible via `tap>` or Portal:
   ```clojure
   (tap> @system)  ; or equivalent binding from dev/user.clj
   ; Expected: EDN map with all component keys present
   ```

5. Stop the system:
   ```clojure
   (stop)
   ; Expected: no exceptions; Timbre logs "system stopped" or equivalent
   ```

6. Reset (stop + reload + start) without restarting the JVM:
   ```clojure
   (reset)
   ; Expected: clean reload; no stale state errors
   ```

7. Verify the charm.clj terminal frame appeared on `start` and cleaned up on `stop` (visible in the terminal output).

### Pass Criteria

- No exceptions at any step
- Portal received the `tap>` value in step 3
- System map visible in step 4
- `reset` in step 6 completes without error
- Terminal frame renders and cleans up

---

## Definition of Done

- [ ] `clojure -T:test` passes with zero failures
- [ ] Manual REPL demo completed and all pass criteria met
- [ ] `deps.edn` has pinned Clojure 1.12.x and Java 21 minimum documented
- [ ] `.nrepl.edn` present with port 7888
- [ ] `README.md` has a developer setup section covering JVM version and editor REPL connection
- [ ] No hardcoded magic — Integrant config is data-driven EDN
