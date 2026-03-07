package leyline.infra

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.service.CourseService
import leyline.frontdoor.service.DeckService
import leyline.frontdoor.service.MatchCoordinator
import leyline.frontdoor.wire.DeckWireBuilder
import org.slf4j.LoggerFactory

/**
 * Production [MatchCoordinator] — wired in [LeylineServer.startLocal].
 *
 * Absorbs the cross-BC deck resolution logic and shared @Volatile state
 * that previously lived as lambdas and fields in LeylineServer.
 */
class AppMatchCoordinator(
    private val playerId: PlayerId,
    private val deckService: DeckService,
    private val courseService: CourseService,
) : MatchCoordinator {

    private val log = LoggerFactory.getLogger(AppMatchCoordinator::class.java)

    @Volatile
    override var selectedDeckId: String? = null
        private set

    @Volatile
    override var selectedEventName: String? = null
        private set

    override fun selectDeck(deckId: String) {
        selectedDeckId = deckId
    }

    override fun selectEvent(eventName: String) {
        selectedEventName = eventName
    }

    override fun resolveDeckJson(deckId: String): String? {
        // 1. Constructed deck from repository
        deckService.getById(DeckId(deckId))?.let { return cardsToJson(it.mainDeck, it.sideboard) }

        // 2. Sealed/draft course deck
        val event = selectedEventName ?: return null
        val courseDeck = courseService.getCourse(playerId, event)?.deck ?: return null
        return cardsToJson(courseDeck.mainDeck, courseDeck.sideboard)
    }

    override fun resolveDeckJsonByName(name: String): String? {
        val deck = deckService.getByName(name) ?: return null
        return cardsToJson(deck.mainDeck, deck.sideboard)
    }

    override fun reportMatchResult(won: Boolean) {
        val event = selectedEventName ?: return
        courseService.recordMatchResult(playerId, event, won)
        log.info("Match result recorded: event={} won={}", event, won)
    }

    private fun cardsToJson(mainDeck: List<DeckCard>, sideboard: List<DeckCard>): String =
        buildJsonObject {
            put("MainDeck", DeckWireBuilder.cardsToJsonArray(mainDeck))
            put("Sideboard", DeckWireBuilder.cardsToJsonArray(sideboard))
        }.toString()
}
