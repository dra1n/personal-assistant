# Requirements: Phase 8 — Terminal Markdown Rendering

## Goal
Render the assistant's Markdown responses as styled terminal text — headings,
emphasis, lists, code, blockquotes, tables — behind the `:markdown` runtime
setting introduced in Phase 7. Phase 7 shipped the `/markdown on|off` command and
the flag but deliberately left *rendering* to this phase. Parsing is done with
`io.github.nextjournal/markdown` (a thin Clojure-data layer over commonmark-java);
a pure renderer walks the resulting AST and emits ANSI via `charm.style`. Because
the whole conversation string is rebuilt on every streamed delta, rendered output
must be cached so markdown is never re-parsed per token.

## Scope

### In scope
- A pure renderer namespace `pa.ui.view.markdown` — `(render md-string width)` →
  a charm-styled string wrapped to `width`, with inline styling done at the
  character level (`[char style-map]` pairs) so word-wrap measures *visible*
  length and punctuation stays attached across style runs.
- Node coverage: headings; strong / em / inline-code / strikethrough / link;
  soft & hard breaks; bullet + numbered lists (nested); fenced code blocks;
  blockquotes; GFM tables; thematic breaks.
- Add `io.github.nextjournal/markdown` to `deps.edn`.
- Wire the renderer into `conversation-content` / `render-turn`
  (`src/pa/ui/view.clj`) behind `:markdown` — **committed assistant turns only**.
- Config-driven default: `:markdown` defaults to `true` in `pa.state.db`; a
  `:settings` map read from `config.edn` at startup merges over the defaults so a
  user can set `{:settings {:markdown false}}`.
- Caching: render markdown only on commit; cache the rendered committed block in
  the model / `refresh-conversation` path (`src/pa/ui/app.clj`), keyed by
  `[committed-conversation width md?]`; append the plain streaming turn on top.
- Deferred-polish items, all pulled **into this branch** (per interview):
  - Code blocks: gutter-align the fenced-language line; optional bordered block.
  - Tables: per-cell inline styling; width-aware column sizing / truncation.
  - Links: optional faint URL after the styled text / OSC-8 hyperlinks where
    supported.
  - Render task-list items (`- [ ]` / `- [x]`) and footnotes specially.
- Tests covering renderer output, wrapping, integration behind the toggle,
  config default, caching, and the non-map-entry regression.

### Out of scope
- Palette redesign. Keep the spike's palette as-is (cyan headings, magenta inline
  code, blue links) even though it overlaps the app's cyan accent — revisiting
  the overlap is a follow-up, not this branch.
- Markdown rendering of **user** turns, the **live stream**, **pending**
  (thinking…) turns, and **tool** output — these stay plain / keep their existing
  rendering.
- Syntax highlighting inside code blocks (no good JVM/TUI highlighter; code stays
  plain or a few languages map to simple ANSI at most).
- Persisting a `:markdown` toggle *back* to `config.edn` at runtime — only the
  startup config → initial `:settings` direction is in scope (the rest of
  settings persistence stays deferred from Phase 7).
- Per-*turn* caching keyed by `[content width]` — the single committed-block cache
  is enough for now; per-turn caching is a later optimization if history grows.

## Design decisions
1. **Reimplement, don't cherry-pick the spike.** The spike on branch
   `spike/markdown-render` (`pa.ui.view.markdown`, `dev/markdown_demo.clj`) is a
   **design reference only**. This branch writes a clean `pa.ui.view.markdown`
   rather than porting the spike commit, so no spike-era shortcuts are carried in.
   The demo namespace may be re-created for manual/REPL verification.
2. **Renderer is pure.** `(render md-string width)` has no side effects and no
   runtime-state access; all impurity (the `:markdown` flag lookup, caching) lives
   in the view/model integration layer, keeping `pa.ui.view` and the renderer
   testable in isolation (mission value: *Testable*, *Composable*).
3. **Character-level inline model.** Inline nodes flatten to a seq of
   `[char style-map]` pairs (a hard break becomes a `[:break]` marker). Wrapping
   measures visible characters, so ANSI escapes never corrupt column math and the
   fixed-height frame stays exact.
4. **Assistant-commit boundary only.** Only `:role :assistant` committed turns are
   markdown-rendered; tagging guards on `map?` so non-map sentinel conversation
   entries pass through untouched. The in-flight stream and pending turns are never
   tagged, so a half-open fenced block mid-stream cannot garble the preview —
   markdown applies only once a turn commits.
5. **Render on commit, cache the committed block.** `refresh-conversation` runs on
   every `:llm/delta`; the committed block is rendered once and cached, the plain
   streaming turn re-appended each frame → zero markdown work per delta. Cache key
   `[committed-conversation width md?]` invalidates on a new commit, a terminal
   resize (width change), and a `:markdown` toggle.
6. **Config merges over code defaults.** `pa.state.db/initial-db` sets
   `:settings {:markdown true}`; the startup config's `:settings` map is merged
   over these defaults (config wins), so absent config keeps the on-by-default
   behavior and `{:settings {:markdown false}}` disables it.
7. **charm.style only for SGR.** All styling goes through `charm.style`; no
   hand-written ANSI escape bytes (a raw `0x1b` is easy to drop in an edit and
   prints literal `[2m`). Strikethrough falls back to `:faint` (charm has no SGR 9).

## Context
- **Integration points** (already in the tree):
  - `src/pa/ui/view.clj` — `conversation-content` (`view.clj:110`) builds the
    turn list and `render-turn` (`view.clj:81`) renders each; committed assistant
    turns currently go through `wrap-text`. This is where the `:markdown` branch
    and `map?`-guarded tagging land.
  - `src/pa/ui/app.clj` — `refresh-conversation` (`app.clj:58`) rebuilds the
    conversation viewport on every delta; the committed-block cache lives here in
    the model path so `pa.ui.view` stays pure.
  - `src/pa/state/db.clj` — `initial-db` already carries `:settings {}`; this
    phase seeds `:markdown true` and merges config over it.
- **Phase 7 dependency (satisfied):** the `:markdown` setting, the
  `/markdown on|off` command, `set-setting` transition, and `queries/setting`
  selector all exist; this phase only adds *rendering* behind the flag.
- **Findings carried from the spike** (must be preserved in the rewrite): a
  `:softbreak` node has no content and must render as a space or adjacent words
  merge ("wrapped tothe"); the rendered bullet glyph differs by codepoint from a
  hand-typed `•`, so tests assert on the marker *transformation*, not the glyph;
  frame height stays exact because the renderer wraps to `text-width`.
- **Library rationale:** `io.github.nextjournal/markdown` wraps commonmark-java
  0.24 + GFM extensions (tables, strikethrough, task-lists, autolinks, footnotes),
  a light footprint (a few hundred KB of jars + `data.json`, no JS engine).
  Alternatives (raw commonmark-java's Java visitor API, heavier flexmark, shelling
  out to glow/mdcat) were considered and rejected.
