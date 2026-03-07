# SQLite Player Store Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace golden JSON files with a SQLite player store. Server opens `player.db` at boot, serves FD endpoints from it. One-time `just seed-db` migrates goldens + txt decks into DB.

**Architecture:** `player.db` with `players` + `decks` tables (JSON blob columns). `just seed-db` CLI command reads golden captures + `decks/*.txt` → populates DB once. At runtime, FrontDoorService reads DB only — no golden files, no DeckCatalog, no txt parsing. Client deck edits (406) persist to DB.

**Tech Stack:** SQLite JDBC (already in deps), Kotlin, manual JSON (match existing codebase patterns).

---

## Schema

```sql
CREATE TABLE IF NOT EXISTS players (
    player_id   TEXT PRIMARY KEY,
    screen_name TEXT NOT NULL DEFAULT 'Planeswalker',
    preferences TEXT NOT NULL DEFAULT '{}',
    inventory   TEXT NOT NULL DEFAULT '{}',
    cosmetics   TEXT NOT NULL DEFAULT '{}',
    rank_info   TEXT NOT NULL DEFAULT '{}',
    created_at  TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS decks (
    deck_id    TEXT PRIMARY KEY,
    player_id  TEXT NOT NULL REFERENCES players(player_id),
    summary    TEXT NOT NULL,
    cards      TEXT NOT NULL,
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_decks_player ON decks(player_id);
```

## Data Flow

```
SEED (one-time, `just seed-db`):
  golden start-hook.json → parse inventory/cosmetics/decks → INSERT into player.db
  golden player-preferences.json → INSERT preferences
  decks/*.txt → DeckCatalog.scan() → resolve grpIds → INSERT as decks
  Precreate player "Denis" with golden data

RUNTIME (every boot):
  Boot → open player.db → serve from DB
  StartHook (1) → SELECT player + decks → assemble JSON → serve
  Preferences (1911) → SELECT preferences → serve
  Deck edit (406) → UPDATE decks → ack
  Deck summaries (410) → SELECT decks → serve
  Play (612) → store deckId → MatchHandler resolves from PlayerDb
```

## DB File Location

`{projectDir}/data/player.db` — gitignored. Env override: `LEYLINE_PLAYER_DB`.

---

### Task 1: Create PlayerDb singleton

**Files:**
- Create: `src/main/kotlin/leyline/server/PlayerDb.kt`
- Test: `src/test/kotlin/leyline/server/PlayerDbTest.kt`

**Step 1: Write failing test**

```kotlin
package leyline.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

class PlayerDbTest : FunSpec({
    val testDb = File.createTempFile("player-test", ".db").also { it.deleteOnExit() }

    beforeSpec {
        PlayerDb.init(testDb)
    }

    test("init creates tables") {
        PlayerDb.isInitialized() shouldBe true
    }

    test("upsertPlayer creates player on first call") {
        PlayerDb.upsertPlayer("test-player-1", "Tester")
        val p = PlayerDb.getPlayer("test-player-1")
        p shouldNotBe null
        p!!.screenName shouldBe "Tester"
    }

    test("upsertPlayer is idempotent") {
        PlayerDb.upsertPlayer("test-player-1", "Tester")
        PlayerDb.upsertPlayer("test-player-1", "Tester")
        val p = PlayerDb.getPlayer("test-player-1")
        p shouldNotBe null
    }

    test("getPreferences returns stored preferences") {
        PlayerDb.updatePreferences("test-player-1", """{"PlayBladeSelectionData":"{}"}""")
        val prefs = PlayerDb.getPreferences("test-player-1")
        prefs shouldNotBe null
        prefs!! shouldBe """{"PlayBladeSelectionData":"{}"}"""
    }
})
```

**Step 2: Run test — expect compile failure (PlayerDb doesn't exist)**

Run: `just test-one PlayerDbTest`

**Step 3: Implement PlayerDb**

```kotlin
package leyline.server

import org.slf4j.LoggerFactory
import java.io.File
import java.sql.DriverManager

object PlayerDb {
    private val log = LoggerFactory.getLogger(PlayerDb::class.java)
    private var dbPath: String? = null

    data class Player(
        val playerId: String,
        val screenName: String,
        val preferences: String,
        val inventory: String,
        val cosmetics: String,
        val rankInfo: String,
    )

    data class DeckRow(
        val deckId: String,
        val playerId: String,
        val summary: String,
        val cards: String,
    )

    fun init(dbFile: File): Boolean {
        dbPath = dbFile.absolutePath
        dbFile.parentFile?.mkdirs()
        conn { c ->
            c.createStatement().use { s ->
                s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS players (
                        player_id   TEXT PRIMARY KEY,
                        screen_name TEXT NOT NULL DEFAULT 'Planeswalker',
                        preferences TEXT NOT NULL DEFAULT '{}',
                        inventory   TEXT NOT NULL DEFAULT '{}',
                        cosmetics   TEXT NOT NULL DEFAULT '{}',
                        rank_info   TEXT NOT NULL DEFAULT '{}',
                        created_at  TEXT DEFAULT (datetime('now'))
                    )
                """)
                s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS decks (
                        deck_id    TEXT PRIMARY KEY,
                        player_id  TEXT NOT NULL,
                        summary    TEXT NOT NULL,
                        cards      TEXT NOT NULL,
                        updated_at TEXT DEFAULT (datetime('now'))
                    )
                """)
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_decks_player ON decks(player_id)")
            }
        }
        log.info("PlayerDb initialized: {}", dbFile)
        return true
    }

    fun isInitialized(): Boolean = dbPath != null

    fun upsertPlayer(playerId: String, screenName: String = "Planeswalker") {
        conn { c ->
            c.prepareStatement("""
                INSERT INTO players (player_id, screen_name) VALUES (?, ?)
                ON CONFLICT(player_id) DO NOTHING
            """).use { s ->
                s.setString(1, playerId)
                s.setString(2, screenName)
                s.executeUpdate()
            }
        }
    }

    fun getPlayer(playerId: String): Player? = conn { c ->
        c.prepareStatement("SELECT * FROM players WHERE player_id = ?").use { s ->
            s.setString(1, playerId)
            val rs = s.executeQuery()
            if (rs.next()) Player(
                playerId = rs.getString("player_id"),
                screenName = rs.getString("screen_name"),
                preferences = rs.getString("preferences"),
                inventory = rs.getString("inventory"),
                cosmetics = rs.getString("cosmetics"),
                rankInfo = rs.getString("rank_info"),
            ) else null
        }
    }

    fun getPreferences(playerId: String): String? = conn { c ->
        c.prepareStatement("SELECT preferences FROM players WHERE player_id = ?").use { s ->
            s.setString(1, playerId)
            val rs = s.executeQuery()
            if (rs.next()) rs.getString("preferences") else null
        }
    }

    fun updatePreferences(playerId: String, preferencesJson: String) {
        conn { c ->
            c.prepareStatement("UPDATE players SET preferences = ? WHERE player_id = ?").use { s ->
                s.setString(1, preferencesJson)
                s.setString(2, playerId)
                s.executeUpdate()
            }
        }
    }

    fun updateInventory(playerId: String, inventoryJson: String) {
        conn { c ->
            c.prepareStatement("UPDATE players SET inventory = ? WHERE player_id = ?").use { s ->
                s.setString(1, inventoryJson)
                s.setString(2, playerId)
                s.executeUpdate()
            }
        }
    }

    fun updateCosmetics(playerId: String, cosmeticsJson: String) {
        conn { c ->
            c.prepareStatement("UPDATE players SET cosmetics = ? WHERE player_id = ?").use { s ->
                s.setString(1, cosmeticsJson)
                s.setString(2, playerId)
                s.executeUpdate()
            }
        }
    }

    // --- Deck CRUD ---

    fun upsertDeck(deckId: String, playerId: String, summary: String, cards: String) {
        conn { c ->
            c.prepareStatement("""
                INSERT INTO decks (deck_id, player_id, summary, cards, updated_at)
                VALUES (?, ?, ?, ?, datetime('now'))
                ON CONFLICT(deck_id) DO UPDATE SET
                    summary = excluded.summary,
                    cards = excluded.cards,
                    updated_at = datetime('now')
            """).use { s ->
                s.setString(1, deckId)
                s.setString(2, playerId)
                s.setString(3, summary)
                s.setString(4, cards)
                s.executeUpdate()
            }
        }
    }

    fun getDeck(deckId: String): DeckRow? = conn { c ->
        c.prepareStatement("SELECT * FROM decks WHERE deck_id = ?").use { s ->
            s.setString(1, deckId)
            val rs = s.executeQuery()
            if (rs.next()) DeckRow(
                deckId = rs.getString("deck_id"),
                playerId = rs.getString("player_id"),
                summary = rs.getString("summary"),
                cards = rs.getString("cards"),
            ) else null
        }
    }

    fun getDecksForPlayer(playerId: String): List<DeckRow> = conn { c ->
        c.prepareStatement("SELECT * FROM decks WHERE player_id = ? ORDER BY updated_at DESC").use { s ->
            s.setString(1, playerId)
            val rs = s.executeQuery()
            val result = mutableListOf<DeckRow>()
            while (rs.next()) {
                result.add(DeckRow(
                    deckId = rs.getString("deck_id"),
                    playerId = rs.getString("player_id"),
                    summary = rs.getString("summary"),
                    cards = rs.getString("cards"),
                ))
            }
            result
        }
    }

    fun deleteDeck(deckId: String) {
        conn { c ->
            c.prepareStatement("DELETE FROM decks WHERE deck_id = ?").use { s ->
                s.setString(1, deckId)
                s.executeUpdate()
            }
        }
    }

    private fun <T> conn(block: (java.sql.Connection) -> T): T {
        val path = dbPath ?: error("PlayerDb not initialized")
        return DriverManager.getConnection("jdbc:sqlite:$path").use(block)
    }
}
```

**Step 4: Run test — expect pass**

Run: `just test-one PlayerDbTest`

**Step 5: Commit**

```
feat: add PlayerDb singleton with players + decks tables
```

---

### Task 2: Add deck CRUD tests

**Files:**
- Modify: `src/test/kotlin/leyline/server/PlayerDbTest.kt` (extend)

**Step 1: Add deck tests**

```kotlin
test("upsertDeck stores and retrieves deck") {
    PlayerDb.upsertDeck(
        deckId = "deck-1",
        playerId = "test-player-1",
        summary = """{"DeckId":"deck-1","Name":"Test Deck"}""",
        cards = """{"MainDeck":[{"cardId":93855,"quantity":4}],"Sideboard":[]}""",
    )
    val deck = PlayerDb.getDeck("deck-1")
    deck shouldNotBe null
    deck!!.deckId shouldBe "deck-1"
    deck.summary shouldBe """{"DeckId":"deck-1","Name":"Test Deck"}"""
}

test("getDecksForPlayer returns all player decks") {
    PlayerDb.upsertDeck("deck-2", "test-player-1", """{"DeckId":"deck-2","Name":"Deck 2"}""", "{}")
    val decks = PlayerDb.getDecksForPlayer("test-player-1")
    decks.size shouldBe 2
}

test("deleteDeck removes deck") {
    PlayerDb.deleteDeck("deck-2")
    PlayerDb.getDeck("deck-2") shouldBe null
}

test("upsertDeck updates existing deck") {
    PlayerDb.upsertDeck("deck-1", "test-player-1", """{"DeckId":"deck-1","Name":"Updated"}""", "{}")
    val deck = PlayerDb.getDeck("deck-1")
    deck!!.summary shouldBe """{"DeckId":"deck-1","Name":"Updated"}"""
}
```

**Step 2: Run tests — expect pass (implementation already in Task 1)**

Run: `just test-one PlayerDbTest`

**Step 3: Commit**

```
test: add deck CRUD tests for PlayerDb
```

---

### Task 3: Create `just seed-db` CLI command

**Files:**
- Create: `src/main/kotlin/leyline/server/SeedDb.kt` (main function)
- Modify: `justfile` or `just/server.just` — add `seed-db` recipe

This is the one-time migration tool. It:
1. Opens/creates `data/player.db`
2. Creates player "Denis" with golden preferences/inventory/cosmetics
3. Parses golden StartHook decks → inserts into `decks` table
4. Scans `decks/*.txt` → resolves via CardDb → inserts into `decks` table
5. Prints summary

**Step 1: Implement SeedDb.kt**

```kotlin
package leyline.server

import leyline.game.CardDb
import java.io.File
import java.util.UUID

/**
 * One-time DB seeder. Run via `just seed-db`.
 * Reads golden captures + decks/*.txt → populates player.db.
 */
object SeedDb {
    private const val PLAYER_ID = "9da3ee9f-0d6a-4b18-a3e0-c9e315d2475b"
    private const val PLAYER_NAME = "Denis"

    @JvmStatic
    fun main(args: Array<String>) {
        val projectDir = findProjectDir()
        val dbFile = File(projectDir, "data/player.db")
        println("Seeding ${dbFile.absolutePath}")

        PlayerDb.init(dbFile)

        // Create player
        PlayerDb.upsertPlayer(PLAYER_ID, PLAYER_NAME)
        println("Player: $PLAYER_NAME ($PLAYER_ID)")

        // Seed from goldens
        seedFromGoldens()

        // Seed txt decks (if CardDb available)
        val cardDbPath = System.getenv("LEYLINE_CARD_DB")
        if (cardDbPath != null && File(cardDbPath).exists()) {
            CardDb.init(File(cardDbPath))
            seedTxtDecks(File(projectDir, "decks"))
        } else {
            println("LEYLINE_CARD_DB not set — skipping txt deck import")
        }

        // Summary
        val player = PlayerDb.getPlayer(PLAYER_ID)!!
        val decks = PlayerDb.getDecksForPlayer(PLAYER_ID)
        println("\nDone. Player has ${decks.size} deck(s):")
        for (d in decks) {
            // Extract name from summary JSON
            val name = Regex(""""Name"\s*:\s*"([^"]+)"""").find(d.summary)?.groupValues?.get(1) ?: d.deckId
            println("  - $name (${d.deckId})")
        }
    }

    private fun seedFromGoldens() {
        val startHook = loadResource("fd-golden/start-hook.json")
        val prefs = loadResource("fd-golden/player-preferences.json")

        // Preferences
        PlayerDb.updatePreferences(PLAYER_ID, prefs)
        println("Seeded preferences")

        // Inventory, cosmetics from StartHook
        extractJsonObject(startHook, "InventoryInfo")?.let {
            PlayerDb.updateInventory(PLAYER_ID, it)
            println("Seeded inventory")
        }
        extractJsonObject(startHook, "PreferredCosmetics")?.let {
            PlayerDb.updateCosmetics(PLAYER_ID, it)
            println("Seeded cosmetics")
        }

        // Decks from StartHook
        seedGoldenDecks(startHook)
    }

    private fun seedGoldenDecks(startHook: String) {
        val summariesArray = extractJsonArray(startHook, "DeckSummariesV2") ?: return
        val decksObj = extractJsonObject(startHook, "Decks") ?: return

        val deckIds = Regex(""""DeckId"\s*:\s*"([^"]+)"""").findAll(summariesArray)
        var count = 0
        for (match in deckIds) {
            val deckId = match.groupValues[1]
            val summary = extractDeckEntry(summariesArray, deckId) ?: continue
            val cards = extractDeckCards(decksObj, deckId) ?: continue
            PlayerDb.upsertDeck(deckId, PLAYER_ID, summary, cards)
            count++
        }
        println("Seeded $count golden deck(s)")
    }

    private fun seedTxtDecks(decksDir: File) {
        if (!decksDir.isDirectory) return
        val txtFiles = decksDir.listFiles { f -> f.extension == "txt" } ?: return
        var count = 0
        for (file in txtFiles) {
            val deck = parseTxtDeck(file) ?: continue
            PlayerDb.upsertDeck(deck.deckId, PLAYER_ID, deck.summary, deck.cards)
            count++
            println("Imported txt deck: ${deck.name}")
        }
        println("Seeded $count txt deck(s)")
    }

    private data class ParsedDeck(
        val deckId: String,
        val name: String,
        val summary: String,
        val cards: String,
    )

    private fun parseTxtDeck(file: File): ParsedDeck? {
        val deckId = UUID.nameUUIDFromBytes(file.name.toByteArray()).toString()
        val name = file.nameWithoutExtension.replace("-", " ")
            .replaceFirstChar { it.uppercase() }

        val mainDeck = mutableListOf<Pair<Int, Int>>() // grpId, qty
        var tileId = 0

        for (line in file.readLines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "Deck" || trimmed == "Sideboard") continue
            val match = Regex("""^(\d+)\s+(.+)$""").find(trimmed) ?: continue
            val qty = match.groupValues[1].toInt()
            val cardName = match.groupValues[2].trim()
            val grpId = CardDb.getGrpId(cardName)
            if (grpId == null) {
                println("  WARN: unknown card '$cardName' in ${file.name}")
                continue
            }
            mainDeck.add(grpId to qty)
            // First non-land card as tile
            if (tileId == 0 && !cardName.contains("Plains") && !cardName.contains("Island") &&
                !cardName.contains("Swamp") && !cardName.contains("Mountain") && !cardName.contains("Forest")
            ) {
                tileId = grpId
            }
        }

        if (mainDeck.isEmpty()) return null

        val mainCards = mainDeck.joinToString(",") { """{"cardId":${it.first},"quantity":${it.second}}""" }
        val cardCount = mainDeck.sumOf { it.second }
        val legal = cardCount >= 60

        val summary = """{"DeckId":"$deckId","Name":"$name","Attributes":[{"name":"Version","value":"1"},{"name":"Format","value":"Standard"},{"name":"TileID","value":"$tileId"}],"DeckTileId":$tileId,"DeckArtId":0,"FormatLegalities":{"Standard":$legal,"Historic":$legal,"Explorer":$legal,"Timeless":$legal,"Alchemy":$legal,"Brawl":false},"PreferredCosmetics":{"Avatar":"","Sleeve":"","Pet":"","Title":"","Emotes":[]},"DeckValidationSummaries":[],"UnownedCards":{}}"""

        val cards = """{"MainDeck":[$mainCards],"ReducedSideboard":[],"Sideboard":[],"CommandZone":[],"Companions":[],"CardSkins":[]}"""

        return ParsedDeck(deckId, name, summary, cards)
    }

    // --- JSON extraction helpers (bracket-counting) ---

    private fun extractJsonObject(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*\{""".toRegex()
        val match = pattern.find(json) ?: return null
        val start = json.indexOf('{', match.range.first)
        return extractBracketed(json, start, '{', '}')
    }

    private fun extractJsonArray(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*\[""".toRegex()
        val match = pattern.find(json) ?: return null
        val start = json.indexOf('[', match.range.first)
        return extractBracketed(json, start, '[', ']')
    }

    private fun extractBracketed(json: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        for (i in start until json.length) {
            when (json[i]) {
                open -> depth++
                close -> { depth--; if (depth == 0) return json.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun extractDeckEntry(summariesArray: String, deckId: String): String? {
        val pos = summariesArray.indexOf("\"$deckId\"")
        if (pos < 0) return null
        var braceStart = pos
        while (braceStart > 0 && summariesArray[braceStart] != '{') braceStart--
        return extractBracketed(summariesArray, braceStart, '{', '}')
    }

    private fun extractDeckCards(decksObj: String, deckId: String): String? {
        val pattern = """"$deckId"\s*:\s*\{""".toRegex()
        val match = pattern.find(decksObj) ?: return null
        val start = decksObj.indexOf('{', match.range.last - 1)
        return extractBracketed(decksObj, start, '{', '}')
    }

    private fun loadResource(path: String): String =
        SeedDb::class.java.classLoader.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Missing classpath resource: $path")

    private fun findProjectDir(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "justfile").exists() || File(dir, "build.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        return File(".")
    }
}
```

**Step 2: Add just recipe**

In `just/server.just` (or appropriate justfile):

```just
# one-time: seed player.db from golden captures + txt decks
seed-db: (_require classpath) check-java
    @{{_cli}} leyline.server.SeedDb
```

**Step 3: Run it**

```bash
just seed-db
```

Expected output:
```
Seeding /Users/denislebedev/src/leyline/data/player.db
Player: Denis (9da3ee9f-0d6a-4b18-a3e0-c9e315d2475b)
Seeded preferences
Seeded inventory
Seeded cosmetics
Seeded 5 golden deck(s)
Imported txt deck: Simple test
Seeded 1 txt deck(s)

Done. Player has 6 deck(s):
  - Golden Deck 1 (...)
  - ...
  - Simple test (...)
```

**Step 4: Commit**

```
feat: add `just seed-db` to populate player.db from goldens + txt decks
```

---

### Task 4: Wire PlayerDb into FrontDoorService (StartHook from DB)

**Files:**
- Modify: `src/main/kotlin/leyline/server/FrontDoorService.kt`
- Modify: `src/main/kotlin/leyline/server/LeylineServer.kt`

**Step 1: Add PlayerDb.init() to LeylineServer startup**

In `startStub()`, before FrontDoorService creation:

```kotlin
val playerDbFile = File(findLeylineDir() ?: ".", "data/player.db")
if (playerDbFile.exists()) {
    PlayerDb.init(playerDbFile)
} else {
    log.warn("No player.db found — run `just seed-db` first. Using golden fallback.")
}
```

**Step 2: Add `playerId` to FrontDoorService constructor**

```kotlin
class FrontDoorService(
    matchDoorHost: String,
    matchDoorPort: Int,
    playerId: String? = null,
    // ... existing params
)
```

Pass `playerId = "9da3ee9f-0d6a-4b18-a3e0-c9e315d2475b"` from LeylineServer.

**Step 3: Replace `patchStartHook()` with `buildStartHook()`**

```kotlin
private fun buildStartHook(): String {
    val golden = loadTextResource("fd-golden/start-hook.json")
    if (playerId == null || !PlayerDb.isInitialized()) return golden

    val player = PlayerDb.getPlayer(playerId) ?: return golden
    val decks = PlayerDb.getDecksForPlayer(playerId)
    if (decks.isEmpty()) return golden

    // Build DeckSummariesV2 array
    val summaries = decks.joinToString(",") { it.summary }

    // Build Decks object
    val decksMap = decks.joinToString(",") { """"${it.deckId}":${it.cards}""" }

    // Replace deck sections in golden JSON
    var patched = golden
    patched = replaceBracketedSection(patched, "DeckSummariesV2", "[", "]", "[$summaries]")
    patched = replaceBracketedSection(patched, "Decks", "{", "}", "{$decksMap}")

    // Replace inventory/cosmetics if stored
    if (player.inventory != "{}") {
        patched = replaceBracketedSection(patched, "InventoryInfo", "{", "}", player.inventory)
    }
    if (player.cosmetics != "{}") {
        patched = replaceBracketedSection(patched, "PreferredCosmetics", "{", "}", player.cosmetics)
    }

    log.info("StartHook assembled from PlayerDb: {} deck(s)", decks.size)
    return patched
}
```

**Step 4: Remove `decksDir` constructor param and `patchStartHook()` method. Remove DeckCatalog import.**

**Step 5: Build + run `just serve-stub`**

Verify client connects and shows decks from DB.

**Step 6: Commit**

```
refactor: serve StartHook from PlayerDb instead of golden files
```

---

### Task 5: Serve Preferences from PlayerDb

**Files:**
- Modify: `src/main/kotlin/leyline/server/FrontDoorService.kt`

**Step 1: Update 1911 handler**

```kotlin
1911 -> {
    val prefs = if (playerId != null && PlayerDb.isInitialized())
        PlayerDb.getPreferences(playerId) else null
    val json = prefs ?: playerPreferencesJson
    log.info("Front Door: PlayerPreferences ({})", if (prefs != null) "db" else "golden")
    sendJsonResponse(ctx, txId, json)
}
```

**Step 2: Build + verify**

**Step 3: Commit**

```
feat: serve PlayerPreferences from PlayerDb
```

---

### Task 6: Handle deck update/delete from client (406/410)

**Files:**
- Modify: `src/main/kotlin/leyline/server/FrontDoorService.kt`

**Step 1: Add 406 handler (Deck_UpdateDeckV3)**

```kotlin
406 -> {
    if (json != null && playerId != null && PlayerDb.isInitialized()) {
        val summary = extractJsonObject(json, "Summary")
        val cards = extractJsonObject(json, "Deck")
        val deckId = Regex(""""DeckId"\s*:\s*"([^"]+)"""").find(summary ?: "")?.groupValues?.get(1)
        if (deckId != null && summary != null && cards != null) {
            PlayerDb.upsertDeck(deckId, playerId, summary, cards)
            log.info("Front Door: Deck_UpdateDeckV3 saved deck {}", deckId)
        }
    }
    sendEmptyResponse(ctx, txId)
}
```

**Step 2: Add 410 handler (Deck_GetDeckSummariesV3)**

```kotlin
410 -> {
    if (playerId != null && PlayerDb.isInitialized()) {
        val decks = PlayerDb.getDecksForPlayer(playerId)
        val summaries = decks.joinToString(",") { it.summary }
        log.info("Front Door: DeckSummariesV3 ({} decks from db)", decks.size)
        sendJsonResponse(ctx, txId, """{"DeckSummariesV3":[$summaries]}""")
    } else {
        sendEmptyResponse(ctx, txId)
    }
}
```

**Step 3: Build + test. Connect client, edit deck name, verify server log shows "saved".**

**Step 4: Commit**

```
feat: handle deck update/delete from client via PlayerDb
```

---

### Task 7: Wire MatchHandler to read from PlayerDb

**Files:**
- Modify: `src/main/kotlin/leyline/server/MatchHandler.kt`

**Step 1: Update `resolveSeat1Deck()` to use PlayerDb**

```kotlin
private fun resolveSeat1Deck(): String {
    val deckId = selectedDeckOverride?.invoke()
    if (deckId != null && PlayerDb.isInitialized()) {
        val deckRow = PlayerDb.getDeck(deckId)
        if (deckRow != null) {
            log.info("Match Door: using deck '{}' from PlayerDb", deckId)
            return convertArenaCardsToForgeDeck(deckRow.cards)
        }
    }
    return loadDeckFromConfig(playtestConfig.decks.seat1)
}

private fun convertArenaCardsToForgeDeck(cardsJson: String): String {
    val sb = StringBuilder()
    val mainDeckMatch = Regex(""""MainDeck"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(cardsJson)
    if (mainDeckMatch != null) {
        Regex("""\{"cardId"\s*:\s*(\d+)\s*,\s*"quantity"\s*:\s*(\d+)}""")
            .findAll(mainDeckMatch.groupValues[1])
            .forEach { entry ->
                val grpId = entry.groupValues[1].toInt()
                val qty = entry.groupValues[2].toInt()
                val name = CardDb.getCardName(grpId) ?: return@forEach
                sb.appendLine("$qty $name")
            }
    }
    return sb.toString()
}
```

**Step 2: Remove DeckCatalog dependency from MatchHandler.**

**Step 3: Build + full e2e test: `just serve-stub`, select deck, Play, verify match uses selected deck.**

**Step 4: Commit**

```
refactor: MatchHandler reads decks from PlayerDb instead of DeckCatalog
```

---

### Task 8: Clean up — remove DeckCatalog from runtime, add .gitignore

**Files:**
- Modify: `.gitignore` — add `data/`
- Modify/simplify: `src/main/kotlin/leyline/server/DeckCatalog.kt` — keep only for `seed-db`, remove `findById()`/`findByName()`
- Remove: `decksDir` references from LeylineServer/FrontDoorService
- Remove: StartHook patching code from FrontDoorService companion

**Step 1: Add to .gitignore**

```
data/
```

**Step 2: Simplify DeckCatalog** — it's only used by `SeedDb.main()` now. Remove `all()`, `findById()`, `findByName()`. Keep `scan()` for txt parsing.

**Step 3: Remove dead code from FrontDoorService** — `patchStartHook()`, deck-related regex patterns, `buildCardArray()`.

**Step 4: `just fmt && just build`**

**Step 5: Commit**

```
refactor: remove DeckCatalog from runtime path, clean up dead code
```

---

## Not In Scope

- Real authentication (OAuth, WotC login) — playerId is hardcoded
- Collection tracking (card ownership per player)
- Economy simulation (earn gold/gems from matches)
- Deck format validation server-side
- Multi-player support (single player assumed)
- Migration from old player.db schema versions
- Auto-seeding on boot (deliberately manual via `just seed-db`)
