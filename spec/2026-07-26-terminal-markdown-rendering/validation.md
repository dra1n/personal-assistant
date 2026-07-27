# Validation: Phase 8 — Terminal Markdown Rendering

## Definition of done
`pa.ui.view.markdown` is a pure, tested renderer that turns an assistant turn's
Markdown into charm-styled, width-wrapped terminal text covering the full node
set (headings, emphasis, inline code, strikethrough, links, lists, blockquotes,
fenced code, GFM tables, thematic breaks, task-lists, footnotes). It is wired into
`conversation-content` / `render-turn` behind the `:markdown` setting so **only
committed assistant turns** render — user, live-stream, pending, and tool turns
stay plain. `:markdown` defaults to `true` and is overridable via a `config.edn`
`:settings` map. Rendering happens **once per commit** and is cached in the
`refresh-conversation` path (keyed by `[committed-conversation width md?]`), so no
markdown is parsed per streamed delta and the fixed-height frame stays exact. All
tests below pass under the auto-discovering runner.

## Checklist

### Tests
- [ ] `render` output (ANSI-stripped): headings; emphasis with markers consumed
      and text kept; inline code; bullet/numbered lists with marker transformed and
      nesting preserved; fenced code block; blockquote gutter; table alignment;
      thematic break; task-list items; footnotes
- [ ] Wrapping: long paragraphs wrap to width; `:softbreak` becomes a space (no
      "wrapped tothe" merge); a word longer than width hard-splits without
      overrunning the box
- [ ] Integration (`conversation-content`): setting on → committed assistant turns
      rendered; a user turn with markdown syntax stays literal; setting off → raw
      source; the live stream is never rendered even when the setting is on
- [ ] Default & config: `:markdown` defaults on; `{:settings {:markdown false}}`
      in `config.edn` disables it at startup
- [ ] Caching: committed block rendered once across many deltas (assert no
      re-parse per delta); width change (resize) invalidates and re-wraps; toggling
      `:markdown` invalidates
- [ ] Regression: non-map conversation entries do not crash the tagging path

### Behaviors
- [ ] With `:markdown` on, an assistant reply containing headings/lists/code/tables
      renders styled in the terminal (verified via the demo namespace or REPL)
- [ ] `/markdown off` returns subsequent (and re-rendered) assistant turns to raw
      source; `/markdown on` restores styling
- [ ] A user message typed with literal `*asterisks*` renders verbatim, not italic
- [ ] Mid-stream, a half-open fenced code block does not garble the live preview;
      styling appears only once the turn commits
- [ ] Frame height is unchanged with markdown on vs off (wraps to `text-width`)

### Integration
- [ ] `pa.ui.view` and `pa.ui.view.markdown` remain pure — no runtime-state reads
      inside the renderer; the flag lookup and caching live in the view/model layer
- [ ] The cache invalidates correctly on the three triggers (new commit, resize,
      toggle) and nowhere leaks stale rendered output
- [ ] Adding markdown rendering touched only the UI/view + state-default layers —
      no changes to the event/effect pipeline, tools, or storage

### Merge criteria
- [ ] All Phase 8 tasks in `spec/.../plan.md` are checked, including the
      deferred-polish items pulled into this branch (code-block alignment, table
      sizing/truncation, link URLs/OSC-8, task-lists, footnotes)
- [ ] Full test suite passes (`clojure -M:test`)
- [ ] No per-delta markdown parsing (caching verified by test)
- [ ] Palette kept as the spike had it (cyan/magenta/blue); the cyan-accent overlap
      is noted as a follow-up, not fixed here
- [ ] The Phase 8 checkboxes in `spec/roadmap.md` are ticked to match the shipped
      work
