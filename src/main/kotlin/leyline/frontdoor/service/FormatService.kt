package leyline.frontdoor.service

import forge.game.GameFormat
import forge.model.FModel
import org.slf4j.LoggerFactory

/**
 * Maps Arena event formats to Forge [GameFormat] and validates decks.
 * Requires card database initialization before [resolve]/[validateDeck].
 */
object FormatService {
    private val log = LoggerFactory.getLogger(FormatService::class.java)

    /** Arena format names that have no Forge equivalent — skip validation. */
    private val UNMAPPED_FORMATS = setOf("Timeless", "Alchemy")

    /** Arena → Forge renames (after stripping "Traditional" prefix). */
    private val ARENA_TO_FORGE = mapOf(
        "Explorer" to "Pioneer",
    )

    /**
     * Map Arena deckSelectFormat string to Forge format name, or null if unmapped.
     * "TraditionalStandard" → "Standard", "Explorer" → "Pioneer".
     */
    fun mapArenaFormat(arenaFormat: String): String? {
        val base = arenaFormat.removePrefix("Traditional")
        if (base in UNMAPPED_FORMATS) return null
        return ARENA_TO_FORGE[base] ?: base
    }

    /**
     * Resolve a format name to the engine [GameFormat].
     * Throws if the format name is non-blank but not found -- that's a bug in our mapping.
     */
    fun resolve(formatId: String?): GameFormat? {
        if (formatId.isNullOrBlank()) return null
        val collection = FModel.getFormats()
        return collection.get(formatId)
            ?: collection.get(formatId.replaceFirstChar { it.uppercase() })
            ?: error("Forge format '$formatId' not found -- check EventRegistry/FormatService mapping")
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
