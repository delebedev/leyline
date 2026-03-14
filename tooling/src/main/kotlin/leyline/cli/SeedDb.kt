package leyline.cli

import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.Format
import leyline.frontdoor.domain.PlayerId
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

    /** A card entry: qty, name, optional set code for precise lookup. */
    private data class CardEntry(val quantity: Int, val name: String, val setCode: String? = null)

    /** Arena decklist line: `4 Hallowed Priest (ANB) 9` or simple: `4 Hallowed Priest` */
    private val ARENA_LINE = Regex("""^(\d+)\s+(.+?)\s+\((\w+)\)\s+\d+\s*$""")
    private val SIMPLE_LINE = Regex("""^(\d+)\s+(.+?)\s*$""")

    /** Parse a decklist string (Arena or simple format) into CardEntry list. */
    private fun parseDeckList(text: String): List<CardEntry> =
        text.lines()
            .filter { it.isNotBlank() && !it.startsWith("Deck", ignoreCase = true) && !it.startsWith("Sideboard", ignoreCase = true) }
            .mapNotNull { line ->
                ARENA_LINE.matchEntire(line)?.let { m ->
                    CardEntry(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3])
                } ?: SIMPLE_LINE.matchEntire(line)?.let { m ->
                    CardEntry(m.groupValues[1].toInt(), m.groupValues[2])
                }
            }

    /** Load deck files from data/decks/. Filename (minus .txt) becomes deck name. */
    private fun loadDeckFiles(decksDir: File): List<Pair<String, List<CardEntry>>> {
        if (!decksDir.isDirectory) return emptyList()
        return decksDir.listFiles()
            ?.filter { it.extension == "txt" }
            ?.sortedBy { it.name }
            ?.map { file ->
                val name = file.nameWithoutExtension
                val cards = parseDeckList(file.readText())
                name to cards
            } ?: emptyList()
    }

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
        // Seed decks from data/decks/*.txt
        val decksDir = File(projectDir, "data/decks")
        val deckFiles = loadDeckFiles(decksDir)
        if (deckFiles.isEmpty()) {
            println("No deck files in ${decksDir.absolutePath} — skipping deck seeding")
        } else if (cardDbFile != null) {
            val cardDb = Database.connect("jdbc:sqlite:${cardDbFile.absolutePath}", "org.sqlite.JDBC")
            val cardRepo = ExposedCardRepository(cardDb)
            seedDecks(store, cardRepo, deckFiles)
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

    private fun seedDecks(
        store: SqlitePlayerStore,
        cardRepo: ExposedCardRepository,
        deckFiles: List<Pair<String, List<CardEntry>>>,
    ) {
        // Resolve all decks first, fail fast if any card is missing
        data class ResolvedDeck(val name: String, val cards: List<DeckCard>)

        val resolved = mutableListOf<ResolvedDeck>()
        val errors = mutableListOf<String>()

        for ((deckName, cards) in deckFiles) {
            val mainDeck = mutableListOf<DeckCard>()
            for (entry in cards) {
                val grpId = if (entry.setCode != null) {
                    cardRepo.findGrpIdByNameAndSet(entry.name, entry.setCode)
                        ?: cardRepo.findGrpIdByName(entry.name) // fallback
                } else {
                    cardRepo.findGrpIdByName(entry.name)
                }
                if (grpId != null) {
                    mainDeck.add(DeckCard(grpId, entry.quantity))
                } else {
                    errors.add("  $deckName: '${entry.name}' (${entry.setCode ?: "any set"}) not found")
                }
            }
            if (mainDeck.isNotEmpty()) {
                resolved.add(ResolvedDeck(deckName, mainDeck))
            } else {
                errors.add("  $deckName: no cards resolved at all")
            }
        }

        if (errors.isNotEmpty()) {
            println("Card resolution errors:")
            errors.forEach { println(it) }
            error("Fix deck files before seeding (${errors.size} error(s))")
        }

        for (rd in resolved) {
            val deckId = UUID.nameUUIDFromBytes(rd.name.toByteArray()).toString()
            val tileId = rd.cards.first().grpId
            val deck = Deck(
                id = DeckId(deckId),
                playerId = PlayerId(PLAYER_ID),
                name = rd.name,
                format = Format.Standard,
                tileId = tileId,
                mainDeck = rd.cards,
                sideboard = emptyList(),
                commandZone = emptyList(),
                companions = emptyList(),
            )
            store.save(deck)
            println("Seeded: ${rd.name} (${rd.cards.sumOf { it.quantity }} cards)")
        }
    }

    private fun findProjectDir(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null) {
            if (File(dir, "justfile").exists() || File(dir, "build.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        return File(".").canonicalFile
    }
}
