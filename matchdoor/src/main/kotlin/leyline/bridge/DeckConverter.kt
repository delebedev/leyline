package leyline.bridge

import org.slf4j.LoggerFactory

/** Card entry for deck conversion: grpId + quantity. */
data class CardEntry(val grpId: Int, val quantity: Int)

object DeckConverter {
    private val log = LoggerFactory.getLogger(DeckConverter::class.java)

    /**
     * Convert card entry lists to Forge-parseable deck text.
     * Unknown grpIds are skipped with a warning.
     *
     * @param nameByGrpId lookup function: grpId → card name (null if unknown)
     */
    fun toDeckText(
        mainDeck: List<CardEntry>,
        sideboard: List<CardEntry>,
        nameByGrpId: (Int) -> String?,
    ): String = buildString {
        for (card in mainDeck) {
            val name = nameByGrpId(card.grpId)
            if (name != null) {
                appendLine("${card.quantity} $name")
            } else {
                log.warn("DeckConverter: unknown grpId {}", card.grpId)
            }
        }
        if (sideboard.isNotEmpty()) {
            appendLine("Sideboard")
            for (card in sideboard) {
                val name = nameByGrpId(card.grpId)
                if (name != null) {
                    appendLine("${card.quantity} $name")
                } else {
                    log.warn("DeckConverter: unknown sideboard grpId {}", card.grpId)
                }
            }
        }
    }

    /**
     * Convert card entry lists to a Forge Deck.
     * Requires Forge card database to be initialized.
     *
     * @param nameByGrpId lookup function: grpId → card name (null if unknown)
     */
    fun toForgeDeck(
        mainDeck: List<CardEntry>,
        sideboard: List<CardEntry>,
        nameByGrpId: (Int) -> String?,
    ): forge.deck.Deck {
        val text = toDeckText(mainDeck, sideboard, nameByGrpId)
        return DeckLoader.parseDeckList(text)
    }
}
