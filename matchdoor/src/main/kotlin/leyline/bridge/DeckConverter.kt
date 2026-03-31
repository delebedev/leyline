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
     * When [commandZone] is non-empty, emits a `[Commander]` section header
     * before the main deck. [DeckLoader.parseDeckList] routes cards under
     * `[Commander]` to [forge.deck.DeckSection.Commander], which
     * [RegisteredPlayer.forCommander] uses to place them in the command zone.
     *
     * @param nameByGrpId lookup function: grpId → card name (null if unknown)
     */
    fun toDeckText(
        mainDeck: List<CardEntry>,
        sideboard: List<CardEntry>,
        commandZone: List<CardEntry> = emptyList(),
        nameByGrpId: (Int) -> String?,
    ): String = buildString {
        if (commandZone.isNotEmpty()) {
            appendLine("[Commander]")
            for (card in commandZone) {
                val name = nameByGrpId(card.grpId)
                if (name != null) {
                    appendLine("${card.quantity} $name")
                } else {
                    log.warn("DeckConverter: unknown commander grpId {}", card.grpId)
                }
            }
            appendLine("[Deck]")
        }
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
        commandZone: List<CardEntry> = emptyList(),
        nameByGrpId: (Int) -> String?,
    ): forge.deck.Deck {
        val text = toDeckText(mainDeck, sideboard, commandZone, nameByGrpId)
        return DeckLoader.parseDeckList(text)
    }
}
