package leyline.frontdoor.service

import leyline.bridge.DeckConverter
import leyline.bridge.DeckLoader
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.MatchInfo
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.DeckRepository
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Creates matches from event + deck selection, returning a [MatchInfo] the FD handler
 * pushes to the client as a MatchCreated message.
 *
 * Deck legality is validated against the event's Forge format (looked up via [EventRegistry]).
 * Events flagged `SkipDeckValidation` in the registry bypass this check entirely — used for
 * formats Forge doesn't model yet (e.g. Alchemy) so the client can still queue.
 */
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
            val deckText = DeckConverter.toDeckText(deck.mainDeck, deck.sideboard, nameByGrpId)
            if (deckText.isBlank()) {
                log.warn("Cannot validate deck '{}' — card name resolver not wired", deck.name)
            } else {
                val forgeDeck = DeckLoader.parseDeckList(deckText)
                val problem = FormatService.validateDeck(forgeDeck, forgeFormat)
                if (problem != null) {
                    throw IllegalArgumentException("Deck '${deck.name}' not legal in $forgeFormat: $problem")
                }
                log.info("Deck '{}' validated for format {}", deck.name, forgeFormat)
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
