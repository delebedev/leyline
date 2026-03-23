package leyline.bridge

import forge.game.GameFormat
import forge.model.FModel
import org.slf4j.LoggerFactory

/**
 * Resolves Forge [GameFormat] by name and validates decks against formats.
 *
 * Arena → Forge format mapping (e.g. "TraditionalStandard" → "Standard") is
 * handled by [leyline.frontdoor.service.EventRegistry.mapArenaFormat] — this
 * class only deals with resolved Forge format names.
 *
 * Requires card database initialization before [resolve]/[validateDeck].
 */
object FormatService {
    @Suppress("UnusedPrivateProperty")
    private val log = LoggerFactory.getLogger(FormatService::class.java)

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
