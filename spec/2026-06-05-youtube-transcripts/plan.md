# Plan: Phase 4c — Tool System (YouTube Transcripts)

## Task groups

### Group 1 — Transcript tool (full implementation)

- [ ] Document the mechanism: write a brief design note in the source namespace header
      covering the timedtext API extraction approach, the unofficial-API caveat, and the
      three error types — before any implementation code is written
- [ ] Implement video ID extraction: accept a full YouTube URL (youtube.com/watch?v=,
      youtu.be/, youtube.com/shorts/, youtube.com/embed/) or a bare video ID; return the
      canonical 11-character ID
- [ ] Implement transcript fetch: GET the video watch page with hato, extract
      `captionTracks` from `ytInitialPlayerResponse` JSON, select the track matching
      `:lang` (default `"en"`) or fall back to the first available track if not found;
      fetch the timedtext URL, parse the XML, strip timestamps and tags, return plain text
      with `:transcript/kind` (`:auto` for `kind: "asr"`, `:manual` otherwise)
- [ ] Implement error surfacing: no caption tracks → `:type :tool/no-transcript`;
      network/HTTP failure or parse error → `:type :tool/unavailable`; HTTP 403 or
      known restriction signal → `:type :tool/restricted`; all returned as
      `:tool/result` maps, no exceptions propagated
- [ ] Register `youtube-transcript` in the tool registry with `:fn`, `:schema`
      (`:url-or-id` string required; `:lang` string optional), and `:description`
- [ ] Write mocked tests: stub hato responses for happy path (returns transcript text
      with `:transcript/kind`), no-captions case, network error case, restricted-video
      case, and requested-language-not-found case (falls back to first available track);
      assert each produces the correct `:tool/result` shape without throwing
- [ ] REPL smoke test: call `youtube-transcript` with a real public YouTube URL at the
      REPL; confirm readable transcript text (or a clean `:tool/result` error for a
      restricted video) is returned
- [ ] LLM multi-hop chain smoke test: ask the assistant a question that requires
      fetching a YouTube video's transcript; confirm the LLM calls `youtube-transcript`,
      receives the result, and incorporates it into the answer

## Notes

All tasks are in a single group because the phase is small, the infrastructure is
entirely in place, and there are no blocking dependencies between sub-tasks once the
design note is written. The design note task is listed first to crystallize the
extraction approach before any implementation code is written — but it can be
lightweight (a few lines in the namespace docstring, not a separate document).

REPL and LLM chain smoke tests are listed last but should be run before marking the
phase complete, not as an afterthought. They are part of the merge criteria.
