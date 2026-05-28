package leyline.infra

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.DraftStatus
import leyline.frontdoor.domain.Format
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.DraftSessionRepository
import leyline.frontdoor.service.CourseService
import leyline.frontdoor.service.DeckService
import leyline.frontdoor.service.MatchCoordinator
import leyline.frontdoor.wire.DeckWireBuilder
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    private val draftRepo: DraftSessionRepository,
) : MatchCoordinator {
    private val log = LoggerFactory.getLogger(AppMatchCoordinator::class.java)
    private val opponentRotationByEvent = ConcurrentHashMap<String, AtomicInteger>()

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
        deckService.getById(DeckId(deckId))?.let {
            return cardsToJson(it.mainDeck, it.sideboard, it.commandZone)
        }

        // 2. Sealed/draft course deck (no command zone)
        val event = selectedEventName ?: return null
        val courseDeck = courseService.getCourse(playerId, event)?.deck ?: return null
        return cardsToJson(courseDeck.mainDeck, courseDeck.sideboard)
    }

    override fun resolveFirstDeck(): String? {
        val first = deckService.listForPlayer(playerId).firstOrNull() ?: return null
        log.info("Fallback deck: {} ({})", first.name, first.id.value)
        return cardsToJson(first.mainDeck, first.sideboard)
    }

    override fun resolveDeckJsonByName(name: String): String? {
        if (name.equals("random", ignoreCase = true)) return resolveRandomDeckJson()

        val deck = deckService.getByName(name) ?: return null
        return cardsToJson(deck.mainDeck, deck.sideboard, deck.commandZone)
    }

    private fun resolveRandomDeckJson(): String? {
        val targetFormat = if (selectedEventName?.contains("Brawl", ignoreCase = true) == true) Format.Brawl else Format.Standard
        val decks = deckService.listForPlayer(playerId).filter { it.format == targetFormat }
        if (decks.isEmpty()) return null

        val candidates = decks.filterNot { it.id.value == selectedDeckId }.ifEmpty { decks }
        val deck = candidates.random()
        log.info("Random AI deck: {} ({}) format={}", deck.name, deck.id.value, deck.format)
        return cardsToJson(deck.mainDeck, deck.sideboard, deck.commandZone)
    }

    override fun resolveOpponentDeckJson(eventName: String): String? {
        val session = draftRepo.findByPlayerAndEvent(playerId, eventName) ?: return null
        if (session.status != DraftStatus.Completed) return null
        val pod = draftRepo.findPodResults(session.id)
        if (pod.isEmpty()) return null

        val counter = opponentRotationByEvent.computeIfAbsent(eventName) { AtomicInteger(0) }
        val rotation = Math.floorMod(counter.getAndIncrement(), pod.size)
        val botDeck = pod[rotation]
        log.info(
            "Pod-bot opponent: event={} seat={} cards={}",
            eventName,
            rotation + 1,
            botDeck.size,
        )

        val mainDeck =
            botDeck
                .groupingBy { it }
                .eachCount()
                .map { DeckCard(it.key, it.value) }
        return cardsToJson(mainDeck, sideboard = emptyList())
    }

    override fun reportMatchResult(won: Boolean) {
        val event = selectedEventName ?: return
        courseService.recordMatchResult(playerId, event, won)
        log.info("Match result recorded: event={} won={}", event, won)
    }

    private fun cardsToJson(
        mainDeck: List<DeckCard>,
        sideboard: List<DeckCard>,
        commandZone: List<DeckCard> = emptyList(),
    ): String =
        buildJsonObject {
            put("MainDeck", DeckWireBuilder.cardsToJsonArray(mainDeck))
            put("Sideboard", DeckWireBuilder.cardsToJsonArray(sideboard))
            if (commandZone.isNotEmpty()) {
                put("CommandZone", DeckWireBuilder.cardsToJsonArray(commandZone))
            }
        }.toString()
}
