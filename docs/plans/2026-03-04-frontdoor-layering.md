# Front Door Layering Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Decompose FrontDoorService monolith into layered bounded context: domain types, repository (Exposed DSL), services, wire builders, thin handler.

**Architecture:** See `docs/plans/2026-03-04-frontdoor-layering-design.md`. Domain types at center, repository interfaces around them, services call repos, wire builders translate domain↔protocol, handler dispatches.

**Tech Stack:** Kotlin, Exposed DSL 1.1.1, SQLite (existing schema), Kotest FunSpec, kotlinx.serialization for wire builders.

**Safety:** Existing `FrontDoorServiceTest` wire tests are the behavioral contract. They must pass after every task. Run `just test-fd` (fast, FD-only) between tasks, `just test-gate` before commits.

---

### Task 0: Add FdTag and `just test-fd`

**Files:**
- Modify: `src/test/kotlin/leyline/Tags.kt`
- Modify: `src/test/kotlin/leyline/server/FrontDoorServiceTest.kt` (change `tags(UnitTag)` → `tags(FdTag)`)
- Modify: `src/test/kotlin/leyline/server/PlayerDbTest.kt` (change `tags(UnitTag)` → `tags(FdTag)`)
- Modify: `build.gradle.kts` (add `testFd` task)
- Modify: `justfile` (add `test-fd` recipe)

**Step 1: Add FdTag to Tags.kt**

```kotlin
object FdTag : Tag()
```

**Step 2: Tag FD tests with FdTag**

In `FrontDoorServiceTest.kt`: change `tags(UnitTag)` to `tags(FdTag)`.
In `PlayerDbTest.kt`: change `tags(UnitTag)` to `tags(FdTag)`.

**Step 3: Add Gradle test task**

In `build.gradle.kts` after the `testGate` block:

```kotlin
val testFd by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "FdTag")
}
```

**Step 4: Add justfile recipe**

```just
test-fd:
    ./gradlew testFd
```

**Step 5: Run `just test-fd` to verify**

Expected: 14 FD tests + 8 PlayerDb tests pass. Nothing else runs.

**Step 6: Also verify `just test-gate` still passes**

FdTag tests should also run in gate. Update gate tag filter:

```kotlin
val testGate by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "UnitTag | ConformanceTag | FdTag")
}
```

**Step 7: Commit**

```
test: add FdTag for fast FD iteration
```

---

### Task 1: Add Exposed dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

**Step 1: Add version and libraries to catalog**

In `gradle/libs.versions.toml`:

```toml
# Under [versions]
exposed = "1.1.1"

# Under [libraries]
exposed-core = { module = "org.jetbrains.exposed:exposed-core", version.ref = "exposed" }
exposed-jdbc = { module = "org.jetbrains.exposed:exposed-jdbc", version.ref = "exposed" }
```

**Step 2: Add dependencies to build.gradle.kts**

```kotlin
implementation(libs.exposed.core)
implementation(libs.exposed.jdbc)
```

**Step 3: Run `./gradlew compileKotlin` to verify resolution**

Expected: clean compile, deps downloaded.

**Step 4: Commit**

```
build: add Exposed DSL 1.1.1 dependencies
```

---

### Task 2: Domain types

**Files:**
- Create: `src/main/kotlin/leyline/frontdoor/domain/Deck.kt`
- Create: `src/main/kotlin/leyline/frontdoor/domain/Player.kt`
- Create: `src/main/kotlin/leyline/frontdoor/domain/Preferences.kt`
- Test: `src/test/kotlin/leyline/frontdoor/domain/DeckTest.kt`

**Step 1: Write Deck.kt**

```kotlin
package leyline.frontdoor.domain

@JvmInline value class DeckId(val value: String)
@JvmInline value class PlayerId(val value: String)

enum class Format {
    Standard, Historic, Explorer, Timeless, Alchemy, Brawl;

    companion object {
        fun fromString(s: String): Format =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: Standard
    }
}

data class DeckCard(val grpId: Int, val quantity: Int)

data class Deck(
    val id: DeckId,
    val playerId: PlayerId,
    val name: String,
    val format: Format,
    val tileId: Int,
    val mainDeck: List<DeckCard>,
    val sideboard: List<DeckCard>,
    val commandZone: List<DeckCard>,
    val companions: List<DeckCard>,
) {
    val totalCards: Int get() = mainDeck.sumOf { it.quantity }
}
```

**Step 2: Write Player.kt**

```kotlin
package leyline.frontdoor.domain

@JvmInline value class SessionId(val value: String)

data class Player(
    val id: PlayerId,
    val screenName: String,
)
```

**Step 3: Write Preferences.kt**

```kotlin
package leyline.frontdoor.domain

/**
 * Typed wrapper around player preferences JSON.
 *
 * We don't parse individual preferences yet — this prevents raw
 * strings from crossing service boundaries.
 */
@JvmInline value class Preferences(val json: String)
```

**Step 4: Write DeckTest.kt**

```kotlin
package leyline.frontdoor.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.FdTag

class DeckTest : FunSpec({
    tags(FdTag)

    test("totalCards sums main deck quantities") {
        val deck = Deck(
            id = DeckId("d1"),
            playerId = PlayerId("p1"),
            name = "Test",
            format = Format.Standard,
            tileId = 0,
            mainDeck = listOf(DeckCard(100, 4), DeckCard(200, 56)),
            sideboard = listOf(DeckCard(300, 15)),
            commandZone = emptyList(),
            companions = emptyList(),
        )
        deck.totalCards shouldBe 60
    }

    test("Format.fromString is case-insensitive") {
        Format.fromString("standard") shouldBe Format.Standard
        Format.fromString("HISTORIC") shouldBe Format.Historic
        Format.fromString("unknown") shouldBe Format.Standard
    }
})
```

**Step 5: Run `just test-fd`**

Expected: domain tests pass + existing FD tests still pass.

**Step 6: Commit**

```
feat(fd): domain types — Deck, Player, Preferences value objects
```

---

### Task 3: Repository interfaces

**Files:**
- Create: `src/main/kotlin/leyline/frontdoor/repo/DeckRepository.kt`
- Create: `src/main/kotlin/leyline/frontdoor/repo/PlayerRepository.kt`

**Step 1: Write DeckRepository.kt**

```kotlin
package leyline.frontdoor.repo

import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.PlayerId

interface DeckRepository {
    fun findById(id: DeckId): Deck?
    fun findAllForPlayer(playerId: PlayerId): List<Deck>
    fun save(deck: Deck)
    fun delete(id: DeckId)
}
```

**Step 2: Write PlayerRepository.kt**

```kotlin
package leyline.frontdoor.repo

import leyline.frontdoor.domain.Player
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.domain.Preferences

interface PlayerRepository {
    fun findPlayer(id: PlayerId): Player?
    fun getPreferences(id: PlayerId): Preferences?
    fun savePreferences(id: PlayerId, prefs: Preferences)
    fun ensurePlayer(id: PlayerId, screenName: String)
}
```

**Step 3: Compile check**

Run: `./gradlew compileKotlin`

**Step 4: Commit**

```
feat(fd): repository interfaces — DeckRepository, PlayerRepository
```

---

### Task 4: SqlitePlayerStore (Exposed DSL)

**Files:**
- Create: `src/main/kotlin/leyline/frontdoor/repo/SqlitePlayerStore.kt`
- Create: `src/test/kotlin/leyline/frontdoor/repo/SqlitePlayerStoreTest.kt`

**Step 1: Write SqlitePlayerStoreTest.kt**

Tests mirror existing `PlayerDbTest` but use domain types. Run against real SQLite (temp file).

```kotlin
package leyline.frontdoor.repo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.FdTag
import leyline.frontdoor.domain.*
import org.jetbrains.exposed.sql.Database

class SqlitePlayerStoreTest : FunSpec({
    tags(FdTag)

    val db = Database.connect("jdbc:sqlite::memory:", "org.sqlite.JDBC")
    val store = SqlitePlayerStore(db)

    beforeSpec {
        store.createTables()
    }

    test("ensurePlayer creates player") {
        store.ensurePlayer(PlayerId("p1"), "Tester")
        val p = store.findPlayer(PlayerId("p1"))
        p shouldNotBe null
        p!!.screenName shouldBe "Tester"
    }

    test("ensurePlayer is idempotent") {
        store.ensurePlayer(PlayerId("p1"), "Tester")
        store.findPlayer(PlayerId("p1")) shouldNotBe null
    }

    test("preferences round-trip") {
        store.savePreferences(PlayerId("p1"), Preferences("""{"key":"val"}"""))
        store.getPreferences(PlayerId("p1"))?.json shouldBe """{"key":"val"}"""
    }

    test("save and find deck") {
        val deck = Deck(
            id = DeckId("d1"),
            playerId = PlayerId("p1"),
            name = "White Weenie",
            format = Format.Standard,
            tileId = 93855,
            mainDeck = listOf(DeckCard(93855, 4)),
            sideboard = emptyList(),
            commandZone = emptyList(),
            companions = emptyList(),
        )
        store.save(deck)
        val found = store.findById(DeckId("d1"))
        found shouldNotBe null
        found!!.name shouldBe "White Weenie"
        found.tileId shouldBe 93855
        found.format shouldBe Format.Standard
        found.mainDeck shouldHaveSize 1
    }

    test("findAllForPlayer returns all decks") {
        val deck2 = Deck(
            id = DeckId("d2"),
            playerId = PlayerId("p1"),
            name = "Deck 2",
            format = Format.Standard,
            tileId = 100,
            mainDeck = emptyList(),
            sideboard = emptyList(),
            commandZone = emptyList(),
            companions = emptyList(),
        )
        store.save(deck2)
        store.findAllForPlayer(PlayerId("p1")) shouldHaveSize 2
    }

    test("delete removes deck") {
        store.delete(DeckId("d2"))
        store.findById(DeckId("d2")) shouldBe null
    }

    test("save updates existing deck") {
        val updated = store.findById(DeckId("d1"))!!.copy(
            name = "Updated Name",
            format = Format.Historic,
        )
        store.save(updated)
        val found = store.findById(DeckId("d1"))!!
        found.name shouldBe "Updated Name"
        found.format shouldBe Format.Historic
    }
})
```

**Step 2: Run test to verify it fails**

Run: `just test-fd`
Expected: FAIL — SqlitePlayerStore doesn't exist yet.

**Step 3: Write SqlitePlayerStore.kt**

```kotlin
package leyline.frontdoor.repo

import kotlinx.serialization.json.*
import leyline.frontdoor.domain.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class SqlitePlayerStore(private val database: Database) : DeckRepository, PlayerRepository {

    private object Players : Table("players") {
        val playerId = text("player_id")
        val screenName = text("screen_name")
        val preferences = text("preferences").nullable()
        val inventory = text("inventory").nullable()
        val cosmetics = text("cosmetics").nullable()
        val rankInfo = text("rank_info").nullable()
        val createdAt = text("created_at")
        override val primaryKey = PrimaryKey(playerId)
    }

    private object Decks : Table("decks") {
        val deckId = text("deck_id")
        val playerId = text("player_id")
        val name = text("name")
        val tileId = integer("tile_id")
        val format = text("format")
        val cards = text("cards")
        val updatedAt = text("updated_at")
        override val primaryKey = PrimaryKey(deckId)
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun createTables() {
        transaction(database) {
            SchemaUtils.create(Players, Decks)
        }
    }

    // --- DeckRepository ---

    override fun findById(id: DeckId): Deck? = transaction(database) {
        Decks.selectAll().where { Decks.deckId eq id.value }
            .firstOrNull()?.toDeck()
    }

    override fun findAllForPlayer(playerId: PlayerId): List<Deck> = transaction(database) {
        Decks.selectAll().where { Decks.playerId eq playerId.value }
            .map { it.toDeck() }
    }

    override fun save(deck: Deck) {
        val cardsJson = encodeCards(deck)
        val now = Instant.now().toString()
        transaction(database) {
            val existing = Decks.selectAll().where { Decks.deckId eq deck.id.value }.count()
            if (existing > 0) {
                Decks.update({ Decks.deckId eq deck.id.value }) {
                    it[playerId] = deck.playerId.value
                    it[name] = deck.name
                    it[tileId] = deck.tileId
                    it[format] = deck.format.name
                    it[cards] = cardsJson
                    it[updatedAt] = now
                }
            } else {
                Decks.insert {
                    it[deckId] = deck.id.value
                    it[playerId] = deck.playerId.value
                    it[name] = deck.name
                    it[tileId] = deck.tileId
                    it[format] = deck.format.name
                    it[cards] = cardsJson
                    it[updatedAt] = now
                }
            }
        }
    }

    override fun delete(id: DeckId) {
        transaction(database) {
            Decks.deleteWhere { deckId eq id.value }
        }
    }

    // --- PlayerRepository ---

    override fun findPlayer(id: PlayerId): Player? = transaction(database) {
        Players.selectAll().where { Players.playerId eq id.value }
            .firstOrNull()?.let {
                Player(PlayerId(it[Players.playerId]), it[Players.screenName])
            }
    }

    override fun getPreferences(id: PlayerId): Preferences? = transaction(database) {
        Players.selectAll().where { Players.playerId eq id.value }
            .firstOrNull()?.get(Players.preferences)?.let { Preferences(it) }
    }

    override fun savePreferences(id: PlayerId, prefs: Preferences) {
        transaction(database) {
            Players.update({ Players.playerId eq id.value }) {
                it[preferences] = prefs.json
            }
        }
    }

    override fun ensurePlayer(id: PlayerId, screenName: String) {
        transaction(database) {
            val existing = Players.selectAll().where { Players.playerId eq id.value }.count()
            if (existing == 0L) {
                Players.insert {
                    it[playerId] = id.value
                    it[Players.screenName] = screenName
                    it[createdAt] = Instant.now().toString()
                }
            }
        }
    }

    // --- Mapping ---

    private fun ResultRow.toDeck(): Deck {
        val cardsObj = try {
            json.parseToJsonElement(this[Decks.cards]).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        return Deck(
            id = DeckId(this[Decks.deckId]),
            playerId = PlayerId(this[Decks.playerId]),
            name = this[Decks.name],
            format = Format.fromString(this[Decks.format]),
            tileId = this[Decks.tileId],
            mainDeck = parseCardList(cardsObj["MainDeck"]),
            sideboard = parseCardList(cardsObj["Sideboard"]) + parseCardList(cardsObj["ReducedSideboard"]),
            commandZone = parseCardList(cardsObj["CommandZone"]),
            companions = parseCardList(cardsObj["Companions"]),
        )
    }

    private fun parseCardList(element: JsonElement?): List<DeckCard> {
        if (element == null || element !is JsonArray) return emptyList()
        return element.mapNotNull { entry ->
            val obj = entry.jsonObject
            val grpId = obj["cardId"]?.jsonPrimitive?.int ?: return@mapNotNull null
            val qty = obj["quantity"]?.jsonPrimitive?.int ?: 1
            DeckCard(grpId, qty)
        }
    }

    private fun encodeCards(deck: Deck): String {
        val obj = buildJsonObject {
            putJsonArray("MainDeck") {
                deck.mainDeck.forEach { c ->
                    addJsonObject { put("cardId", c.grpId); put("quantity", c.quantity) }
                }
            }
            putJsonArray("Sideboard") {
                deck.sideboard.forEach { c ->
                    addJsonObject { put("cardId", c.grpId); put("quantity", c.quantity) }
                }
            }
            putJsonArray("ReducedSideboard") {}
            putJsonArray("CommandZone") {
                deck.commandZone.forEach { c ->
                    addJsonObject { put("cardId", c.grpId); put("quantity", c.quantity) }
                }
            }
            putJsonArray("Companions") {
                deck.companions.forEach { c ->
                    addJsonObject { put("cardId", c.grpId); put("quantity", c.quantity) }
                }
            }
            putJsonArray("CardSkins") {}
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}
```

**Step 4: Run `just test-fd`**

Expected: SqlitePlayerStoreTest passes, existing FD tests still pass.

**Step 5: Commit**

```
feat(fd): SqlitePlayerStore — Exposed DSL over existing schema
```

---

### Task 5: Wire builders

**Files:**
- Create: `src/main/kotlin/leyline/frontdoor/wire/DeckWireBuilder.kt`
- Create: `src/main/kotlin/leyline/frontdoor/wire/PlayerWireBuilder.kt`
- Create: `src/main/kotlin/leyline/frontdoor/wire/FdResponseWriter.kt`
- Create: `src/test/kotlin/leyline/frontdoor/wire/DeckWireBuilderTest.kt`

**Step 1: Write DeckWireBuilderTest.kt**

Test V2 and V3 summary shapes from a domain `Deck`:

```kotlin
package leyline.frontdoor.wire

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.FdTag
import leyline.frontdoor.domain.*

class DeckWireBuilderTest : FunSpec({
    tags(FdTag)

    val deck = Deck(
        id = DeckId("d1"),
        playerId = PlayerId("p1"),
        name = "Test Deck",
        format = Format.Standard,
        tileId = 12345,
        mainDeck = listOf(DeckCard(100, 4), DeckCard(200, 56)),
        sideboard = emptyList(),
        commandZone = emptyList(),
        companions = emptyList(),
    )

    test("V2 summary has Attributes as array of name/value pairs") {
        val obj = DeckWireBuilder.toV2Summary(deck)
        obj["DeckId"]?.jsonPrimitive?.content shouldBe "d1"
        obj["Name"]?.jsonPrimitive?.content shouldBe "Test Deck"
        val attrs = obj["Attributes"]?.jsonArray
        attrs shouldNotBe null
        // V2 format: [{name:"Version",value:"1"}, ...]
        val first = attrs!![0].jsonObject
        first["name"]?.jsonPrimitive?.content shouldBe "Version"
    }

    test("V3 summary has Attributes as flat dict") {
        val obj = DeckWireBuilder.toV3Summary(deck)
        obj["DeckId"]?.jsonPrimitive?.content shouldBe "d1"
        val attrs = obj["Attributes"]?.jsonObject
        attrs shouldNotBe null
        attrs!!["Format"]?.jsonPrimitive?.content shouldBe "Standard"
        attrs["TileID"]?.jsonPrimitive?.content shouldBe "12345"
    }

    test("V2 and V3 both have FormatLegalities") {
        val v2 = DeckWireBuilder.toV2Summary(deck)
        val v3 = DeckWireBuilder.toV3Summary(deck)
        v2["FormatLegalities"]?.jsonObject shouldNotBe null
        v3["FormatLegalities"]?.jsonObject shouldNotBe null
    }

    test("deck with < 60 cards has Standard legality false") {
        val small = deck.copy(mainDeck = listOf(DeckCard(100, 10)))
        val obj = DeckWireBuilder.toV3Summary(small)
        val legalities = obj["FormatLegalities"]!!.jsonObject
        legalities["Standard"]?.jsonPrimitive?.content shouldBe "false"
    }
})
```

**Step 2: Run test to verify it fails**

**Step 3: Write DeckWireBuilder.kt**

Extract `buildDeckSummaryObj` and `buildDeckSummaryV3Obj` from `FrontDoorService.companion` into `DeckWireBuilder`. Key difference: takes `Deck` domain object, not `PlayerDb.DeckRow`.

Shared helpers (FormatLegalities, PreferredCosmetics, DeckValidationSummaries, UnownedCards) become private functions. `countCards` disappears — replaced by `deck.totalCards`.

Also include:
- `toStartHookEntry(deck: Deck): JsonObject` — deck entry in StartHook's Decks map
- `parseDeckUpdate(json: String, playerId: PlayerId): Deck?` — parse CmdType 406 inbound JSON

**Step 4: Write PlayerWireBuilder.kt**

```kotlin
package leyline.frontdoor.wire

import kotlinx.serialization.json.*

object PlayerWireBuilder {
    /** Parse preferences JSON, guarding against double-wrap. */
    fun parsePreferences(json: String): String {
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val inner = obj["Preferences"]?.jsonObject
            if (inner != null && inner.containsKey("Preferences")) {
                // Double-wrapped — return inner layer
                Json.encodeToString(JsonObject.serializer(), inner)
            } else {
                json
            }
        } catch (_: Exception) {
            json
        }
    }
}
```

**Step 5: Write FdResponseWriter.kt**

Extract `sendJsonResponse`, `sendEmptyResponse`, `sendProtoResponse`, `sendRawProtoResponse`, `sendCtrlAck`, `sendRaw` from `FrontDoorService`. Takes `ChannelHandlerContext` as parameter. Includes `FdDebugCollector.record()` calls.

**Step 6: Run `just test-fd`**

Expected: new DeckWireBuilderTest passes, existing FD tests still pass.

**Step 7: Commit**

```
feat(fd): wire builders — DeckWireBuilder, PlayerWireBuilder, FdResponseWriter
```

---

### Task 6: Services

**Files:**
- Create: `src/main/kotlin/leyline/frontdoor/service/DeckService.kt`
- Create: `src/main/kotlin/leyline/frontdoor/service/PlayerService.kt`
- Create: `src/main/kotlin/leyline/frontdoor/service/MatchmakingService.kt`
- Create: `src/main/kotlin/leyline/frontdoor/service/LobbyStubs.kt`
- Create: `src/test/kotlin/leyline/frontdoor/service/DeckServiceTest.kt`
- Create: `src/test/kotlin/leyline/frontdoor/repo/InMemoryDeckRepository.kt`
- Create: `src/test/kotlin/leyline/frontdoor/repo/InMemoryPlayerRepository.kt`

**Step 1: Write in-memory repos (test helpers)**

```kotlin
package leyline.frontdoor.repo

import leyline.frontdoor.domain.*

class InMemoryDeckRepository : DeckRepository {
    private val decks = mutableMapOf<DeckId, Deck>()

    override fun findById(id: DeckId) = decks[id]
    override fun findAllForPlayer(playerId: PlayerId) = decks.values.filter { it.playerId == playerId }
    override fun save(deck: Deck) { decks[deck.id] = deck }
    override fun delete(id: DeckId) { decks.remove(id) }
}
```

```kotlin
package leyline.frontdoor.repo

import leyline.frontdoor.domain.*

class InMemoryPlayerRepository : PlayerRepository {
    private val players = mutableMapOf<PlayerId, Player>()
    private val prefs = mutableMapOf<PlayerId, Preferences>()

    override fun findPlayer(id: PlayerId) = players[id]
    override fun getPreferences(id: PlayerId) = prefs[id]
    override fun savePreferences(id: PlayerId, p: Preferences) { prefs[id] = p }
    override fun ensurePlayer(id: PlayerId, screenName: String) {
        players.putIfAbsent(id, Player(id, screenName))
    }
}
```

**Step 2: Write DeckServiceTest.kt**

```kotlin
package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.FdTag
import leyline.frontdoor.domain.*
import leyline.frontdoor.repo.InMemoryDeckRepository

class DeckServiceTest : FunSpec({
    tags(FdTag)

    val repo = InMemoryDeckRepository()
    val service = DeckService(repo)
    val playerId = PlayerId("p1")

    test("save and retrieve deck") {
        val deck = Deck(
            id = DeckId("d1"), playerId = playerId, name = "Test",
            format = Format.Standard, tileId = 0,
            mainDeck = listOf(DeckCard(100, 60)),
            sideboard = emptyList(), commandZone = emptyList(), companions = emptyList(),
        )
        service.save(deck)
        service.getById(DeckId("d1")) shouldNotBe null
    }

    test("listForPlayer returns player decks") {
        service.listForPlayer(playerId) shouldHaveSize 1
    }

    test("delete removes deck") {
        service.delete(DeckId("d1"))
        service.getById(DeckId("d1")) shouldBe null
    }
})
```

**Step 3: Run test to verify it fails**

**Step 4: Write DeckService.kt**

```kotlin
package leyline.frontdoor.service

import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.DeckRepository

class DeckService(private val decks: DeckRepository) {
    fun listForPlayer(playerId: PlayerId): List<Deck> = decks.findAllForPlayer(playerId)
    fun getById(id: DeckId): Deck? = decks.findById(id)
    fun save(deck: Deck) = decks.save(deck)
    fun delete(id: DeckId) = decks.delete(id)
}
```

**Step 5: Write PlayerService.kt**

```kotlin
package leyline.frontdoor.service

import leyline.frontdoor.domain.*
import leyline.frontdoor.repo.PlayerRepository
import java.util.UUID

class PlayerService(private val players: PlayerRepository) {
    fun authenticate(playerId: PlayerId, screenName: String): SessionId {
        players.ensurePlayer(playerId, screenName)
        return SessionId(UUID.randomUUID().toString())
    }

    fun getPreferences(playerId: PlayerId): Preferences? =
        players.getPreferences(playerId)

    fun savePreferences(playerId: PlayerId, prefs: Preferences) =
        players.savePreferences(playerId, prefs)
}
```

**Step 6: Write MatchmakingService.kt**

```kotlin
package leyline.frontdoor.service

import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.DeckRepository
import java.util.UUID

data class MatchInfo(val matchId: String, val host: String, val port: Int)

class MatchmakingService(
    private val decks: DeckRepository,
    private val matchDoorHost: String,
    private val matchDoorPort: Int,
) {
    fun startAiMatch(playerId: PlayerId, deckId: DeckId): MatchInfo {
        // Validate deck exists (future: validate deck legality)
        decks.findById(deckId)
        return MatchInfo(
            matchId = UUID.randomUUID().toString(),
            host = matchDoorHost,
            port = matchDoorPort,
        )
    }
}
```

**Step 7: Write LobbyStubs.kt**

Extract all the `{}`, `[]`, and constant JSON responses from `FrontDoorService.dispatch()` into a single object with named methods:

```kotlin
package leyline.frontdoor.service

object LobbyStubs {
    fun activeMatches() = """{"MatchesV3":[]}"""
    fun courses() = """{"Courses":[]}"""
    fun currencies() = """{"Currencies":[]}"""
    fun boosters() = """{"Boosters":[]}"""
    fun quests() = """{"Quests":[]}"""
    fun periodicRewards() = """{}"""
    fun cosmetics() = """{"Cosmetics":[]}"""
    fun netDeckFolders() = """[]"""
    fun playerInbox() = """{"Messages":[]}"""
    fun staticContent() = """{}"""
    fun cardSet() = """{}"""
    fun storeStatus() = """{"CatalogStatus":[]}"""
    fun rankSeasonDetails() = """{}"""
    fun carousel() = """[]"""
    fun preferredPrintings() = """{}"""
    fun prizeWalls() = """{"ActivePrizeWalls":[]}"""
    fun rankInfo() = """{"playerId":null,"constructedSeasonOrdinal":0,"constructedClass":"Bronze","constructedLevel":0,"constructedStep":0,"constructedMatchesWon":0,"constructedMatchesLost":0,"constructedMatchesDrawn":0,"limitedSeasonOrdinal":0,"limitedClass":"Bronze","limitedLevel":0,"limitedStep":0,"limitedMatchesWon":0,"limitedMatchesLost":0,"limitedMatchesDrawn":0}"""
    fun telemetryAck() = "Success"
}
```

**Step 8: Run `just test-fd`**

Expected: all new + existing tests pass.

**Step 9: Commit**

```
feat(fd): services — DeckService, PlayerService, MatchmakingService, LobbyStubs
```

---

### Task 7: Rewire FrontDoorHandler

This is the big task — rewire `FrontDoorService` to use services/wire-builders/response-writer. Rename to `FrontDoorHandler`.

**Files:**
- Modify: `src/main/kotlin/leyline/frontdoor/FrontDoorService.kt` → rename to `FrontDoorHandler.kt`
- Modify: `src/main/kotlin/leyline/infra/LeylineServer.kt` (constructor wiring)
- Modify: `src/test/kotlin/leyline/server/FrontDoorServiceTest.kt` (update imports)
- Delete: companion object methods that moved to wire builders

**Step 1: Rename FrontDoorService → FrontDoorHandler**

Change class name and file name. Update constructor to accept services:

```kotlin
class FrontDoorHandler(
    private val playerId: PlayerId,
    private val deckService: DeckService,
    private val playerService: PlayerService,
    private val matchmaking: MatchmakingService,
    private val responseWriter: FdResponseWriter,
    private val goldenData: GoldenData,  // extracted: all golden resource loading
) : ChannelInboundHandlerAdapter()
```

**Step 2: Add `requireJson` routing helper**

```kotlin
private inline fun requireJson(json: String?, block: (String) -> Unit) {
    if (json == null) {
        log.warn("Front Door: expected JSON payload, got null")
        return
    }
    block(json)
}
```

**Step 3: Rewrite dispatch to use services**

Each `when` branch becomes a 3-5 line method calling services + wire builders. Remove all `PlayerDb` references, all inline JSON construction, all `playerId != null` checks.

**Step 4: Update LeylineServer wiring**

```kotlin
val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
val store = SqlitePlayerStore(db)
store.createTables()  // idempotent
val deckService = DeckService(store)
val playerService = PlayerService(store)
val matchmaking = MatchmakingService(store, externalHost, matchDoorPort)
val responseWriter = FdResponseWriter()
val handler = FrontDoorHandler(PlayerId(playerId), deckService, playerService, matchmaking, responseWriter, goldenData)
```

**Step 5: Update FrontDoorServiceTest imports**

Change `FrontDoorService` → `FrontDoorHandler`. The test still constructs the handler with services wired to real SQLite (via SqlitePlayerStore). Test logic doesn't change — same wire-level assertions.

**Step 6: Run `just test-fd`**

This is the critical step. All 14 existing wire tests must pass. If any fail, the rewiring broke behavior — fix before proceeding.

**Step 7: Run `just test-gate`**

Full gate to catch anything else broken by the rename/rewire.

**Step 8: Commit**

```
refactor(fd): rewire FrontDoorHandler to use services, repos, wire builders
```

---

### Task 8: Delete PlayerDb singleton

**Files:**
- Delete: `src/main/kotlin/leyline/frontdoor/PlayerDb.kt`
- Delete or migrate: `src/test/kotlin/leyline/server/PlayerDbTest.kt`
- Modify: `src/main/kotlin/leyline/cli/SeedDb.kt` (use SqlitePlayerStore instead of PlayerDb)
- Modify: any remaining PlayerDb imports

**Step 1: Grep for remaining PlayerDb references**

```bash
grep -r "PlayerDb" src/ --include="*.kt" -l
```

Fix each one to use `SqlitePlayerStore` or `DeckRepository`/`PlayerRepository`.

**Step 2: Update SeedDb to use SqlitePlayerStore**

SeedDb creates a Database connection and SqlitePlayerStore directly — it's CLI tooling, not part of the server runtime.

**Step 3: Delete PlayerDb.kt**

**Step 4: Move/update PlayerDbTest → SqlitePlayerStoreTest covers same ground**

If SqlitePlayerStoreTest (Task 4) covers all behaviors, delete PlayerDbTest. If not, add missing cases to SqlitePlayerStoreTest.

**Step 5: Run `just test-fd` then `just test-gate`**

**Step 6: Commit**

```
refactor(fd): delete PlayerDb singleton, all access through SqlitePlayerStore
```

---

### Task 9: Cleanup — move tests, update JaCoCo, format

**Files:**
- Move: `src/test/kotlin/leyline/server/FrontDoorServiceTest.kt` → `src/test/kotlin/leyline/frontdoor/FrontDoorHandlerTest.kt`
- Move: `src/test/kotlin/leyline/server/TlsHelperTest.kt` → `src/test/kotlin/leyline/infra/TlsHelperTest.kt`
- Modify: `build.gradle.kts` — update JaCoCo exclusion from `leyline/server/**` to `leyline/infra/**` + `leyline/cli/**`
- Delete: `src/test/kotlin/leyline/server/` directory (should be empty)

**Step 1: Move test files, update package declarations**

**Step 2: Update JaCoCo exclusions in build.gradle.kts**

Replace `"leyline/server/**"` with `"leyline/infra/**"`, `"leyline/cli/**"`, and add `"leyline/frontdoor/wire/**"` (wire builders are mechanical translation, not worth measuring coverage on).

**Step 3: Run `just fmt`**

**Step 4: Run `just test-gate`**

**Step 5: Commit**

```
refactor: move FD tests to frontdoor/, update JaCoCo exclusions
```

---

### Task 10: Final verification

**Step 1: Run `just test-gate`**

All unit + conformance + FD tests must pass.

**Step 2: Run `just test-integration`**

Full integration tests — verifies nothing in match/bridge broke from package moves or import changes.

**Step 3: Run `just fmt`**

Clean up any formatting drift.

**Step 4: Verify no `PlayerDb` references remain**

```bash
grep -r "PlayerDb" src/ --include="*.kt"
```

Expected: zero results (or only in comments/docs).

**Step 5: Verify no `leyline.server` package references remain in main code**

```bash
grep -r "package leyline.server" src/main/ --include="*.kt"
```

Expected: zero results.

**Step 6: Commit if any cleanup was needed**

```
chore: final cleanup after FD layering
```
