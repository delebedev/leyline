# Autoplay Agent Evolution — 2026-03-10

## Context

Autoplay harness (v8.2) plays 7-11 cards/game via OCR + scry + blind drags. Hitting a wall: auras, targeting, mana choices, combat all require precision beyond current heuristics. Discussion covered how to reach SOTA self-verifying agentic loop for game interaction.

## Key questions explored

### 1. Can `arena play` work in proxy mode?

**Problem:** `arena play` uses debug API (`/api/id-map`) which only works when Forge engine is running (local mode). Proxy mode has no Forge, so id-map returns empty.

**Finding:** Scry already has the same data from Player.log — hand cards with names/instanceIds, zones, actions. Works in all server modes.

**Resolution:** Added scry fallback to `arena play`. Tries debug API first, falls back to `bin/scry state` CLI. Zone name normalization (`ZoneType_Hand` → `Hand`) handled. 18 tests added. Prompt updated to v9 — `arena play` is now the primary play method, scan-and-cancel eliminated.

### 2. Hand visual sort order — what is it?

**Initial assumption (wrong):** `SetHandManaCostSorting()` in IL2CPP dump → hand re-sorts every time it changes.

**Denis's observation:** Opening hand is sorted (CMC, WUBRG within CMC). Draws append from the right. Not re-sorted on draw.

**Implication:** Visual order = `sorted(opening_hand) + [draw1, draw2, ...]` minus played cards. Path-dependent after opening. Can't predict positions from scry zone order alone without tracking draw history.

### 3. Can OCR read hand card names?

**Test at 960px (default):** 5/8 cards detected. Missing: 2 Swamps + Plains (lands at left edge of fan, most rotated/overlapped).

**Test at 1920px (--hires):** Same 5/8. Resolution wasn't the bottleneck.

**Screenshot analysis (Denis):** All 8 card names are visually readable on title bars, even overlapping ones. OCR just fails to extract the rotated/overlapped edges. The information IS there.

### 4. Spatial layout structure

Denis pointed out the y-axis hierarchy:
- **Battlefield row** (high y, e.g. y < ~200): lands and permanents always above a threshold
- **Hand row** (low y, e.g. y > ~460): the card fan, always below
- **Hover preview**: card jumps up temporarily, already filtered by `cy > 490`

This means:
- No confusion between battlefield and hand cards at the same name
- Can crop hand strip for focused OCR (less noise)
- Play verification: card appears at battlefield-y = success

### 5. Broader architecture — four tiers discussed

**Tier 0 (done):** Scry fallback for `arena play`, v9 prompt.

**Tier 1 (MTGA debug panel):** 23-tab debug panel already role-unlocked via JWT. Autoplay scripting framework (30+ action types), DuelScene debug overlay (backtick key), localhost:5001 workflow logger — all untested, potentially game-changing.

**Tier 2 (Forge as oracle):** `SpellAbilityPicker.chooseSpellAbilityToPlay()` evaluates all legal plays via simulation. Needs Arena→Forge state mapping (reverse of existing matchdoor bridge).

**Tier 3 (headless Forge AI vs Sparky):** Proxy intercepts player actions, Forge AI decides, no UI needed. Client optional. Infinite recordings at zero UI automation cost.

### 6. Wild ideas explored

**Code injection:** BepInEx/MelonLoader for Unity. Could expose "play card by instanceId" via localhost. IL2CPP dumps + Ghidra analysis available but no runtime hooks yet.

**Forge board eval:** Confirmed feasible — `GameStateEvaluator.getScoreForGameState()` scores positions, 150+ ability evaluators, combat planning. Gives "best play" not just "available plays."

---

## Prioritized suggestions

### ~~P0: Hand-strip OCR~~ ✅

Done. PIL crop + 2x upscale + fuzzy match → 7-8/8 hand card detection. Root cause was sips `--cropOffset` silently failing. Also: apostrophe normalization, x-bounds filtering (120–800).

### ~~P1: OCR-detection fusion~~ ✅

Done. OCR is primary in `_find_hand_card`, detection model is fallback. With 7-8/8 OCR hit rate, fusion logic is rarely needed.

### ~~P2: --hires default for play~~ ✅

Done. `_find_hand_card_ocr` always captures at retina resolution (1920px).

### ~~P3: Draw-order tracking in scry~~ — skipped

Moot — OCR gives name→cx directly, no need to predict positions from draw history.

### P3.5: Arc-aware click geometry ✅ (bonus)

`_hand_adjust()`: cosine arc (drop=35px, center=500), base +20px shift, +20px proportional nudge. Converts OCR title center → card logical center. Calibrated with visual overlay.

### P3.6: scry proto3 accumulator fix ✅ (bonus)

Diff objects are complete snapshots, not sparse patches. Field-absent = default (false/0). Fixes stale `isTapped` on lands after untap step.

### P4: Investigate MTGA debug panel (Tier 1)

Three untested systems, all already role-unlocked:
1. **Autoplay scripting** — if it can execute game actions programmatically, drag/drop problem vanishes
2. **DuelScene debug overlay** (backtick key) — may expose card positions/instanceIds visually
3. **localhost:5001 workflow logger** — structured stream of client interactions

**Why P4 not higher:** Unknown payoff. Could be game-changing or could be vestigial stubs. Needs hands-on exploration in a live client.

### P5: Forge oracle wiring (Tier 2)

New `ForgeOracle` class: takes scry output → maps to Forge game state → asks `SpellAbilityPicker` for best play → returns recommended action with instanceId.

**Gap:** Arena→Forge state mapping. Matchdoor currently maps Forge→Arena (server direction). Reverse mapping is new work but can reuse existing card/zone definitions.

### ~~P6: Cyan glow detection~~ — skipped

Not needed. scry actions + OCR + debug API give us playability info directly.

### P7: Headless Forge AI vs Sparky (Tier 3)

Proxy intercepts player seat actions, Forge AI picks plays, leyline sends protocol response. No UI automation. Client becomes optional passive renderer.

**Biggest payoff, biggest effort.** Depends on P5 (Forge oracle) plus proxy action injection.
