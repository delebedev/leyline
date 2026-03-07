# Quick Draft Implementation Retro

**Date:** 2026-03-07
**Scope:** Phase 1-2 (golden stubs + real handlers), smoke test attempt

## What worked well

- **Subagent-driven execution was fast.** 15 commits across 9 subagent dispatches, each completing in 50-140s. No context pollution between tasks.
- **Golden-first approach validated again.** Extract from recording -> stub -> real logic is a reliable pattern. Catches wire format issues early.
- **Reusing Course infrastructure.** DraftSession parallels Course cleanly. CourseService.join() needed only a new branch + completeDraft() method. No refactoring needed.
- **TDD caught real issues.** DraftService tests forced correct pack advancement logic (pack exhaustion -> next pack) before any integration.
- **Batching independent tasks.** Tasks 1-3 (enums/registry) and 11-12 (wire builder + parser) batched into single subagents without conflicts.

## What didn't work

- **Wire values not validated against recording.** Two crashes from invalid Arena enum values:
  1. `FormatType: "BotDraft"` -- not a valid Arena `EFormatType` (real server sends `"Draft"`)
  2. `EventTags: ["Draft"]` -- not a valid Arena `EventTag` (real server sends `"QuickDraft"`)

  **Root cause:** Plan specified values without checking the recording. The recording has the exact EventDef shape at CmdType 624 -- should have extracted and compared before writing the EventDef.

- **`just serve` doesn't rebuild.** It uses a pre-built classpath from `target/classpath.txt`. After code changes, must run `just build` before `just serve`. Wasted two restart cycles on stale binaries.

- **`just build` not in plan.** The plan says "just serve" for smoke tests but never mentions rebuilding. Subagents committed code but the running server had the old jar.

- **GetCoursesV2 empty on fresh DB.** When courseService is wired, it queries SQLite (empty) instead of returning default seed courses (Ladder, Play, Jump_In). Had to add merge logic. This was a pre-existing gap exposed by the fresh player.db.

- **Skipped spec review for speed.** The subagent-driven-development skill calls for two-stage review (spec + quality) after each task. Skipped both to move fast. The FormatType/EventTag bugs would have been caught by a spec review comparing against the recording.

## Takeaways

1. **Always extract EventDef fields from recording, not from guesses.** Add a conformance step: `just fd-response 624 | jq '.Events[] | select(.InternalEventName | contains("QuickDraft"))'` before writing the EventDef.
2. **`just build && just serve`** -- never just `just serve` after code changes.
3. **Fresh player.db breaks seed courses.** Need a startup seeder or the merge-defaults pattern we added.
4. **Spec review is worth the cost** when wire format matters. Skip it for pure domain logic (DraftService, DraftSession) but never for EventDef/wire builder tasks.
