# Requirements: Phase 4c — Tool System (YouTube Transcripts)

## Goal

Extend the Phase 4 tool machinery with a YouTube transcript retrieval tool. The tool
accepts a YouTube URL or bare video ID, fetches the transcript via a transcript API,
and returns plain text. Error cases (no captions, unavailable, restricted) surface as
clean `:tool/result` maps — no thrown exceptions. The tool registers in the existing
tool registry and participates in the Phase 4b multi-hop tool-use chain. No yt-dlp
system dependency.

## Scope

### In scope

- Document and implement the transcript API mechanism: call YouTube's timedtext
  endpoint directly via hato (already in the stack), extracting caption track URLs
  from the video page source
- Accept both full YouTube URLs and bare video IDs
- Accept an optional `:lang` parameter (e.g. `"en"`, `"es"`, `"fr"`); default to `"en"`,
  fall back to first available track if the requested language is not found
- Return plain transcript text (concatenated caption lines, timestamps stripped)
- Include `:transcript/kind` in the result (`:auto` for ASR tracks, `:manual` for
  human-created captions) so callers can weight quality accordingly
- Surface all failure modes as typed `:tool/result` errors: `:tool/no-transcript`,
  `:tool/unavailable`, `:tool/restricted`
- Register in the existing tool registry with `:fn`, `:schema`, `:description`
- Mocked test suite covering happy path and all error cases
- REPL smoke test with a real public YouTube URL
- End-to-end confirmation that the LLM can invoke the transcript tool in a multi-hop
  chain (Phase 4b architecture)

### Out of scope

- yt-dlp subprocess approach
- Audio download or transcription
- Caption format selection (SRT, VTT, ASS) — plain text only
- Any caption editing, alignment, or timestamp preservation in the output

## Design decisions

1. **No system dependency** — the tool calls YouTube's unofficial timedtext API over
   HTTP using hato, the same HTTP client already in the stack. No yt-dlp, no Python
   bridge. This keeps the JVM footprint self-contained.

2. **Extraction approach** — fetch the video watch page, extract the `captionTracks`
   JSON from the embedded `ytInitialPlayerResponse`, pick the best available English
   track (or first track if none), fetch the timedtext URL, strip XML/timestamp markup,
   and return concatenated plain text. This is the same mechanism used by the canonical
   Python `youtube-transcript-api` library internally.

3. **Unofficial API caveat** — the timedtext endpoint is undocumented and may require
   maintenance if YouTube changes the page structure. This risk is acceptable for a
   personal local-first tool; it should be noted in a brief inline comment.

4. **Soft dependency** — the tool does not fail at system startup. Failures surface only
   at call time as typed `:tool/result` errors, consistent with the other tool
   implementations from Phase 4 and 4b.

5. **Language selection** — the tool accepts an optional `:lang` parameter (BCP 47
   language code, e.g. `"en"`, `"es"`, `"fr"`). When provided, the tool selects the
   matching `captionTracks` entry by `languageCode`; if not found, it falls back to the
   first available track rather than erroring, since a transcript in a different language
   is more useful than none.

6. **Auto-generated vs. manual captions** — `captionTracks` entries with `"kind": "asr"`
   are auto-generated (ASR); entries without a `kind` field (or with an empty string) are
   human-created. Both are fetched via the same timedtext endpoint and return the same XML
   format. The result map includes `:transcript/kind` (`:auto` or `:manual`) so the LLM
   and callers can factor in quality when reasoning about the content.

7. **Error taxonomy** — three distinct error types, each a `:tool/result` map with
   no exception propagation:
   - `:type :tool/no-transcript` — video exists but has no captions at all
   - `:type :tool/unavailable` — network failure, video not found, or API parse error
   - `:type :tool/restricted` — age/region gating prevents access

8. **Tool registry wiring** — registered identically to the Phase 4 filesystem tools and
   Phase 4b network tools: `:schema` advertised to the LLM, argument validation via the
   Phase 4b schema enforcement in `:tool/invoke`, dry-run mode supported, structured
   logging on every invocation.

9. **Multi-hop participation** — tools are re-advertised on every follow-up `:llm/invoke`
   in the Phase 4b chain, so the transcript tool is available in any hop without
   additional wiring.

## Context

Phase 4/4b established the full tool machinery: registry, `:tool/invoke` effect type,
argument schema validation, dry-run mode, structured logging, `:tool/result` event
wiring, and multi-hop LLM tool use. This phase is purely additive — one new tool
implementation that slots into existing infrastructure. No changes to the dispatch
pipeline, effect executor, or interceptor chain are expected.

The hato HTTP client is already in `deps.edn` from Phase 4b (web search and page
retrieval tools). The transcript tool reuses the same HTTP call pattern and the same
SSRF guard if the extracted caption URL resolves to a Google/YouTube CDN host (which it
always will, so no new SSRF surface).
