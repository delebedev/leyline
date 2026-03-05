package leyline.cli

import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.Format
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.domain.Preferences
import leyline.frontdoor.repo.SqlitePlayerStore
import leyline.game.CardDb
import org.jetbrains.exposed.v1.jdbc.Database
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

        val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        val store = SqlitePlayerStore(db)
        store.createTables()

        store.ensurePlayer(PlayerId(PLAYER_ID), PLAYER_NAME)
        println("Player: $PLAYER_NAME ($PLAYER_ID)")

        // Player blobs from golden captures
        val prefs = loadResource("fd-golden/player-preferences.json")
        store.savePreferences(PlayerId(PLAYER_ID), Preferences(prefs))
        println("Seeded preferences")

        // Decks from txt files via DeckCatalog
        val cardDbPath = System.getenv("LEYLINE_CARD_DB")
        val cardDbOk = if (cardDbPath != null) CardDb.init(File(cardDbPath)) else CardDb.init()
        if (cardDbOk) {
            seedDecks(store, File(projectDir, "decks"))
        } else {
            println("CardDb not available — skipping deck import")
        }

        // Summary
        val decks = store.findAllForPlayer(PlayerId(PLAYER_ID))
        println("\nDone. Player has ${decks.size} deck(s):")
        for (d in decks) {
            println("  - ${d.name} (${d.id.value})")
        }
    }

    private fun seedDecks(store: SqlitePlayerStore, decksDir: File) {
        if (!decksDir.isDirectory) return
        val loaded = DeckCatalog.scan(decksDir)
        if (loaded == 0) return

        for (catalog in DeckCatalog.all()) {
            val deck = Deck(
                id = DeckId(catalog.deckId),
                playerId = PlayerId(PLAYER_ID),
                name = catalog.name,
                format = Format.Standard,
                tileId = catalog.tileId,
                mainDeck = catalog.mainDeck.map { DeckCard(it.cardId, it.quantity) },
                sideboard = catalog.sideboard.map { DeckCard(it.cardId, it.quantity) },
                commandZone = emptyList(),
                companions = emptyList(),
            )
            store.save(deck)
            println("Imported: ${catalog.name} (${catalog.mainDeck.sumOf { it.quantity }} cards)")
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
