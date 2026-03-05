package leyline.cli

import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.Format
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.domain.Preferences
import leyline.frontdoor.repo.SqlitePlayerStore
import leyline.game.ExposedCardRepository
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.util.UUID

/**
 * One-time DB seeder — run via `just seed-db`.
 * Seeds player from golden blobs, starter decks from inline definitions.
 */
object SeedDb {
    private const val PLAYER_ID = "9da3ee9f-0d6a-4b18-a3e0-c9e315d2475b"
    private const val PLAYER_NAME = "Denis"

    /** Starter deck definition: name + card list (qty, card name). */
    private data class StarterDeck(val name: String, val cards: List<Pair<Int, String>>)

    /** Built-in starter decks seeded into player.db. */
    private val STARTER_DECKS = listOf(
        StarterDeck(
            "Green stompy",
            listOf(
                4 to "Llanowar Elves",
                4 to "Elvish Mystic",
                4 to "Giant Growth",
                4 to "Garruk's Companion",
                4 to "Leatherback Baloth",
                4 to "Kalonian Tusker",
                4 to "Strangleroot Geist",
                4 to "Rancor",
                4 to "Aspect of Hydra",
                24 to "Forest",
            ),
        ),
        StarterDeck(
            "Simple test",
            listOf(
                4 to "Grizzly Bears",
                4 to "Giant Growth",
                4 to "Llanowar Elves",
                24 to "Forest",
                4 to "Serra Angel",
                4 to "Pacifism",
                16 to "Plains",
            ),
        ),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val projectDir = findProjectDir()
        val dbFile = File(projectDir, "data/player.db")
        dbFile.parentFile.mkdirs()
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

        // Seed starter decks (resolve card names → grpIds via card DB)
        val cardDbPath = System.getenv("LEYLINE_CARD_DB")
        val cardDbFile = if (cardDbPath != null) {
            File(cardDbPath).takeIf { it.exists() }
        } else {
            // Auto-detect from macOS Arena install
            val raw = File(System.getProperty("user.home"))
                .resolve("Library/Application Support/com.wizards.mtga/Downloads/Raw")
            raw.listFiles()?.firstOrNull { it.name.startsWith("Raw_CardDatabase_") && it.name.endsWith(".mtga") }
        }
        if (cardDbFile != null) {
            val cardDb = Database.connect("jdbc:sqlite:${cardDbFile.absolutePath}", "org.sqlite.JDBC")
            val cardRepo = ExposedCardRepository(cardDb)
            seedDecks(store, cardRepo)
        } else {
            println("CardDb not available — skipping deck seeding")
        }

        // Summary
        val decks = store.findAllForPlayer(PlayerId(PLAYER_ID))
        println("\nDone. Player has ${decks.size} deck(s):")
        for (d in decks) {
            println("  - ${d.name} (${d.id.value})")
        }
    }

    private fun seedDecks(store: SqlitePlayerStore, cardRepo: ExposedCardRepository) {
        for (starter in STARTER_DECKS) {
            val mainDeck = mutableListOf<DeckCard>()
            var skipped = 0
            for ((qty, name) in starter.cards) {
                val grpId = cardRepo.findGrpIdByName(name)
                if (grpId != null) {
                    mainDeck.add(DeckCard(grpId, qty))
                } else {
                    println("  Warning: card '$name' not found in card DB, skipping")
                    skipped++
                }
            }
            if (mainDeck.isEmpty()) {
                println("  Skipping '${starter.name}' — no cards resolved")
                continue
            }
            val deckId = UUID.nameUUIDFromBytes(starter.name.toByteArray()).toString()
            val tileId = mainDeck.first().grpId
            val deck = Deck(
                id = DeckId(deckId),
                playerId = PlayerId(PLAYER_ID),
                name = starter.name,
                format = Format.Standard,
                tileId = tileId,
                mainDeck = mainDeck,
                sideboard = emptyList(),
                commandZone = emptyList(),
                companions = emptyList(),
            )
            store.save(deck)
            println("Seeded: ${starter.name} (${mainDeck.sumOf { it.quantity }} cards, $skipped skipped)")
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
