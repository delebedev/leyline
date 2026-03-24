package leyline.frontdoor.service

import leyline.frontdoor.domain.DeckCard
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
 *
 * Deck validation is injected as a lambda to keep this module free of Forge engine
 * dependencies. The wiring layer ([leyline.infra.LeylineServer]) composes DeckConverter,
 * DeckLoader, and FormatService into the lambda.
 */
class MatchmakingService(
    private val decks: DeckRepository,
    val matchDoorHost: String,
    val matchDoorPort: Int,
    /**
     * Validate a deck against a format. Returns null if legal, error string if illegal.
     * Params: (mainDeck, sideboard, formatId).
     */
    private val validateDeck: ((List<DeckCard>, List<DeckCard>, String) -> String?)? = null,
    /**
     * Match ID generator. Allows app wiring to route selected events into custom
     * MD flows without coupling FD to engine details.
     */
    private val matchIdFactory: (String) -> String = { UUID.randomUUID().toString() },
) {
    private val log = LoggerFactory.getLogger(MatchmakingService::class.java)

    fun createMatchId(eventName: String): String = matchIdFactory(eventName)

    /**
     * Create a match for any event. Validates deck legality against the event's format.
     */
    fun startMatch(playerId: PlayerId, deckId: DeckId, eventName: String): MatchInfo {
        val deck = decks.findById(deckId)
            ?: throw IllegalArgumentException("Deck not found: ${deckId.value}")

        val forgeFormat = EventRegistry.forgeFormatFor(eventName)
        if (forgeFormat != null && validateDeck != null) {
            val problem = validateDeck.invoke(deck.mainDeck, deck.sideboard, forgeFormat)
            require(problem == null) { "Deck '${deck.name}' not legal in $forgeFormat: $problem" }
            log.info("Deck '{}' validated for format {}", deck.name, forgeFormat)
        }

        return MatchInfo(
            matchId = createMatchId(eventName),
            host = matchDoorHost,
            port = matchDoorPort,
            eventName = eventName,
        )
    }

    /** Create MatchInfo without deck validation — for sealed events where deck is in Course. */
    fun createMatchInfo(eventName: String): MatchInfo = MatchInfo(
        matchId = createMatchId(eventName),
        host = matchDoorHost,
        port = matchDoorPort,
        eventName = eventName,
    )

    /** Convenience: delegates to [startMatch]. */
    fun startAiMatch(@Suppress("UnusedParameter") playerId: PlayerId, deckId: DeckId, eventName: String = "AIBotMatch"): MatchInfo =
        startMatch(playerId, deckId, eventName)
}
