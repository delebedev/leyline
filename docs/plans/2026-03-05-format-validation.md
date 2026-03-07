# Format-Based Deck Validation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Validate decks against format legality (banned cards, legal sets) before match creation, so queuing Explorer rejects a Standard-only deck and vice versa.

**Architecture:** Copy forge-web's `FormatService` pattern — thin wrapper around Forge's `GameFormat.getDeckConformanceProblem()`. Validate at FD layer (MatchmakingService) before creating the match. Engine doesn't need the format — it just plays cards. Arena `deckSelectFormat` strings map to Forge format names via a static table.

**Tech Stack:** Forge `GameFormat` (already on classpath), `DeckLoader.parseDeckList` (already exists), `CardRepository.findNameByGrpId` (already exists).

---

## Key Insight: Reuse Existing Conversion

`MatchHandler.convertArenaCardsToDeckText()` already converts grpId deck cards → Forge deck text → `DeckLoader.parseDeckList()` → `forge.deck.Deck`. We need the same conversion at FD layer for validation. Extract it so both MatchHandler and FormatService can use it.

## Arena Format → Forge Format Mapping

Arena's `deckSelectFormat` values and their Forge equivalents:

| Arena (EventDef.deckSelectFormat) | Forge format name |
|-----------------------------------|-------------------|
| `Standard`                        | `Standard`        |
| `TraditionalStandard`             | `Standard`        |
| `Historic`                        | `Historic`        |
| `TraditionalHistoric`             | `Historic`        |
| `Explorer`                        | `Explorer`        |
| `TraditionalExplorer`             | `Explorer`        |
| `Timeless`                        | `Timeless`        |
| `TraditionalTimeless`             | `Timeless`        |
| `Alchemy`                         | `Alchemy`         |

"Traditional" prefix = Bo3, same card pool. Forge has no Pioneer yet (Explorer is Arena's Pioneer equivalent).

---

### Task 1: Extract deck conversion to shared utility

The grpId→name conversion currently lives in `MatchHandler.convertArenaCardsToDeckText()` (private). Extract to a shared function so FD layer can also use it.

**Files:**
- Create: `src/main/kotlin/leyline/bridge/DeckConverter.kt`
- Modify: `src/main/kotlin/leyline/match/MatchHandler.kt` — delegate to new utility
- Test: `src/test/kotlin/leyline/bridge/DeckConverterTest.kt`

**Step 1: Write the failing test**

```kotlin
package leyline.bridge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.frontdoor.domain.DeckCard
import leyline.game.CardRepository

class DeckConverterTest : FunSpec({

    val cards = object : CardRepository {
        private val db = mapOf(75515 to "Lightning Bolt", 93848 to "Counterspell")
        override fun findByGrpId(grpId: Int) = null
        override fun findNameByGrpId(grpId: Int) = db[grpId]
        override fun findGrpIdByName(name: String) = db.entries.firstOrNull { it.value == name }?.key
        override fun findAllGrpIds() = db.keys.toList()
    }

    test("converts DeckCard list to deck text") {
        val main = listOf(DeckCard(75515, 4), DeckCard(93848, 2))
        val text = DeckConverter.toDeckText(main, emptyList(), cards)
        text shouldBe "4 Lightning Bolt\n2 Counterspell\n"
    }

    test("includes sideboard section") {
        val main = listOf(DeckCard(75515, 4))
        val side = listOf(DeckCard(93848, 2))
        val text = DeckConverter.toDeckText(main, side, cards)
        text shouldBe "4 Lightning Bolt\nSideboard\n2 Counterspell\n"
    }

    test("skips unknown grpIds") {
        val main = listOf(DeckCard(99999, 1), DeckCard(75515, 4))
        val text = DeckConverter.toDeckText(main, emptyList(), cards)
        text shouldBe "4 Lightning Bolt\n"
    }

    test("toForgeDeck parses into Forge Deck") {
        val main = listOf(DeckCard(75515, 4))
        // Requires card DB initialized — skip in unit test, covered by integration
    }
})
```

**Step 2: Run test to verify it fails**

Run: `just test-one DeckConverterTest`
Expected: FAIL — `DeckConverter` doesn't exist

**Step 3: Write the implementation**

```kotlin
package leyline.bridge

import leyline.frontdoor.domain.DeckCard
import leyline.game.CardRepository
import org.slf4j.LoggerFactory

/**
 * Converts between Arena deck representations (grpId lists) and
 * Forge deck text format ("4 Lightning Bolt").
 */
object DeckConverter {
    private val log = LoggerFactory.getLogger(DeckConverter::class.java)

    /**
     * Convert Arena DeckCard lists to Forge-parseable deck text.
     * Unknown grpIds are skipped with a warning.
     */
    fun toDeckText(
        mainDeck: List<DeckCard>,
        sideboard: List<DeckCard>,
        cards: CardRepository,
    ): String = buildString {
        for (card in mainDeck) {
            val name = cards.findNameByGrpId(card.grpId)
            if (name != null) appendLine("${card.quantity} $name")
            else log.warn("DeckConverter: unknown grpId {}", card.grpId)
        }
        if (sideboard.isNotEmpty()) {
            appendLine("Sideboard")
            for (card in sideboard) {
                val name = cards.findNameByGrpId(card.grpId)
                if (name != null) appendLine("${card.quantity} $name")
                else log.warn("DeckConverter: unknown sideboard grpId {}", card.grpId)
            }
        }
    }

    /**
     * Convert Arena DeckCard lists to a Forge [forge.deck.Deck].
     * Requires card database to be initialized ([GameBootstrap.initializeCardDatabase]).
     */
    fun toForgeDeck(
        mainDeck: List<DeckCard>,
        sideboard: List<DeckCard>,
        cards: CardRepository,
    ): forge.deck.Deck {
        val text = toDeckText(mainDeck, sideboard, cards)
        return DeckLoader.parseDeckList(text)
    }
}
```

**Step 4: Run test to verify it passes**

Run: `just test-one DeckConverterTest`
Expected: PASS

**Step 5: Update MatchHandler to delegate**

In `MatchHandler.kt`, replace `convertArenaCardsToDeckText` body to use `DeckConverter`. The existing method parses JSON; refactor `resolveSeat1Deck`/`resolveSeat2Deck` to load the `Deck` domain object from the repo, then call `DeckConverter.toDeckText()`.

This is a refactor — existing tests must still pass.

Run: `just test-gate`

**Step 6: Commit**

```
feat: extract DeckConverter for shared grpId→Forge deck conversion
```

---

### Task 2: Add FormatService

Thin wrapper around Forge's `GameFormat` — identical pattern to forge-web's `FormatService`.

**Files:**
- Create: `src/main/kotlin/leyline/frontdoor/service/FormatService.kt`
- Test: `src/test/kotlin/leyline/frontdoor/service/FormatServiceTest.kt`

**Step 1: Write the failing test**

```kotlin
package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class FormatServiceTest : FunSpec({

    test("resolve returns null for blank format") {
        FormatService.resolve(null).shouldBeNull()
        FormatService.resolve("").shouldBeNull()
    }

    test("mapArenaFormat strips Traditional prefix") {
        FormatService.mapArenaFormat("TraditionalStandard") shouldBe "Standard"
        FormatService.mapArenaFormat("TraditionalExplorer") shouldBe "Explorer"
    }

    test("mapArenaFormat passes through base formats") {
        FormatService.mapArenaFormat("Standard") shouldBe "Standard"
        FormatService.mapArenaFormat("Explorer") shouldBe "Explorer"
        FormatService.mapArenaFormat("Historic") shouldBe "Historic"
    }

    // Integration tests (require card DB) tagged :integration
    // test("resolve finds Standard format").config(tags = setOf(Integration)) { ... }
    // test("validateDeck rejects banned card").config(tags = setOf(Integration)) { ... }
})
```

**Step 2: Run test to verify it fails**

Run: `just test-one FormatServiceTest`
Expected: FAIL — `FormatService` doesn't exist

**Step 3: Write the implementation**

```kotlin
package leyline.frontdoor.service

import forge.game.GameFormat
import forge.model.FModel
import org.slf4j.LoggerFactory

/**
 * Maps Arena event formats to Forge [GameFormat] and validates decks.
 * Requires [leyline.bridge.GameBootstrap.initializeCardDatabase] before [resolve]/[validateDeck].
 */
object FormatService {
    private val log = LoggerFactory.getLogger(FormatService::class.java)

    /**
     * Map Arena deckSelectFormat string to Forge format name.
     * "TraditionalStandard" → "Standard" (Traditional = Bo3, same card pool).
     */
    fun mapArenaFormat(arenaFormat: String): String =
        arenaFormat.removePrefix("Traditional")

    /**
     * Resolve a format name to the engine [GameFormat].
     * Throws if the format name is non-blank but not found — that's a bug in our mapping.
     */
    fun resolve(formatId: String?): GameFormat? {
        if (formatId.isNullOrBlank()) return null
        val collection = FModel.getFormats()
        return collection.get(formatId)
            ?: collection.get(formatId.replaceFirstChar { it.uppercase() })
            ?: error("Forge format '$formatId' not found — check EventRegistry/FormatService mapping")
    }

    /**
     * Validate a Forge [forge.deck.Deck] against a format.
     * Returns null if legal, error string if illegal.
     * Throws if the format name doesn't resolve (configuration bug).
     */
    fun validateDeck(deck: forge.deck.Deck, formatId: String?): String? {
        val format = resolve(formatId) ?: return null
        return format.getDeckConformanceProblem(deck)
    }
}
```

**Step 4: Run test to verify it passes**

Run: `just test-one FormatServiceTest`
Expected: PASS

**Step 5: Commit**

```
feat: add FormatService — maps Arena formats to Forge GameFormat
```

---

### Task 3: Wire validation into MatchmakingService

Add format validation to the match creation path. `MatchmakingService` looks up the event's format, converts the deck, validates, and rejects with an error if illegal.

**Files:**
- Modify: `src/main/kotlin/leyline/frontdoor/service/MatchmakingService.kt`
- Modify: `src/main/kotlin/leyline/frontdoor/service/EventRegistry.kt` — add helper
- Test: `src/test/kotlin/leyline/frontdoor/service/MatchmakingServiceTest.kt`

**Step 1: Write the failing test**

```kotlin
package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.throwables.shouldThrow
import leyline.frontdoor.domain.*
import leyline.frontdoor.repo.DeckRepository

class MatchmakingServiceTest : FunSpec({

    // Minimal stub repo for testing
    val stubRepo = object : DeckRepository {
        private val decks = mutableMapOf<String, Deck>()
        fun seed(deck: Deck) { decks[deck.id.value] = deck }
        override fun findById(id: DeckId) = decks[id.value]
        override fun findByName(name: String) = decks.values.firstOrNull { it.name == name }
        override fun findAllForPlayer(playerId: PlayerId) = decks.values.filter { it.playerId == playerId }
        override fun save(deck: Deck) { decks[deck.id.value] = deck }
        override fun delete(id: DeckId) { decks.remove(id.value) }
    }

    test("startMatch returns MatchInfo for valid event") {
        val deck = Deck(
            DeckId("d1"), PlayerId("p1"), "Test Deck", Format.Standard,
            0, listOf(DeckCard(75515, 60)), emptyList(), emptyList(), emptyList(),
        )
        stubRepo.seed(deck)
        val svc = MatchmakingService(stubRepo, "localhost", 30003)
        val match = svc.startMatch(PlayerId("p1"), DeckId("d1"), "Ladder")
        match shouldNotBe null
    }

    test("startMatch throws for unknown deck") {
        val svc = MatchmakingService(stubRepo, "localhost", 30003)
        shouldThrow<IllegalArgumentException> {
            svc.startMatch(PlayerId("p1"), DeckId("missing"), "Ladder")
        }
    }
})
```

**Step 2: Run test to verify it fails**

Run: `just test-one MatchmakingServiceTest`
Expected: FAIL — `startMatch` doesn't exist

**Step 3: Implement**

Add to `EventRegistry`:
```kotlin
/** Look up Forge format name for an Arena event. Null = no restriction. */
fun forgeFormatFor(eventName: String): String? {
    val event = findEvent(eventName) ?: return null
    if (event.flags.contains("SkipDeckValidation")) return null
    return FormatService.mapArenaFormat(event.deckSelectFormat)
}
```

Update `MatchmakingService`:
```kotlin
class MatchmakingService(
    private val decks: DeckRepository,
    private val matchDoorHost: String,
    private val matchDoorPort: Int,
    private val cards: CardRepository,
) {
    /**
     * Create a match for any event (ranked, unranked, bot).
     * Validates deck legality against the event's format.
     */
    fun startMatch(playerId: PlayerId, deckId: DeckId, eventName: String): MatchInfo {
        val deck = decks.findById(deckId)
            ?: throw IllegalArgumentException("Deck not found: ${deckId.value}")

        val forgeFormat = EventRegistry.forgeFormatFor(eventName)
        if (forgeFormat != null) {
            val forgeDeck = DeckConverter.toForgeDeck(deck.mainDeck, deck.sideboard, cards)
            val problem = FormatService.validateDeck(forgeDeck, forgeFormat)
            if (problem != null) {
                throw IllegalArgumentException("Deck '${deck.name}' not legal in $forgeFormat: $problem")
            }
        }

        return MatchInfo(
            matchId = UUID.randomUUID().toString(),
            host = matchDoorHost,
            port = matchDoorPort,
            eventName = eventName,
        )
    }

    /** Convenience: delegates to [startMatch]. Kept for backward compat. */
    fun startAiMatch(playerId: PlayerId, deckId: DeckId, eventName: String = "AIBotMatch"): MatchInfo =
        startMatch(playerId, deckId, eventName)
}
```

**Step 4: Run tests**

Run: `just test-gate`
Expected: PASS (existing tests still work, new tests pass)

**Step 5: Commit**

```
feat: validate deck format legality in MatchmakingService
```

---

### Task 4: Wire CardRepository into MatchmakingService

`MatchmakingService` needs a `CardRepository` to do grpId→name conversion. Thread it from `LeylineServer` / `LeylineMain` where CardRepository is already created.

**Files:**
- Modify: `src/main/kotlin/leyline/infra/LeylineServer.kt` (or wherever MatchmakingService is constructed)
- Modify: `src/main/kotlin/leyline/LeylineMain.kt` if needed

**Step 1: Find construction site**

```bash
grep -n "MatchmakingService(" src/main/kotlin/leyline/**/*.kt
```

**Step 2: Add `cards` parameter at construction**

Pass the existing `CardRepository` instance. This is pure wiring — no new logic.

**Step 3: Run tests**

Run: `just test-gate`

**Step 4: Commit**

```
feat: wire CardRepository into MatchmakingService for format validation
```

---

### Task 5: Wire 603 (Event_EnterPairing) to create matches

Now that validation works, make 603 actually start a match — same pattern as 612 (AIBotMatch).

**Files:**
- Modify: `src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt` — 603 handler

**Step 1: Update 603 handler**

```kotlin
603 -> { // Event_EnterPairing
    val eventName = extractEventName(json)
    val deckId = json?.let { DECK_ID_PATTERN.find(it)?.groupValues?.get(1) }
    log.info("Front Door: Event_EnterPairing event={} deck={}", eventName, deckId)

    if (eventName != null && deckId != null) {
        val pid = playerId ?: PlayerId("anonymous")
        try {
            onEventSelected?.invoke(eventName)
            if (deckId.isNotEmpty()) onDeckSelected?.invoke(deckId)
            val match = matchmaking.startMatch(pid, DeckId(deckId), eventName)
            writer.sendJson(ctx, txId, """{"CurrentModule":"CreateMatch","Payload":"Success"}""")
            sendMatchCreated(ctx, match)
        } catch (e: IllegalArgumentException) {
            log.warn("Front Door: Event_EnterPairing rejected — {}", e.message)
            writer.sendJson(ctx, txId, """{"CurrentModule":"Error","Payload":"${e.message}"}""")
        }
    } else {
        writer.sendJson(ctx, txId, """{"CurrentModule":"CreateMatch","Payload":"Success"}""")
        log.warn("Front Door: Event_EnterPairing missing eventName or deckId")
    }
}
```

**Step 2: Verify with `just serve`**

Start server, connect Arena client, pick Explorer queue, select a deck, click Play. Should see:
- `Event_EnterPairing event=Explorer_Ladder deck=<id>` in logs
- `MatchCreated` push after validation
- Client transitions to match

**Step 3: Commit**

```
feat: wire Event_EnterPairing (603) to create matches with format validation
```

---

### Task 6: Integration test — format rejection

End-to-end test that format validation actually blocks an illegal deck.

**Files:**
- Create: `src/test/kotlin/leyline/frontdoor/service/FormatValidationIntegrationTest.kt`

**Step 1: Write the test**

```kotlin
package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import leyline.bridge.DeckConverter
import leyline.bridge.DeckLoader
import leyline.bridge.GameBootstrap
import leyline.Tags.Integration

class FormatValidationIntegrationTest : FunSpec({

    beforeSpec {
        GameBootstrap.initializeCardDatabase(quiet = true)
    }

    test("Standard format rejects a Pioneer-only card").config(tags = setOf(Integration)) {
        // Nykthos is legal in Explorer/Pioneer but not Standard
        val deckText = "4 Nykthos, Shrine to Nyx\n56 Forest\n"
        val deck = DeckLoader.parseDeckList(deckText)
        val problem = FormatService.validateDeck(deck, "Standard")
        problem.shouldNotBeNull()
        problem shouldContain "Nykthos"
    }

    test("Explorer format accepts Explorer-legal card").config(tags = setOf(Integration)) {
        val deckText = "4 Nykthos, Shrine to Nyx\n56 Forest\n"
        val deck = DeckLoader.parseDeckList(deckText)
        val problem = FormatService.validateDeck(deck, "Explorer")
        // Explorer should allow Nykthos (if Forge's Explorer format includes Theros)
        // If this fails, check Forge's Explorer.txt set list
    }
})
```

**Step 2: Run**

Run: `just test-one FormatValidationIntegrationTest`

**Step 3: Commit**

```
test: integration test for format-based deck rejection
```

---

## Summary

| Task | What | Risk |
|------|------|------|
| 1 | Extract `DeckConverter` | Low — pure refactor |
| 2 | Add `FormatService` | Low — 30 lines, mirrors forge-web |
| 3 | Wire validation into `MatchmakingService` | Medium — new rejection path |
| 4 | Thread `CardRepository` to `MatchmakingService` | Low — wiring |
| 5 | Wire 603 to create matches | Medium — client-facing behavior change |
| 6 | Integration test | Low — validates the chain |

**Not in scope (future):**
- Companion/commander validation (different deck structure)
- FD error response that client renders as toast (need to capture what Arena expects)
- Deck validation at 622 (Event_SetDeckV2) — validate early, not just at pairing
- Format validation for precon decks (should always be legal)
