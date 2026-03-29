# Brawl Format Support — Implementation Plan

## Overview

Four gaps in the FD-to-MD pipeline drop commander zone data and always create a Standard Constructed game. Fixing them threads commandZone through the entire flow and selects the correct Forge game type for Brawl events.

## Pre-requisite: Record a Real Brawl Match

**Why first:** The project rule is "recording is the spec." A real recording reveals whether Arena sends Brawl-specific protocol elements we haven't predicted (e.g., commander tax annotations, zone 26 initial state details, starting life 25 signaling, special mulligan handling).

**How:**
1. `just serve-proxy` — captures FD + MD frames
2. Queue for StandardBrawl or HistoricBrawl in Arena client
3. Play a full game casting the commander at least twice (to see tax behavior)
4. `just wire` and `just tape` to analyze the recording

**What to look for:**
- Does the GRE ConnectReq or initial GSM carry any Brawl-specific field?
- Does the initial GSM put commander in zone 26 (Command) or is it placed by engine logic?
- Are there commander-tax-specific annotations or is it just a mana cost increase?
- Starting life total — is it in a GRE message or purely engine-side?
- Any special mulligan rules for Brawl (free mulligan)?

**Risk:** If the recording reveals unexpected protocol requirements, the plan below expands. The 4 gaps identified are necessary but may not be sufficient.

---

## Gap 1: `AppMatchCoordinator.resolveDeckJson()` drops commandZone

**File:** `app/main/kotlin/leyline/infra/AppMatchCoordinator.kt`

**Problem:** `cardsToJson()` at line 77-81 only serializes `MainDeck` and `Sideboard`. The `Deck.commandZone` field (which the DB stores and FD parses correctly) is silently dropped.

**Fix:**
```
private fun cardsToJson(mainDeck: List<DeckCard>, sideboard: List<DeckCard>, commandZone: List<DeckCard> = emptyList()): String =
    buildJsonObject {
        put("MainDeck", DeckWireBuilder.cardsToJsonArray(mainDeck))
        put("Sideboard", DeckWireBuilder.cardsToJsonArray(sideboard))
        if (commandZone.isNotEmpty()) {
            put("CommandZone", DeckWireBuilder.cardsToJsonArray(commandZone))
        }
    }.toString()
```

Update callers:
- `resolveDeckJson()` line 58: pass `it.commandZone`
- `resolveDeckJson()` line 63 (course path): pass `courseDeck.commandZone` — check if `CourseDeck` has commandZone; if not, pass `emptyList()` (sealed/draft courses won't have commanders)
- `resolveDeckJsonByName()` line 68: pass `deck.commandZone`

**Scope:** ~10 lines changed in one file. No new dependencies.

**Test:** Unit test in `app/` verifying JSON output contains `CommandZone` key when non-empty, and omits it when empty.

---

## Gap 2: `DeckConverter.toDeckText()` has no Commander section

**File:** `matchdoor/src/main/kotlin/leyline/bridge/DeckConverter.kt`

**Problem:** `toDeckText()` at line 17-41 only emits Main and Sideboard sections. No `[Commander]` header is ever produced. When the text reaches `DeckLoader.parseDeckList()`, all cards go to Main or Sideboard — the Forge `Deck` has no `DeckSection.Commander` entries, so `RegisteredPlayer.forCommander()` finds no commander.

**Fix:** Add optional `commandZone` parameter:
```kotlin
fun toDeckText(
    mainDeck: List<CardEntry>,
    sideboard: List<CardEntry>,
    nameByGrpId: (Int) -> String?,
    commandZone: List<CardEntry> = emptyList(),
): String = buildString {
    // Commander section first (Forge convention)
    if (commandZone.isNotEmpty()) {
        appendLine("[Commander]")
        for (card in commandZone) {
            val name = nameByGrpId(card.grpId)
            if (name != null) appendLine("${card.quantity} $name")
            else log.warn("DeckConverter: unknown commander grpId {}", card.grpId)
        }
    }
    // Main deck (no header needed — default section)
    for (card in mainDeck) { ... }
    // Sideboard
    if (sideboard.isNotEmpty()) { ... }
}
```

Also update `toForgeDeck()` to accept and forward `commandZone`.

**Test:** Extend `DeckConverterTest` with:
- "includes commander section" — verify `[Commander]` header + card name present
- "empty commandZone omits header" — verify no `[Commander]` in output

**Scope:** ~15 lines in DeckConverter, ~5 lines in test.

---

## Gap 3: `MatchHandler.convertArenaCardsToDeckText()` only parses MainDeck + Sideboard

**File:** `matchdoor/src/main/kotlin/leyline/match/MatchHandler.kt`

**Problem:** Lines 402-406 only extract `MainDeck` and `Sideboard` keys from JSON. The `CommandZone` key (now present from Gap 1 fix) would be ignored.

**Fix:**
```kotlin
private fun convertArenaCardsToDeckText(cardsJson: String): String {
    val obj = lenientJson.parseToJsonElement(cardsJson).jsonObject
    val mainDeck = parseDeckSection(obj, "MainDeck")
    val sideboard = parseDeckSection(obj, "Sideboard")
    val commandZone = parseDeckSection(obj, "CommandZone")
    return DeckConverter.toDeckText(mainDeck, sideboard, cards!!::findNameByGrpId, commandZone)
}
```

**Scope:** 2 lines changed. Depends on Gap 2 being done first.

---

## Gap 4: `GameBridge.start()` hardcodes `createConstructedGame`

**Files:**
- `matchdoor/src/main/kotlin/leyline/game/GameBridge.kt` (lines 360, 445)
- `matchdoor/src/main/kotlin/leyline/match/MatchHandler.kt` (lines 209-224)

**Problem:** `GameBridge.start()` always calls `GameBootstrap.createConstructedGame()`. Even with a correct commander deck, Forge won't apply Brawl rules (25 life, singleton, commander tax, command zone) without `GameType.Brawl`.

**Fix — Option A (event name flows to GameBridge):**

Add an optional `variant` parameter to `GameBridge.start()` and `Match.start()`:
```kotlin
// Match.kt
fun start(seed: Long? = null, deckList: String? = null,
          deckList1: String? = null, deckList2: String? = null,
          variant: String? = null) {
    bridge.start(seed, deckList, deckList1, deckList2, variant)
    ...
}

// GameBridge.start()
fun start(seed: Long? = null, ..., variant: String? = null) {
    ...
    val g = if (variant != null) {
        GameBootstrap.createCommanderGame(deck1, deck2, variant)
    } else {
        GameBootstrap.createConstructedGame(deck1, deck2)
    }
    ...
}
```

Same pattern for `startTwoPlayer()` / `createTwoPlayerCommanderGame()`.

In `MatchHandler.processGREMessage()`, derive the variant from `coordinator?.selectedEventName`:
```kotlin
val variant = when {
    eventName?.contains("Brawl", ignoreCase = true) == true -> "brawl"
    // future: eventName?.contains("Commander") -> "commander"
    else -> null
}
```

Then pass `variant` to `it.start(seed = ..., deckList1 = ..., deckList2 = ..., variant = variant)`.

**Why event-name matching is safe:** The event names are controlled by our own `fd-golden/events.json` (`Play_Brawl`, `Play_Brawl_Historic`). We define them, so the string check is stable. A helper function `isBrawlEvent(eventName: String?)` keeps the logic centralized.

**Scope:** ~20 lines across 3 files. No new dependencies.

**Test:** 
- Unit test: `isBrawlEvent("Play_Brawl")` returns true, `isBrawlEvent("Ladder")` returns false
- Integration: A `MatchFlowHarness` test that starts a Brawl match and verifies `game.rules.gameType == GameType.Brawl` and starting life == 25

---

## Implementation Sequence

```
Phase 0: Record real Brawl match via proxy
   └─ Analyze recording, update plan if surprises found

Phase 1: Data flow (can be done in parallel)
   ├─ Gap 1: AppMatchCoordinator — commandZone in JSON
   └─ Gap 2: DeckConverter — [Commander] section in deck text

Phase 2: Wiring (depends on Phase 1)
   └─ Gap 3: MatchHandler — parse CommandZone from JSON

Phase 3: Game type selection (depends on Phase 2)
   └─ Gap 4: GameBridge/MatchHandler — variant routing

Phase 4: Validation
   ├─ Unit tests for each gap
   ├─ End-to-end: proxy → Arena → Brawl game plays with correct rules
   └─ Verify: starting life 25, commander in command zone, commander tax works
```

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Recording reveals unknown Brawl protocol elements | Plan expands | Do recording FIRST before coding |
| Forge `RegisteredPlayer.forCommander()` expects deck structure we don't produce | Commander not placed in command zone | Verify with DeckLoader test that `[Commander]` section → `DeckSection.Commander` → `deck.get(DeckSection.Commander)` non-empty |
| Commander tax tracking via annotations not implemented | Visual desync — client shows wrong mana cost | Recording will reveal if Arena expects specific annotations; Forge handles tax internally so gameplay works even without annotation |
| CourseDeck (sealed/draft) doesn't have commandZone field | Compile error or NPE | Check CourseDeck definition; likely just pass emptyList() since sealed/draft aren't Brawl |
| PvP Brawl (two humans) needs same routing | Feature incomplete for PvP | `startTwoPlayer` gets same variant parameter; both paths covered |
| Historic Brawl vs Standard Brawl need different card pool validation | Wrong cards allowed | Card pool validation is FD-side (deck format filter); engine just runs the game. Not a blocker. |

## Estimated Effort

- Gap 1: ~15 minutes (trivial plumbing)
- Gap 2: ~15 minutes (small API addition + test)
- Gap 3: ~5 minutes (2-line change)
- Gap 4: ~30 minutes (touches 3 files, needs helper function, integration test)
- Recording + analysis: ~45 minutes
- End-to-end validation: ~30 minutes

Total: ~2.5 hours including recording
