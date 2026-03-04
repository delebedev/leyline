package leyline.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leyline.game.CardDb
import java.io.File

/**
 * One-time DB seeder — run via `just seed-db`.
 * Seeds player from golden blobs, decks from DeckCatalog (txt files).
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
        PlayerDb.upsertPlayer(PLAYER_ID, PLAYER_NAME)
        println("Player: $PLAYER_NAME ($PLAYER_ID)")

        // Player blobs from golden captures
        val prefs = loadResource("fd-golden/player-preferences.json")
        PlayerDb.updatePreferences(PLAYER_ID, prefs)
        println("Seeded preferences")

        // Decks from txt files via DeckCatalog
        val cardDbPath = System.getenv("LEYLINE_CARD_DB")
        val cardDbOk = if (cardDbPath != null) CardDb.init(File(cardDbPath)) else CardDb.init()
        if (cardDbOk) {
            seedDecks(File(projectDir, "decks"))
        } else {
            println("CardDb not available — skipping deck import")
        }

        // Summary
        val decks = PlayerDb.getDecksForPlayer(PLAYER_ID)
        println("\nDone. Player has ${decks.size} deck(s):")
        for (d in decks) {
            println("  - ${d.name} (${d.deckId})")
        }
    }

    private fun seedDecks(decksDir: File) {
        if (!decksDir.isDirectory) return
        val loaded = DeckCatalog.scan(decksDir)
        if (loaded == 0) return

        for (deck in DeckCatalog.all()) {
            val cards = buildJsonObject {
                put("MainDeck", buildCardArray(deck.mainDeck))
                put("ReducedSideboard", buildCardArray(deck.sideboard))
                put("Sideboard", buildCardArray(deck.sideboard))
                put("CommandZone", buildJsonArray {})
                put("Companions", buildJsonArray {})
                put("CardSkins", buildJsonArray {})
            }

            PlayerDb.upsertDeck(
                deckId = deck.deckId,
                playerId = PLAYER_ID,
                name = deck.name,
                tileId = deck.tileId,
                format = "Standard",
                cards = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), cards),
            )
            println("Imported: ${deck.name} (${deck.mainDeck.sumOf { it.quantity }} cards)")
        }
    }

    private fun buildCardArray(cards: List<DeckCatalog.DeckCard>) = buildJsonArray {
        for (c in cards) {
            add(
                buildJsonObject {
                    put("cardId", c.cardId)
                    put("quantity", c.quantity)
                },
            )
        }
    }

    private fun loadResource(path: String): String =
        SeedDb::class.java.classLoader.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Missing classpath resource: $path")

    private fun findProjectDir(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null) {
            if (File(dir, "justfile").exists() || File(dir, "build.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        return File(".").canonicalFile
    }
}
