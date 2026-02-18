package forge.nexus.game

import org.slf4j.LoggerFactory

/**
 * Provides precon decks and deals shuffled hands with per-card grpIds.
 *
 * Each seat gets a fixed 60-card deck. [dealHand] shuffles and draws 7,
 * resolving card names → client grpIds via [CardDb.lookupByName].
 * Cards not found in the client DB get a fallback grpId of 0 (face-down).
 */
class DeckProvider {
    private val log = LoggerFactory.getLogger(DeckProvider::class.java)

    companion object {
        /** Fallback grpId for cards not in client DB (renders face-down). */
        const val FALLBACK_GRPID = 0

        // Known client grpIds for precon lands (FDN set)
        private const val GRPID_FOREST = 95189
        private const val GRPID_PLAINS = 95181

        /** Seat 1 — Green Stompy */
        val SEAT1_DECK: List<Pair<String, Int>> = listOf(
            "Llanowar Elves" to 20,
            "Elvish Mystic" to 4,
            "Giant Growth" to 4,
            "Forest" to 32,
        )

        /** Seat 2 — White Flyers */
        val SEAT2_DECK: List<Pair<String, Int>> = listOf(
            "Serra Angel" to 20,
            "Pacifism" to 4,
            "Glorious Anthem" to 4,
            "Plains" to 32,
        )
    }

    /** Expand deck definition into a flat card-name list. */
    private fun expandDeck(seatId: Int): List<String> {
        val def = if (seatId == 1) SEAT1_DECK else SEAT2_DECK
        return def.flatMap { (name, count) -> List(count) { name } }
    }

    /** Resolve a card name to its client grpId, with fallback. */
    private fun resolveGrpId(cardName: String): Int {
        val grpId = CardDb.lookupByName(cardName)
        if (grpId == null) {
            log.warn("Card '{}' not found in client DB, using fallback grpId={}", cardName, FALLBACK_GRPID)
            return FALLBACK_GRPID
        }
        return grpId
    }

    /** Shuffle the seat's deck and draw 7 cards, returning their grpIds. */
    fun dealHand(seatId: Int): List<Int> {
        val deck = expandDeck(seatId).shuffled()
        return deck.take(7).map { resolveGrpId(it) }
    }

    /** Full 60-card deck as grpIds (for [StateMapper.buildDeckMessage]). */
    fun getDeckGrpIds(seatId: Int): List<Int> = expandDeck(seatId).map { resolveGrpId(it) }
}
