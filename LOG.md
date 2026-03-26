# Session Log — Flashback + Mandatory Cost + 3 Cards

**Date:** 2026-03-26
**Branch:** `feat/flashback-and-mandatory-cost`
**Goal:** Flashback, mandatory cost, then Electroduplicate, Novice Inspector, Immersturm Predator

## Pre-dig: 3 Card Research Findings

### Electroduplicate (flashback + copy token + targeting)
- ~55% wire coverage today. Flashback cast works (just built it).
- **abilityGrpId missing on GY cast actions** — addZoneCastActions doesn't set it, doesn't receive abilityRegistryLookup. Low fix.
- **TargetSpec pAnn** — builder exists (AnnotationBuilder:673) but NEVER CALLED. Need emission point when targets selected.
- **CastingTimeOption pAnn type=13** — no builder. Need new annotation for "cast via alternate cost".
- **TemporaryPermanent pAnn** — proto type 80 exists, no builder, no emission.
- **CopyPermanent token** — HIGH complexity. Copy inherits source grpId, not fixed token grpId. Entire subsystem missing.
- **Pragmatic scope:** Skip copy token mechanics (too large). Focus on: abilityGrpId on actions, flashback annotations. Test flashback cast+resolve+exile. Copy token is a separate issue.

### Novice Inspector (investigate, Clue token, sac-for-draw)
- Card spec says "wired" but ETB trigger annotations are a systemic gap.
- **AbilityInstanceCreated for ETB triggers** — no GameEvent for triggered ability creation. Forge fires trigger but leyline doesn't annotate it.
- **TriggeringObject pAnn** — builder exists (AnnotationBuilder:664), never called.
- **Clue grpId mapping** — needs AbilityIdToLinkedTokenGrpId entry: 86969→89236.
- **Sac-for-draw** — Treasure sac-for-mana works (TreasureTokenTest). Clue sac is similar pattern.
- **Pragmatic scope:** Test cast→ETB→Clue creation→sac-for-draw end-to-end. ETB annotations are conformance gaps, not blocking gameplay.

### Immersturm Predator (sacrifice-as-cost, tap trigger, indestructible)
- Sacrifice-as-cost: WebCostDecision.visit(CostSacrifice) fully wired, same SelectNReq path as discard.
- **Tap trigger** — GameEventCardTapped already captured. WORKING.
- **CounterAdded** — already wired. WORKING.
- **AddAbility for indestructible** — builder exists (AnnotationBuilder:542), NEVER CALLED. Hard gap — needs EffectTracker expansion.
- **targetSourceZoneId** — not populated in SelectTargetsReq. Non-blocking (Forge pre-filters).
- **Pragmatic scope:** Test sacrifice-as-cost→tap trigger→counter+exile chain. Skip AddAbility (conformance gap, not gameplay-blocking).

## Commits

1. `1fd57b2` feat(matchdoor): flashback cast from graveyard — Think Twice end-to-end
2. `c1e979d` feat(matchdoor): mandatory additional cost (discard) — Mardu Outrider end-to-end
3. `e6538ca` fix: extract addZoneCastActions to fix detekt NestedBlockDepth

## Findings

### Flashback
- ActionMapper line 124 only iterated Hand cards — GY/Exile never offered as cast sources
- `canCastFromZone()` (checking `card.mayPlay()`) does NOT cover keyword-based alt costs like Flashback. The `mayPlay` grants are a separate mechanism. Instead, `chooseCastAbility` via `getAllCastableAbilities` / `getAlternativeCosts` correctly finds flashback abilities regardless of zone.
- Forge's `AlternativeCost.Flashback` replacement effect handles resolve-to-exile automatically — zero leyline changes needed for destination zone
- ZoneTransfer zone_src is snapshot-based (dynamic) — already correct for GY→Stack
- ManaCost on the Cast action must use the flashback cost (from `sa.payCosts.totalMana`), not `cardData.manaCost` (which is the base cost). New `addManaCostFromForge()` helper converts Forge `ManaCost` shards to proto `ManaRequirement` entries.
- AutoTap skipped for zone casts — flashback cost differs from base, and the existing autoTap solver uses `cardData.manaCost`
- `startPuzzleAtMain1` doesn't work reliably for puzzles with graveyard cards (phase ends up as UPKEEP). Using `startWithBoard` for pure tests and `connectAndKeepPuzzleText` for integration tests.
- `MatchFlowHarness.castSpellByName` extended to search Hand → GY → Exile (was Hand-only)

### Mandatory cost (discard)
- `WebCostDecision.visit(CostDiscard)` already handles the engine-side discard via `selectCards()` → `InteractivePromptBridge.requestChoice()` — engine blocks waiting for choice
- Problem was classification: `PromptClassifier` auto-resolved the discard prompt instead of sending to client
- Fix: added `PromptSemantic.SelectNDiscard` to `InteractivePromptBridge`, tagged the typed discard path in `WebCostDecision.selectCards()`, added classifier branch in `PromptClassifier` for the new semantic
- Added `SelectN` case in `TargetingHandler.handlePostCastPrompt()` — discard-during-cast prompts now reach the client as SelectNReq
- `RequestBuilder` sets `context=Discard_a163`, `listType=Static`, `optionContext=Payment` for discard SelectNReq
- Real server uses PayCostsReq promptId=1024 → EffectCostResp (conformance follow-up, not blocking)

## Blockers

- **Arena playtest blocked:** `screencapture -R` fails with "could not create image from rect" — macOS screen recording permission not granted to this terminal session. `arena ocr`/`arena click` depend on `screencapture -R` via `capture_window()`. Full-screen `screencapture -x` works but captures desktop, not MTGA window (even after activation). Manual playtest needed.
- **Puzzle for flashback requires `startWithBoard`:** `startPuzzleAtMain1` skips to UPKEEP for GY-card puzzles. Not a blocker — tests use the right setup tier.

## Test Results

- FlashbackTest: 2/2 PASS (7s)
- MandatoryDiscardCostTest: 5/5 PASS (8s)
- Full gate: 646/646 PASS, detekt clean

## Manual Playtest Checklist

Both puzzles are ready. To playtest:

1. **Flashback:** `puzzle = "matchdoor/src/test/resources/puzzles/flashback-think-twice.pzl"` is set in `leyline.toml`. Start bot match → Think Twice should be in GY with 3 Islands → cast it via flashback → draw a card → Think Twice goes to Exile (not GY).

2. **Mardu Outrider:** Change puzzle in `leyline.toml` to `"matchdoor/src/test/resources/puzzles/mandatory-cost-mardu-outrider.pzl"`. Start bot match → Mardu Outrider + Mountain in hand, 3 Swamps → cast Mardu Outrider → client prompts to discard a card → discard Mountain → 5/5 creature enters BF.

Or hot-reload: `curl -s -X POST http://localhost:8090/api/inject-puzzle --data-binary @matchdoor/src/test/resources/puzzles/mandatory-cost-mardu-outrider.pzl -H "Content-Type: text/plain"` then click Pass (888,504).
