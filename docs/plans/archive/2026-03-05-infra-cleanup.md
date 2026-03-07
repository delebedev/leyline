# Infra & Singleton Cleanup — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Eliminate mutable singletons, add repository boundaries, thin handlers — align codebase with principles-design.md.

**Architecture:** Four phases, each a separate commit series. Phase 1 replaces `CardDb` object with `CardRepository` interface + Exposed impl. Phase 2 converts debug singletons to injected classes. Phase 3 extracts proxy handlers and thins MatchHandler. Phase 4 (future) splits large files.

**Tech Stack:** Kotlin, Exposed 1.1.1, Kotest FunSpec, Netty

---

## Phase 1: CardDb → CardRepository

CardDb is an `object` singleton with mutable state (cache, testMode, dbPath) and raw JDBC. It also builds proto `GameObjectInfo` — a wire concern in the data layer. Replace with:

- `CardRepository` interface in `game/`
- `ExposedCardRepository` impl using Exposed ORM (read-only against client's external SQLite)
- `InMemoryCardRepository` for tests (replaces testMode + clear() pattern)
- Extract `buildObjectInfo` to `CardProtoBuilder` in `game/` (wire-layer utility)
- Constructor-inject into all consumers

### Task 1.1: Create CardRepository interface

**Files:**
- Create: `src/main/kotlin/leyline/game/CardRepository.kt`

**Step 1: Write the interface**

```kotlin
package leyline.game

/**
 * Read-only card data lookup.
 *
 * Implementations: [ExposedCardRepository] (client SQLite),
 * [InMemoryCardRepository] (tests).
 */
interface CardRepository {
    /** Full card metadata by grpId. */
    fun findByGrpId(grpId: Int): CardData?

    /** Card name by grpId. */
    fun findNameByGrpId(grpId: Int): String?

    /** GrpId by exact card name. */
    fun findGrpIdByName(name: String): Int?

    /**
     * Token grpId produced by [sourceGrpId].
     * Single token → returns directly. Multiple → matches by [tokenName].
     */
    fun tokenGrpIdForCard(sourceGrpId: Int, tokenName: String? = null): Int?
}
```

`CardData` stays in its own file (extract from CardDb). Move the data class:

**Step 2: Extract CardData to its own file**

Create `src/main/kotlin/leyline/game/CardData.kt`:

```kotlin
package leyline.game

import wotc.mtgo.gre.external.messaging.Messages.ManaColor

data class CardData(
    val grpId: Int,
    val titleId: Int,
    val power: String,
    val toughness: String,
    val colors: List<Int>,
    val types: List<Int>,
    val subtypes: List<Int>,
    val supertypes: List<Int>,
    val abilityIds: List<Pair<Int, Int>>,
    val manaCost: List<Pair<ManaColor, Int>>,
    val tokenGrpIds: Map<Int, Int> = emptyMap(),
)
```

Note: `ManaColor` is a proto enum — CardData still references it. This is acceptable because ManaColor is a stable enum used as a domain concept, not a wire message shape. If purity is desired later, define a domain `ManaColor` enum and map at the boundary.

**Step 3: Commit**

```
feat(game): add CardRepository interface + extract CardData
```

---

### Task 1.2: Create ExposedCardRepository

**Files:**
- Create: `src/main/kotlin/leyline/game/ExposedCardRepository.kt`

Uses Exposed Table objects matching the client's external SQLite schema (read-only — no SchemaUtils.create).

**Step 1: Write the Exposed impl**

```kotlin
package leyline.game

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Read-only [CardRepository] backed by the client's local card SQLite database.
 *
 * Schema is external (Arena client) — we define [Table] objects matching it
 * but never call SchemaUtils.create. The DB file path is resolved at startup
 * via LEYLINE_CARD_DB env var or macOS auto-detection.
 */
class ExposedCardRepository(private val database: Database) : CardRepository {

    private val log = LoggerFactory.getLogger(ExposedCardRepository::class.java)

    // In-memory cache — CardData is immutable, DB is read-only
    private val cache = mutableMapOf<Int, CardData?>()
    private val nameCache = mutableMapOf<Int, String>()      // grpId → name
    private val reverseNameCache = mutableMapOf<String, Int>() // name → grpId

    // -- External schema tables (read-only) --

    private object Cards : Table("Cards") {
        val grpId = integer("GrpId")
        val titleId = integer("TitleId")
        val power = text("Power").default("")
        val toughness = text("Toughness").default("")
        val colors = text("Colors").default("")
        val types = text("Types").default("")
        val subtypes = text("Subtypes").default("")
        val supertypes = text("Supertypes").default("")
        val abilityIds = text("AbilityIds").default("")
        val oldSchoolManaText = text("OldSchoolManaText").default("")
        val abilityIdToLinkedTokenGrpId = text("AbilityIdToLinkedTokenGrpId").default("")
        val isToken = integer("IsToken").default(0)
        val isPrimaryCard = integer("IsPrimaryCard").default(1)
        val isDigitalOnly = integer("IsDigitalOnly").default(0)
        val isRebalanced = integer("IsRebalanced").default(0)
        override val primaryKey = PrimaryKey(grpId)
    }

    private object Localizations : Table("Localizations_enUS") {
        val locId = integer("LocId")
        val formatted = integer("Formatted")
        val loc = text("Loc")
    }

    override fun findByGrpId(grpId: Int): CardData? {
        cache[grpId]?.let { return it }
        val data = try {
            transaction(database) {
                Cards.selectAll().where { Cards.grpId eq grpId }
                    .firstOrNull()?.toCardData()
            }
        } catch (e: Exception) {
            log.warn("Card DB query failed for grpId={}: {}", grpId, e.message)
            null
        }
        cache[grpId] = data
        return data
    }

    override fun findNameByGrpId(grpId: Int): String? {
        nameCache[grpId]?.let { return it }
        val name = try {
            transaction(database) {
                (Cards innerJoin Localizations)
                    .selectAll().where {
                        (Cards.titleId eq Localizations.locId) and
                            (Localizations.formatted eq 1) and
                            (Cards.grpId eq grpId)
                    }
                    .firstOrNull()
                    ?.get(Localizations.loc)
            }
        } catch (e: Exception) {
            log.warn("Card DB name query failed for grpId={}: {}", grpId, e.message)
            null
        }
        if (name != null) {
            nameCache[grpId] = name
            reverseNameCache[name] = grpId
        }
        return name
    }

    override fun findGrpIdByName(name: String): Int? {
        reverseNameCache[name]?.let { return it }
        val grpId = try {
            transaction(database) {
                (Cards innerJoin Localizations)
                    .selectAll().where {
                        (Cards.titleId eq Localizations.locId) and
                            (Localizations.formatted eq 1) and
                            (Localizations.loc eq name) and
                            (Cards.isToken eq 0) and
                            (Cards.isPrimaryCard eq 1)
                    }
                    .orderBy(
                        Cards.isDigitalOnly to SortOrder.ASC,
                        Cards.isRebalanced to SortOrder.ASC,
                        Cards.grpId to SortOrder.DESC,
                    )
                    .firstOrNull()
                    ?.get(Cards.grpId)
            }
        } catch (e: Exception) {
            log.warn("Card DB query failed for name='{}': {}", name, e.message)
            null
        }
        if (grpId != null) {
            reverseNameCache[name] = grpId
            nameCache[grpId] = name
        }
        return grpId
    }

    override fun tokenGrpIdForCard(sourceGrpId: Int, tokenName: String?): Int? {
        val data = findByGrpId(sourceGrpId) ?: return null
        val tokens = data.tokenGrpIds
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) return tokens.values.first()
        if (tokenName == null) return null
        for ((_, tokenGrpId) in tokens) {
            val name = findNameByGrpId(tokenGrpId)
            if (name == tokenName) return tokenGrpId
        }
        return null
    }

    private fun ResultRow.toCardData() = CardData(
        grpId = this[Cards.grpId],
        titleId = this[Cards.titleId],
        power = this[Cards.power],
        toughness = this[Cards.toughness],
        colors = parseIntList(this[Cards.colors]),
        types = parseIntList(this[Cards.types]),
        subtypes = parseIntList(this[Cards.subtypes]),
        supertypes = parseIntList(this[Cards.supertypes]),
        abilityIds = parseAbilityIds(this[Cards.abilityIds]),
        manaCost = parseManaCost(this[Cards.oldSchoolManaText]),
        tokenGrpIds = parseTokenGrpIds(this[Cards.abilityIdToLinkedTokenGrpId]),
    )
}
```

Parsing helpers (`parseIntList`, `parseAbilityIds`, `parseManaCost`, `parseTokenGrpIds`) — extract from CardDb to a top-level `CardDataParsing.kt` or keep as private functions in ExposedCardRepository. Prefer package-level `internal` functions shared between impl and tests:

Create `src/main/kotlin/leyline/game/CardDataParsing.kt`:

```kotlin
package leyline.game

import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/** Parse "5" or "27,23" → list of ints. */
internal fun parseIntList(s: String?): List<Int> {
    if (s.isNullOrBlank()) return emptyList()
    return s.split(",").mapNotNull { it.trim().toIntOrNull() }
}

/** Parse "1005:227393 2010:300000" → list of (abilityGrpId, textId). */
internal fun parseAbilityIds(s: String?): List<Pair<Int, Int>> {
    if (s.isNullOrBlank()) return emptyList()
    return s.trim().split(" ", ",").mapNotNull { entry ->
        val parts = entry.split(":")
        if (parts.size == 2) {
            val base = parts[0].toIntOrNull() ?: return@mapNotNull null
            val id = parts[1].toIntOrNull() ?: return@mapNotNull null
            base to id
        } else null
    }
}

/** Parse "99866:94161,175756:94156" → map. */
internal fun parseTokenGrpIds(s: String?): Map<Int, Int> {
    if (s.isNullOrBlank()) return emptyMap()
    val result = mutableMapOf<Int, Int>()
    for (entry in s.split(",")) {
        val parts = entry.trim().split(":")
        if (parts.size == 2) {
            val k = parts[0].toIntOrNull() ?: continue
            val v = parts[1].toIntOrNull() ?: continue
            result[k] = v
        }
    }
    return result
}

/** Parse OldSchoolManaText: "oG" = {G}, "o3oGoG" = {3}{G}{G}. */
internal fun parseManaCost(s: String?): List<Pair<ManaColor, Int>> {
    if (s.isNullOrBlank()) return emptyList()
    val counts = mutableMapOf<ManaColor, Int>()
    for (part in s.split("o").filter { it.isNotEmpty() }) {
        when (part.uppercase()) {
            "W" -> counts.merge(ManaColor.White_afc9, 1, Int::plus)
            "U" -> counts.merge(ManaColor.Blue_afc9, 1, Int::plus)
            "B" -> counts.merge(ManaColor.Black_afc9, 1, Int::plus)
            "R" -> counts.merge(ManaColor.Red_afc9, 1, Int::plus)
            "G" -> counts.merge(ManaColor.Green_afc9, 1, Int::plus)
            "X" -> counts.merge(ManaColor.X, 1, Int::plus)
            "C" -> counts.merge(ManaColor.Colorless_afc9, 1, Int::plus)
            "S" -> counts.merge(ManaColor.Snow_afc9, 1, Int::plus)
            else -> {
                val n = part.toIntOrNull()
                if (n != null && n > 0) counts.merge(ManaColor.Generic, n, Int::plus)
            }
        }
    }
    return counts.toList()
}
```

**Step 2: Commit**

```
feat(game): add ExposedCardRepository + CardDataParsing
```

---

### Task 1.3: Create InMemoryCardRepository

**Files:**
- Create: `src/main/kotlin/leyline/game/InMemoryCardRepository.kt`

Replaces the `testMode` + `register()` + `clear()` pattern. Tests instantiate this directly.

```kotlin
package leyline.game

/**
 * In-memory [CardRepository] for tests. Pre-populate with [register] / [registerData].
 */
class InMemoryCardRepository : CardRepository {

    private val cards = mutableMapOf<Int, CardData>()
    private val names = mutableMapOf<Int, String>()
    private val nameToGrpId = mutableMapOf<String, Int>()

    val registeredCount: Int get() = names.size

    fun register(grpId: Int, name: String) {
        names[grpId] = name
        nameToGrpId[name] = grpId
    }

    fun registerData(data: CardData, name: String) {
        register(data.grpId, name)
        cards[data.grpId] = data
    }

    fun clear() {
        cards.clear()
        names.clear()
        nameToGrpId.clear()
    }

    override fun findByGrpId(grpId: Int): CardData? = cards[grpId]

    override fun findNameByGrpId(grpId: Int): String? = names[grpId]

    override fun findGrpIdByName(name: String): Int? = nameToGrpId[name]

    override fun tokenGrpIdForCard(sourceGrpId: Int, tokenName: String?): Int? {
        val data = findByGrpId(sourceGrpId) ?: return null
        val tokens = data.tokenGrpIds
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) return tokens.values.first()
        if (tokenName == null) return null
        for ((_, tokenGrpId) in tokens) {
            if (findNameByGrpId(tokenGrpId) == tokenName) return tokenGrpId
        }
        return null
    }
}
```

**Step 2: Commit**

```
feat(game): add InMemoryCardRepository
```

---

### Task 1.4: Extract CardProtoBuilder

`buildObjectInfo()` builds proto `GameObjectInfo` — wire concern. Extract from CardDb to its own utility.

**Files:**
- Create: `src/main/kotlin/leyline/game/CardProtoBuilder.kt`

```kotlin
package leyline.game

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Builds [GameObjectInfo] protos from [CardRepository] data.
 * Wire-layer utility — keeps proto construction out of the repository.
 */
class CardProtoBuilder(private val cards: CardRepository) {

    /** Build from DB data, no template — for buildFromGame path. */
    fun buildObjectInfo(grpId: Int): GameObjectInfo.Builder {
        val builder = GameObjectInfo.newBuilder()
            .setGrpId(grpId)
            .setOverlayGrpId(grpId)
        val card = cards.findByGrpId(grpId) ?: return builder
        applyCardData(builder, card)
        return builder
    }

    /** Build from DB data, preserving template structure fields. */
    fun buildObjectInfo(grpId: Int, template: GameObjectInfo): GameObjectInfo {
        val card = cards.findByGrpId(grpId)
            ?: return template.toBuilder().setGrpId(grpId).setOverlayGrpId(grpId).build()
        val builder = template.toBuilder()
            .setGrpId(grpId)
            .setOverlayGrpId(grpId)
            .setName(card.titleId)
        applyCardData(builder, card, startAbilitySeqId = template.uniqueAbilitiesList.firstOrNull()?.id ?: 50)
        return builder.build()
    }

    private fun applyCardData(builder: GameObjectInfo.Builder, card: CardData, startAbilitySeqId: Int = 50) {
        builder.setName(card.titleId)
        builder.clearCardTypes()
        card.types.forEach { builder.addCardTypes(CardType.forNumber(it) ?: return@forEach) }
        builder.clearSubtypes()
        card.subtypes.forEach { builder.addSubtypes(SubType.forNumber(it) ?: return@forEach) }
        builder.clearSuperTypes()
        card.supertypes.forEach { builder.addSuperTypes(SuperType.forNumber(it) ?: return@forEach) }
        builder.clearColor()
        card.colors.forEach { builder.addColor(CardColor.forNumber(it) ?: return@forEach) }

        if (card.power.isNotEmpty()) {
            builder.setPower(Int32Value.newBuilder().setValue(card.power.toIntOrNull() ?: 0))
        } else {
            builder.clearPower()
        }
        if (card.toughness.isNotEmpty()) {
            builder.setToughness(Int32Value.newBuilder().setValue(card.toughness.toIntOrNull() ?: 0))
        } else {
            builder.clearToughness()
        }

        builder.clearUniqueAbilities()
        var seqId = startAbilitySeqId
        val abilities = card.abilityIds.ifEmpty {
            basicLandAbility(card.subtypes)?.let { listOf(it to 0) } ?: emptyList()
        }
        abilities.forEach { (abilityGrpId, _) ->
            builder.addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(seqId++).setGrpId(abilityGrpId))
        }
    }
}

/**
 * Basic land mana ability grpIds — implicit in client, not in DB.
 * SubType enum: Plains=54, Island=43, Swamp=69, Mountain=49, Forest=29.
 */
private val BASIC_LAND_ABILITIES = mapOf(
    54 to 1001, 43 to 1002, 69 to 1003, 49 to 1004, 29 to 1005,
)

private fun basicLandAbility(subtypes: List<Int>): Int? =
    subtypes.firstNotNullOfOrNull { BASIC_LAND_ABILITIES[it] }
```

**Step 2: Commit**

```
refactor(game): extract CardProtoBuilder from CardDb
```

---

### Task 1.5: Wire CardRepository into consumers + delete CardDb

This is the migration task. Update all call sites:

**Callers to update:**

| File | Current | New |
|------|---------|-----|
| `LeylineMain.kt:37-43` | `CardDb.init(File)` | Create `ExposedCardRepository(Database.connect(...))` |
| `LeylineServer.kt` | N/A | Accept `CardRepository` in constructor, pass to MatchHandler |
| `MatchHandler.kt` | `CardDb.lookupNameByGrpId()` | Accept `CardRepository` in constructor |
| `MatchSession.kt` | `CardDb` import | Receive `CardRepository` via MatchHandler |
| `GameBridge.kt` | `CardDb` usage | Accept `CardRepository` + `CardProtoBuilder` in constructor |
| `GameStateCollector.kt` | `CardDb.getCardName()` | Accept `CardRepository` |
| `DebugCollector.kt` | `CardDb.lookupByName()` | Accept `CardRepository` |
| `StateMapper.kt` | `CardDb.buildObjectInfo()` | Use `CardProtoBuilder` |
| `GsmBuilder.kt` | `CardDb.buildObjectInfo()` | Use `CardProtoBuilder` |
| `ObjectMapper.kt` | `CardDb` | Use `CardRepository` / `CardProtoBuilder` |
| `ZoneMapper.kt` | `CardDb` | Use `CardRepository` / `CardProtoBuilder` |
| `DeckProvider.kt` | `CardDb` | Use `CardRepository` |
| `PuzzleCardRegistrar.kt` | `CardDb.register/registerData` | Use `InMemoryCardRepository` or accept `CardRepository` |
| CLI tools (`SeedDb`, `DeckCatalog`) | `CardDb` | Create their own `ExposedCardRepository` |

**Test callers to update:** All tests using `CardDb.testMode = true` / `CardDb.register()` / `CardDb.clear()` → use `InMemoryCardRepository()` instead. Key files:
- `CardDbTest.kt` → `CardRepositoryTest.kt`
- `ConformanceTestBase.kt` → inject `InMemoryCardRepository`
- `ProtocolTest.kt` → inject `InMemoryCardRepository`
- All files using `TestCardRegistry` → update to use `InMemoryCardRepository`

**Step 1:** Update LeylineMain + LeylineServer constructor to create and pass `CardRepository`
**Step 2:** Update MatchHandler + MatchSession to accept `CardRepository`
**Step 3:** Update GameBridge to accept `CardRepository` + `CardProtoBuilder`
**Step 4:** Update StateMapper, GsmBuilder, ObjectMapper, ZoneMapper to use `CardProtoBuilder`
**Step 5:** Update debug collectors to accept `CardRepository`
**Step 6:** Update all tests
**Step 7:** Delete `CardDb.kt`
**Step 8:** Run `just test-gate`
**Step 9:** Commit

```
refactor(game): wire CardRepository into all consumers, delete CardDb singleton
```

---

## Phase 2: Debug Singletons → Injected Classes

Five singletons to convert: DebugCollector, FdDebugCollector, GameStateCollector, DebugEventBus, CaptureSink.

### Task 2.1: Convert DebugCollector to class

**Files:**
- Modify: `src/main/kotlin/leyline/debug/DebugCollector.kt`

**Current problems:**
- `object DebugCollector` with mutable `buffer`, `seq`
- `var bridgeProvider: (() -> Map<String, GameBridge>)?` — circular dep hack
- `var sessionProvider: (() -> MatchSession?)?` — same

**Changes:**
- `object` → `class DebugCollector(private val cardRepository: CardRepository)`
- Remove `var bridgeProvider` / `var sessionProvider` — inject via methods or constructor
- Create instance in LeylineServer, pass to MatchHandler and DebugServer
- DebugEventBus: convert to class, create instance alongside DebugCollector

### Task 2.2: Convert FdDebugCollector to class

**Files:**
- Modify: `src/main/kotlin/leyline/debug/FdDebugCollector.kt`

**Changes:**
- `object` → `class FdDebugCollector(private val eventBus: DebugEventBus)`
- Create instance in LeylineServer, pass to FrontDoorHandler / FdResponseWriter / CaptureSink

### Task 2.3: Convert GameStateCollector to class

**Files:**
- Modify: `src/main/kotlin/leyline/debug/GameStateCollector.kt`

**Changes:**
- `object` → `class GameStateCollector(private val cardRepository: CardRepository)`
- Remove unguarded mutable state — use synchronized or concurrent collections
- Create instance in LeylineServer, pass to MatchSession and DebugServer

### Task 2.4: Convert CaptureSink to class

**Files:**
- Modify: `src/main/kotlin/leyline/infra/CaptureSink.kt`

**Changes:**
- `internal object` → `class CaptureSink(private val fdCollector: FdDebugCollector)`
- Remove `init {}` shutdown hook — manage lifecycle in LeylineServer.stop()
- Create instance in LeylineServer, pass to proxy handlers

### Task 2.5: Wire all debug classes in LeylineServer

**Files:**
- Modify: `src/main/kotlin/leyline/infra/LeylineServer.kt`
- Modify: `src/main/kotlin/leyline/LeylineMain.kt`
- Modify: `src/main/kotlin/leyline/debug/DebugServer.kt`

**Wiring order in LeylineServer.startStub():**
```
val eventBus = DebugEventBus()
val fdCollector = FdDebugCollector(eventBus)
val captureSink = CaptureSink(fdCollector)
val debugCollector = DebugCollector(cardRepository)
val gameStateCollector = GameStateCollector(cardRepository)
// pass to handlers...
```

**Step: Run `just test-gate`, commit**

```
refactor(debug): convert debug singletons to injected classes
```

---

## Phase 3: Handler Thinning + Proxy Extraction

### Task 3.1: Extract proxy handlers from LeylineServer.kt

**Files:**
- Create: `src/main/kotlin/leyline/infra/ProxyHandlers.kt`
- Modify: `src/main/kotlin/leyline/infra/LeylineServer.kt`

Move from LeylineServer.kt (lines 276-397):
- `ProxyFrontHandler` class
- `RelayHandler` class
- `logClientFrame()` private function
- `frameTypeName()` function (if present)

LeylineServer drops from ~400 to ~275 lines. No behavior change — pure file extraction.

```
refactor(infra): extract ProxyHandlers from LeylineServer
```

### Task 3.2: Extract mulligan logic from MatchHandler

**Files:**
- Create: `src/main/kotlin/leyline/match/MulliganHandler.kt`
- Modify: `src/main/kotlin/leyline/match/MatchHandler.kt`

Extract from MatchHandler:
- Mulligan state: `mulliganCount`, `seat1Hand`, `seat2Hand`
- `sendDealHandAndMulligan()` method
- `onMulliganKeep()` method
- Mulligan response handler (MulliganResp dispatch, lines 214-242)

MulliganHandler takes `MatchSession` + `MessageSink` references. MatchHandler delegates to it.

```
refactor(match): extract MulliganHandler from MatchHandler
```

### Task 3.3: Extract puzzle flow from MatchHandler

**Files:**
- Create: `src/main/kotlin/leyline/match/PuzzleHandler.kt`
- Modify: `src/main/kotlin/leyline/match/MatchHandler.kt`

Extract:
- `isPuzzleMatch()` method
- `loadPuzzleForMatch()` method
- `sendPuzzleInitialBundle()` method
- Puzzle mode branch from ConnectReq handler

```
refactor(match): extract PuzzleHandler from MatchHandler
```

---

## Phase 4: Large File Splits (Future — Plan Only)

Not executed now. Captured for future reference.

### Task 4.1: Split WebPlayerController (1278 LOC)
Extract sub-handlers: ScryHandler, SurveilHandler, ChoiceHandler, TargetingHandler, CombatDecisionHandler.

### Task 4.2: Split WebCostDecision (1332 LOC)
Extract cost visitor groups: ManaPaymentVisitor, SacrificeVisitor, DiscardVisitor, TapVisitor.

### Task 4.3: Split BundleBuilder (866 LOC)
Separate: PhaseTransitionBundler, StateSnapshotBundler, ActionRequestBundler.

### Task 4.4: Split AnnotationBuilder (687 LOC)
Separate: AnnotationCategoryResolver, ProtoAnnotationFactory.

### Task 4.5: Split RecordingDecoder (775 LOC)
Separate: FdFrameParser, MdMessageDecoder, FrameToJson.
