# Quick Draft Implementation Retro

**Date:** 2026-03-07
**Scope:** Phase 1-3 (golden stubs → real handlers → real Forge packs), full smoke test

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

---

## Phase 3: Real packs + full smoke test

### What worked well

- **DraftPackGenerator followed SealedPoolGenerator pattern exactly.** Same module (matchdoor), same injected-lambda wiring in LeylineServer, same CardRepository lookup. Zero new patterns needed.
- **Golden inventory bump unblocked paid events.** start-hook.json `Gems: 10000, Gold: 50000` — simple fix, enables all paid event testing.
- **Full draft flow worked end-to-end.** 39 DraftPick calls, all 3 packs, draft completion → DeckSelect → deckbuilding → Event_SetDeckV2. Client UI rendered real ECL card art/stats throughout.
- **Forge booster generation for ECL worked out of the box.** Only 1 unmapped card (Stoic Grove-Guide) out of 42.

### What didn't work

- **`just build` doesn't rebuild matchdoor jar from new files.** Gradle config cache + jar dependency means adding a new .kt file to matchdoor requires `./gradlew :matchdoor:jar --rerun-tasks` before `just build` picks it up. Wasted 2 restart cycles on NoClassDefFoundError.
- **Forge boosters have variable sizes (13-14 cards).** DraftService assumes CARDS_PER_PACK=13 but Forge can generate 14-card packs. Last card in oversized pack gets stuck — client shows Pick 13/14 but there's no server-side handling for the 14th pick. Had to manually pick it.
- **Single remaining card in draft has no OCR text.** When only 1 card remains, the UI shows a tiny thumbnail with no name/stats overlay. OCR finds nothing in the pick area. Had to find it visually — it's at approximately (68, 140) in logical coords.
- **Draft card clicks need ~1s delay.** Clicking card then immediately clicking Confirm Pick doesn't register — the selection animation needs time. 0.5s was too fast, 1s works.
- **Trashing player.db while server is running breaks everything.** Account table disappears mid-session. Must restart server after DB reset.

### Takeaways

5. **Force jar rebuild when adding new files to matchdoor.** `./gradlew :matchdoor:jar --rerun-tasks && just build` — or better, fix the build system to detect new source files.
6. **Pack size must be dynamic.** Change CARDS_PER_PACK to read from actual pack size, not hardcoded 13. Forge boosters vary.
7. **Draft automation: 1s delay between card click and Confirm Pick.** Card selection needs animation time.
8. **Last card in pack: click (68, 140).** Single remaining card renders as tiny thumbnail in upper-left with no OCR text.
9. **Always restart server after DB reset.** Never trash player.db with a running server.
