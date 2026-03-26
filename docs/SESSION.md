# Last Session

**Date:** 2026-03-26
**Branch:** `feat/flashback-and-mandatory-cost`

## What happened

Implemented two end-to-end mechanics in parallel (sub-agent dispatch):

### Flashback (Think Twice)
- Extended `ActionMapper` to scan GY + Exile for castable spells — flashback, escape, and other cast-from-zone abilities now offered to client
- `canCastFromZone()` (mayPlay grants) does NOT cover keyword alt costs; `chooseCastAbility` via `getAlternativeCosts` is the correct path
- New `addManaCostFromForge()` helper converts Forge `ManaCost` shards to proto `ManaRequirement` — flashback cost differs from base cost
- Forge's `AlternativeCost.Flashback` replacement effect handles resolve-to-exile automatically — zero leyline changes
- Extracted `addZoneCastActions()` to fix detekt NestedBlockDepth
- `MatchFlowHarness.castSpellByName` extended: Hand → GY → Exile search order
- Puzzle: `flashback-think-twice.pzl` — Think Twice in GY, 3 Islands, draw + exile verified
- Tests: 2/2 pass (action mapping + full integration cycle)

### Mandatory additional cost — discard (Mardu Outrider)
- Added `PromptSemantic.SelectNDiscard` to `InteractivePromptBridge`
- Tagged typed discard path in `WebCostDecision.selectCards()` with new semantic
- `PromptClassifier` branch routes `SelectNDiscard` → `ClassifiedPrompt.SelectN(Discard)`
- `TargetingHandler.handlePostCastPrompt()` extended with SelectN case for discard-during-cast
- `RequestBuilder` sets `context=Discard_a163`, `listType=Static`, `optionContext=Payment`
- Puzzle: `mandatory-cost-mardu-outrider.pzl` — Mardu Outrider + Mountain in hand, 3 Swamps
- Tests: 5/5 pass (SelectNReq shape, resolves to BF 5/5, discard to GY, hand consumed, state valid)

### Also
- 19 card specs + 3 skills landed on main via PR #240 (previous session, merged before this one)
- `SYNTHESIS.md` with 3-tier horizontal layers now in `docs/card-specs/`
- Full gate: 646/646 pass, detekt clean

## Commits (on branch)
1. `1fd57b2` feat(matchdoor): flashback cast from graveyard — Think Twice end-to-end
2. `c1e979d` feat(matchdoor): mandatory additional cost (discard) — Mardu Outrider end-to-end
3. `e6538ca` fix: extract addZoneCastActions to fix detekt NestedBlockDepth

## Open threads
- **Arena playtest not done:** `screencapture -R` lacks screen recording permission in agent terminal. Server runs, puzzles load, but can't automate clicks/captures. Manual playtest needed — puzzles are ready in `leyline.toml` and via `inject-puzzle` API.
- **PayCostsReq conformance:** Real server uses PayCostsReq promptId=1024 → EffectCostResp for mandatory costs. We use SelectNReq (works end-to-end, wrong wire shape). Conformance follow-up — create issue.
- **AutoTap skipped for zone casts:** Flashback cost differs from `cardData.manaCost`, so autoTap solution not generated for GY/Exile casts. Client falls back to manual tap. Low priority.
- **Library search still blocked:** puzzle infra uses InMemoryCardRepo but search needs real card DB (from Mar 22 session). Unrelated to this branch.
- **Surveil 2 / counterspell still unrecorded** (from Mar 22).

## Next
- Manual playtest both puzzles in Arena (grant screen recording permission or use a terminal that has it)
- Create PR linking issues #192 (mandatory cost) and flashback (no existing issue — create one)
- PayCostsReq conformance issue
- Pick next horizontal layer from SYNTHESIS.md (counter type mapper or token registry are Tier 1 — no logic, just data)
