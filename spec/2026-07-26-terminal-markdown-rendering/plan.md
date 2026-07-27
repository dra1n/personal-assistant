# Plan: Phase 8 — Terminal Markdown Rendering

## Task groups

### Group 1 — Dependency & renderer skeleton
- [x] Add `io.github.nextjournal/markdown` to `deps.edn` (0.7.225)
- [x] Create `pa.ui.view.markdown` (clean reimplementation using the spike as a
      design reference — not a cherry-pick): pure `(render md-string width)`
      parsing with `nextjournal.markdown` and walking the AST
- [x] Implement the character-level inline model: flatten inline nodes to
      `[char style-map]` pairs; `:softbreak` → space, `:hardbreak` → `[:break]`
      marker; render runs through `charm.style` (magenta code, blue link, cyan
      heading — spike palette kept)
- [x] Implement visible-length word-wrap: greedy pack to `width`, hard-split any
      word longer than `width` without overrunning the frame

### Group 2 — Block node coverage
- [x] Headings, strong / em / inline-code / strikethrough (→ `:faint`) / link
- [x] Bullet + numbered lists, including nesting; blockquote gutter
- [x] Fenced code blocks (with gutter-aligned language line; optional bordered
      block — gutter-aligned; bordered box left as the "optional" no-op)
- [x] GFM tables with per-cell inline styling and width-aware column sizing /
      truncation
- [x] Task-list items (`- [ ]` / `- [x]`) and footnotes rendered specially

### Group 3 — Config-driven default setting
- [x] Default `:markdown` to `true` in `pa.state.db/initial-db` (`:settings`)
- [x] Read a `:settings` map from `config.edn` at startup and merge it over the
      code defaults (config wins), so `{:settings {:markdown false}}` disables it
      — `#setting [:settings]` → dispatcher dispatches `:system/settings-loaded`
      → `tr/merge-settings` via `:db`
- [x] Add a commented `:settings {:markdown true}` example to
      `resources/templates/config.edn` so the override is discoverable (done)

### Group 4 — View integration behind the toggle
- [x] In `conversation-content` / `render-turn` (`pa.ui.view`), route committed
      **assistant**, non-tool turns through `markdown/render` when
      `(queries/setting db :markdown)` is on; everything else keeps `wrap-text`
- [x] Tag committed assistant turns with the `:markdown` setting; guard tagging on
      `map?` so non-map sentinel entries are left untouched
- [x] Ensure user turns, the live stream, pending turns, and tool output are never
      markdown-rendered

### Group 5 — Caching in the refresh path
- [x] Render markdown only on commit; cache the rendered committed block in the
      model / `refresh-conversation` path (`pa.ui.app`) — split
      `conversation-content` into pure `committed-content` + `streaming-tail`
- [x] Cache key `[committed-conversation width md?]`; invalidate on new commit,
      terminal resize (width change), and `:markdown` toggle
- [x] Append the plain streaming turn on top of the cached block each frame — no
      markdown parse per `:llm/delta` (verified: 20 deltas → 1 render)

### Group 6 — Tests
- [x] `pa.ui.view.markdown/render` (assert on ANSI-stripped output): headings;
      emphasis (markers consumed, text kept); inline code; lists (marker
      transformed, nesting preserved); fenced code block; blockquote gutter; table
      alignment; thematic break; task-list; footnote — `pa.ui.view.markdown-test`
- [x] Wrapping: long paragraphs wrap to width; soft breaks become spaces; a word
      longer than the width hard-splits without overrunning the box
- [x] Integration (`conversation-content`): `:markdown` on renders committed
      assistant turns; a user turn with markdown syntax stays literal; off shows
      raw source; the live stream is never rendered even with the setting on;
      frame height unchanged — `pa.ui.view-test`
- [x] Default & config: `:markdown` defaults on; `{:settings {:markdown false}}`
      via `:system/settings-loaded` disables it — `pa.runtime.settings-test`
- [x] Caching: committed block rendered once across many deltas (no re-parse per
      delta); a width change invalidates and re-wraps; toggling `:markdown`
      invalidates — `pa.ui.app-test`
- [x] Regression: non-map conversation entries do not crash the tagging path

### Group 7 — Manual verification & cleanup
- [x] Manual verification — done periodically by the user at the running app
      (demo namespace not needed; REPL smoke checks used during development)
- [x] Confirm the palette overlap with the app's cyan accent is acceptable for now
      (kept per decision) and note it for the follow-up

## Notes
- **Sequencing:** Groups 1→2 build the pure renderer bottom-up and can be fully
  test-driven before any UI wiring. Group 3 (config default) is independent and
  can land in parallel. Group 4 depends on Groups 1–2 (the renderer must exist)
  and on Group 3 for the flag default. Group 5 depends on Group 4. Group 6 tests
  are written alongside each group but the integration/caching tests need Groups
  4–5 in place.
- The spike is a **reference only** — reimplement cleanly; do not merge the spike
  branch or cherry-pick its commits.
- Palette work is deliberately excluded — keep cyan/magenta/blue as the spike had
  them.
- Preserve the spike findings: `:softbreak` → space; assert on marker
  *transformation* not the exact bullet glyph; wrap to `text-width` so the fixed
  frame height stays exact.
