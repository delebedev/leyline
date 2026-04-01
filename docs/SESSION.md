# Last Session

**Date:** 2026-03-31
**Branch:** `claude/eloquent-mcnulty` (PR #310)

## What happened

Playtested all 5 mechanics from the branch. Found and fixed real bugs along the way.

1. **Rebased branch on main**, resolved SESSION.md conflict, pushed
2. **Forge event fix:** moved `GameEventExtrinsicKeywordAdded` to fire after `changedCardKeywords.put()` (delebedev/forge#204)
3. **Manifest detection bug:** `detectArenaManifestHash()` picked category-prefixed manifests (Audio_), causing "Corrupted data" on Arena boot after updates. Fixed with hex-only regex filter.
4. **Optional action signal wiring:** `awaitPriorityWithTimeout` didn't check `pendingOptionalAction` — 10s delay on shock land prompt. Added the check.
5. **Keyword grant wiring gap:** `keywordSnapshot` computed but never passed to ObjectMapper — creatures got AddAbility pAnns but empty `uniqueAbilities`. Threaded snapshot through ZoneMapper → ObjectMapper → CardProtoBuilder. Added `KeywordGrantOverrunTest` integration test.
6. **Arena playtested all 5 mechanics** — all functional. Updated PR description with results.

## Changed
- `app/.../LeylineMain.kt` — manifest hex-only filter
- `matchdoor/.../GameBridge.kt` — pendingOptionalAction check in awaitPriority
- `matchdoor/.../ObjectMapper.kt`, `ZoneMapper.kt`, `StateMapper.kt` — keyword snapshot wiring
- `KeywordGrantOverrunTest.kt` + puzzles for overrun, morbid, angelic-destiny

## Open threads
- **Shock land wrong UI** (leyline-6hs) — needs ReplacementEffect pAnn with allocated affectorId + promptId 2233. Real server wire shape documented in beads issue.
- **Puzzle tooling bugs** (leyline-1el, leyline-crk) — scry-ts stale state after match, puzzle accumulator
- **`just dev-setup`/`just dev-teardown`** is the toggle for Arena↔leyline. `services.conf` lives in StreamingAssets inside app bundle.
