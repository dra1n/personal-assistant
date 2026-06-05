# Validation: Phase 4c — Tool System (YouTube Transcripts)

## Definition of done

Phase 4c is complete when the `youtube-transcript` tool is registered in the tool
registry, all mocked tests pass, a live REPL call against a real public video returns
readable transcript text (or a typed error for a restricted video), and the LLM
successfully invokes the transcript tool in a multi-hop chain — receiving the result
and using it to answer a question about the video.

## Checklist

### Tests

- [ ] Happy path (manual captions): mocked watch-page + timedtext responses → plain text
      returned with `:transcript/kind :manual`
- [ ] Happy path (auto-generated): `captionTracks` entry has `"kind": "asr"` →
      `:transcript/kind :auto` in result
- [ ] Language match: `:lang "es"` with Spanish track in `captionTracks` → Spanish
      transcript returned
- [ ] Language fallback: `:lang "fr"` with no French track → first available track
      returned (not an error)
- [ ] No captions: mocked response with empty `captionTracks` → `:type :tool/no-transcript`
- [ ] Network / parse failure: hato throws or returns non-200 → `:type :tool/unavailable`
- [ ] Restricted video: HTTP 403 or known restriction signal in response →
      `:type :tool/restricted`
- [ ] Schema validation: missing `:url-or-id` argument → `:type :tool/invalid-args` via
      the Phase 4b enforcement in `:tool/invoke` (no new code needed, just verify)
- [ ] Dry-run: assert no HTTP calls made; correct effect descriptor logged

### Behaviors

- [ ] REPL smoke test: `(invoke-tool :youtube-transcript {:url-or-id "<real-url>"})` at
      the REPL returns a non-empty string of transcript text
- [ ] Clean error at REPL: a known restricted or caption-free video returns a
      `:tool/result` map (not a thrown exception)
- [ ] Tool appears in the tools list advertised to the LLM alongside Phase 4 / 4b tools

### Integration

- [ ] Tool registered and invocable via `:tool/invoke` → flows through effect executor →
      emits `:tool/result` event onto the event bus
- [ ] LLM multi-hop chain: ask a question requiring a YouTube transcript →
      LLM emits a tool call for `youtube-transcript` → result injected into follow-up
      `:llm/invoke` → LLM answers using transcript content
- [ ] Structured log entry emitted for every invocation (tool name, args, result shape,
      duration) — same format as Phase 4 filesystem tools and Phase 4b network tools

## Merge criteria

- All mocked tests pass with no real HTTP calls
- REPL smoke test produces a readable transcript from a real public YouTube video
- LLM multi-hop chain confirmed end-to-end in a live REPL session
- No exceptions propagated for any error case — all failures are `:tool/result` maps
- Tool appears in the advertised tools list sent to the LLM
