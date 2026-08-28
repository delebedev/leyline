package leyline.cli

import leyline.config.LeylineConfigResolver
import leyline.domain.Deck
import leyline.domain.DeckId
import leyline.domain.Format
import leyline.domain.PlayerId
import leyline.domain.SystemPlayers
import leyline.domain.deck.DeckCards
import leyline.domain.deck.DecklistException
import leyline.domain.deck.parseDecklist
import leyline.domain.deck.resolveCards
import leyline.domain.repo.DeckRepository
import leyline.game.data.CardRepository
import leyline.game.data.ClientCardDatabase
import leyline.infra.persistence.SqlitePlayerStore
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
    private const val SPECTATOR_ROTATION_FILE = "data/spectator-rotation.txt"

    /**
     * The subset of [deckFiles] the spectator feed rotates through, in the order
     * the rotation file lists them. An unmatched name is reported, since a typo
     * is otherwise invisible until the feed is short a deck.
     */
    private fun loadRotation(
        projectDir: File,
        deckFiles: List<Pair<String, File>>,
    ): List<Pair<String, File>> {
        val file = File(projectDir, SPECTATOR_ROTATION_FILE)
        if (!file.isFile) return emptyList()
        val byName = deckFiles.toMap()
        return file
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { name ->
                val deckFile = byName[name]
                if (deckFile == null) {
                    println("Spectator rotation: no deck file named '$name' — skipped")
                    null
                } else {
                    name to deckFile
                }
            }
    }

    /** Load deck files from data/decks/. Filename (minus .txt) becomes deck name. */
    private fun loadDeckFiles(decksDir: File): List<Pair<String, File>> {
        if (!decksDir.isDirectory) return emptyList()
        return decksDir
            .listFiles()
            ?.filter { it.extension == "txt" }
            ?.sortedBy { it.name }
            ?.map { file -> file.nameWithoutExtension to file } ?: emptyList()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val projectDir = findProjectDir()
        val dbFile = LeylineConfigResolver(baseDir = projectDir, env = System.getenv()).resolve().paths.playerDb
        dbFile.parentFile.mkdirs()
        println("Seeding ${dbFile.absolutePath}")

        val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        val store = SqlitePlayerStore(db)
        store.createTables()

        store.ensurePlayer(PlayerId(PLAYER_ID), PLAYER_NAME)
        println("Player: $PLAYER_NAME ($PLAYER_ID)")

        // Seed starter decks (resolve card names → grpIds via card DB)
        val cardRepo = ClientCardDatabase.open(overridePath = System.getenv("LEYLINE_CARD_DB")).cardRepository()
        // Seed decks from data/decks/*.txt
        val decksDir = File(projectDir, "data/decks")
        val deckFiles = loadDeckFiles(decksDir)
        if (deckFiles.isEmpty()) {
            println("No deck files in ${decksDir.absolutePath} — skipping deck seeding")
            reconcileSpectatorDecks(store, emptySet())
        } else {
            val flavorNameAliases = ForgeFlavorNameAliases.load(projectDir)
            seedDecks(store, cardRepo, deckFiles, flavorNameAliases)

            // Again under the system player that owns the feed, so the rotation
            // is addressable without reading a real account's decks.
            val rotation = loadRotation(projectDir, deckFiles)
            store.ensurePlayer(SystemPlayers.SPECTATOR, "Spectator")
            if (rotation.isEmpty()) {
                println("No spectator rotation — ${SPECTATOR_ROTATION_FILE} missing or lists no known decks")
            }
            val activeRotationDeckIds =
                seedDecks(store, cardRepo, rotation, flavorNameAliases, SystemPlayers.SPECTATOR, "spectator/")
            reconcileSpectatorDecks(store, activeRotationDeckIds)
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
        cardRepo: CardRepository,
        deckFiles: List<Pair<String, File>>,
        flavorNameAliases: Map<String, String>,
        ownerId: PlayerId = PlayerId(PLAYER_ID),
        deckIdPrefix: String = "",
    ): Set<DeckId> {
        data class ResolvedDeck(
            val name: String,
            val cards: DeckCards,
        )

        val resolved = mutableListOf<ResolvedDeck>()
        val errors = mutableListOf<String>()
        val cardNameResolver =
            ImportedCardNameResolver(
                findByName = cardRepo::findGrpIdByName,
                findByNameAndSet = cardRepo::findGrpIdByNameAndSet,
                flavorNameAliases = flavorNameAliases,
            )

        for ((deckName, deckFile) in deckFiles) {
            try {
                val cards = parseDecklist(deckFile.readText()).resolveCards(cardNameResolver::resolve)
                resolved.add(ResolvedDeck(deckName, cards))
            } catch (e: DecklistException) {
                e.errors.forEach { errors.add("  $deckName: $it") }
            }
        }

        if (errors.isNotEmpty()) {
            println("Card resolution errors:")
            errors.forEach { println(it) }
            error("Fix deck files before seeding (${errors.size} error(s))")
        }

        return resolved.mapTo(mutableSetOf()) { rd ->
            val deckId = UUID.nameUUIDFromBytes((deckIdPrefix + rd.name).toByteArray()).toString()
            val isBrawl = rd.cards.commandZone.isNotEmpty()
            val tileId =
                if (isBrawl) {
                    rd.cards.commandZone
                        .first()
                        .grpId
                } else {
                    rd.cards.mainDeck
                        .first()
                        .grpId
                }
            val deck =
                Deck(
                    id = DeckId(deckId),
                    playerId = ownerId,
                    name = rd.name,
                    format = if (isBrawl) Format.Brawl else Format.Standard,
                    tileId = tileId,
                    mainDeck = rd.cards.mainDeck,
                    sideboard = rd.cards.sideboard,
                    commandZone = rd.cards.commandZone,
                    companions = rd.cards.companions,
                )
            store.save(deck)
            val total = rd.cards.mainDeck.sumOf { it.quantity } + rd.cards.commandZone.sumOf { it.quantity }
            val suffix = if (isBrawl) " [Brawl]" else ""
            println("Seeded: ${rd.name} ($total cards)$suffix")
            deck.id
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

/** Remove persisted spectator decks that no longer belong to the declared rotation. */
internal fun reconcileSpectatorDecks(
    decks: DeckRepository,
    activeDeckIds: Set<DeckId>,
) {
    decks
        .findAllForPlayer(SystemPlayers.SPECTATOR)
        .filter { it.id !in activeDeckIds }
        .forEach { decks.delete(it.id) }
}
