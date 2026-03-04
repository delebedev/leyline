package leyline.cli

import leyline.game.CardDb
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Scans deck txt files at boot, resolves card names to grpIds via [leyline.game.CardDb],
 * and provides deck data for FrontDoor StartHook injection + match-time lookup.
 */
object DeckCatalog {
    private val log = LoggerFactory.getLogger(DeckCatalog::class.java)

    data class DeckCard(val cardId: Int, val quantity: Int)

    data class CatalogDeck(
        val deckId: String,
        val name: String,
        val fileName: String,
        val mainDeck: List<DeckCard>,
        val sideboard: List<DeckCard>,
        val tileId: Int,
    )

    private val decks = mutableListOf<CatalogDeck>()
    private val byId = mutableMapOf<String, CatalogDeck>()

    fun all(): List<CatalogDeck> = decks.toList()
    fun findById(deckId: String): CatalogDeck? = byId[deckId]

    /**
     * Scan [decksDir] for `*.txt` files, parse each, resolve card names via CardDb.
     * Returns number of decks loaded. Skips files with unresolvable cards (with warnings).
     */
    fun scan(decksDir: File): Int {
        decks.clear()
        byId.clear()

        if (!decksDir.isDirectory) {
            log.warn("DeckCatalog: decks dir not found: {}", decksDir)
            return 0
        }

        val files = decksDir.listFiles { f -> f.extension == "txt" }?.sortedBy { it.name } ?: return 0
        for (file in files) {
            val deck = parseFile(file)
            if (deck != null) {
                decks.add(deck)
                byId[deck.deckId] = deck
            }
        }
        log.info("DeckCatalog: loaded {} deck(s) from {}", decks.size, decksDir)
        return decks.size
    }

    private fun parseFile(file: File): CatalogDeck? {
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val mainDeck = mutableListOf<DeckCard>()
        val sideboard = mutableListOf<DeckCard>()
        var inSideboard = false
        var skipped = 0

        for (line in lines) {
            if (line.equals("Deck", ignoreCase = true)) continue
            if (line.equals("Sideboard", ignoreCase = true)) {
                inSideboard = true
                continue
            }
            // Skip comment lines
            if (line.startsWith("//") || line.startsWith("#")) continue

            val match = DECK_LINE.matchEntire(line)
            if (match == null) {
                log.debug("DeckCatalog: skipping unparseable line in {}: {}", file.name, line)
                continue
            }

            val qty = match.groupValues[1].toInt()
            val cardName = match.groupValues[2].trim()
            val grpId = CardDb.lookupByName(cardName)
            if (grpId == null) {
                log.warn("DeckCatalog: unknown card '{}' in {}", cardName, file.name)
                skipped++
                continue
            }

            val card = DeckCard(cardId = grpId, quantity = qty)
            if (inSideboard) sideboard.add(card) else mainDeck.add(card)
        }

        if (mainDeck.isEmpty()) {
            log.warn("DeckCatalog: no valid cards in {}, skipping", file.name)
            return null
        }

        if (skipped > 0) {
            log.warn("DeckCatalog: {} skipped {} unresolvable card(s)", file.name, skipped)
        }

        val fileName = file.name
        val deckId = UUID.nameUUIDFromBytes(fileName.toByteArray()).toString()
        val name = fileName.removeSuffix(".txt").replace('-', ' ').replaceFirstChar { it.uppercase() }
        // tileId = grpId of first non-land card (heuristic: first card that isn't a basic land name)
        val tileId = mainDeck.firstOrNull { card ->
            val cardName = CardDb.getCardName(card.cardId)
            cardName != null && cardName !in BASIC_LAND_NAMES
        }?.cardId ?: mainDeck.first().cardId

        return CatalogDeck(
            deckId = deckId,
            name = name,
            fileName = fileName,
            mainDeck = mainDeck,
            sideboard = sideboard,
            tileId = tileId,
        )
    }

    private val DECK_LINE = Regex("""^(\d+)\s+(.+?)(?:\s+\([A-Z0-9]+\)\s+\d+)?${'$'}""")

    private val BASIC_LAND_NAMES = setOf("Plains", "Island", "Swamp", "Mountain", "Forest")
}
