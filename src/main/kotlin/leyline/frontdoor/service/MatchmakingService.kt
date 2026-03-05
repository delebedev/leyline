package leyline.frontdoor.service

import leyline.bridge.DeckConverter
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.MatchInfo
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.DeckRepository
import org.slf4j.LoggerFactory
import java.util.UUID

class MatchmakingService(
    private val decks: DeckRepository,
    private val matchDoorHost: String,
    private val matchDoorPort: Int,
    private val nameByGrpId: (Int) -> String? = { null },
) {
    private val log = LoggerFactory.getLogger(MatchmakingService::class.java)

    /**
     * Create a match for any event. Validates deck legality against the event's format.
     */
    fun startMatch(playerId: PlayerId, deckId: DeckId, eventName: String): MatchInfo {
        val deck = decks.findById(deckId)
            ?: throw IllegalArgumentException("Deck not found: ${deckId.value}")

        val forgeFormat = EventRegistry.forgeFormatFor(eventName)
        if (forgeFormat != null) {
            try {
                val forgeDeck = DeckConverter.toForgeDeck(deck.mainDeck, deck.sideboard, nameByGrpId)
                val problem = FormatService.validateDeck(forgeDeck, forgeFormat)
                if (problem != null) {
                    log.warn("Deck '{}' not legal in {}: {}", deck.name, forgeFormat, problem)
                } else {
                    log.info("Deck '{}' validated for format {}", deck.name, forgeFormat)
                }
            } catch (e: Exception) {
                // Forge card DB not initialized or card names unresolvable — skip validation
                log.warn("Skipping deck validation for format {}: {}", forgeFormat, e.message)
            }
        }

        return MatchInfo(
            matchId = UUID.randomUUID().toString(),
            host = matchDoorHost,
            port = matchDoorPort,
            eventName = eventName,
        )
    }

    /** Convenience: delegates to [startMatch]. */
    fun startAiMatch(playerId: PlayerId, deckId: DeckId, eventName: String = "AIBotMatch"): MatchInfo =
        startMatch(playerId, deckId, eventName)
}
