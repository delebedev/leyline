package leyline.infra

import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.DraftStatus
import leyline.domain.Format
import leyline.domain.PlayerId
import leyline.domain.deck.DeckCards
import leyline.domain.deck.toDeckCards
import leyline.domain.repo.DeckRepository
import leyline.domain.repo.DraftSessionRepository
import leyline.domain.service.CourseService
import leyline.domain.service.MatchCoordinator
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
    private val decks: DeckRepository,
    private val courseService: CourseService,
    private val draftRepo: DraftSessionRepository,
) : MatchCoordinator {
    private val log = LoggerFactory.getLogger(AppMatchCoordinator::class.java)
    private val opponentRotationByEvent = ConcurrentHashMap<String, AtomicInteger>()
    private val courseByMatchId = ConcurrentHashMap<String, Pair<PlayerId, String>>()

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

    override fun resolveDeckCards(deckId: String): DeckCards? {
        // 1. Constructed deck from repository
        decks.findById(DeckId(deckId))?.let {
            return it.toDeckCards()
        }

        // 2. Sealed/draft course deck (no command zone)
        val event = selectedEventName ?: return null
        val courseDeck = courseService.getCourse(playerId, event)?.deck ?: return null
        return DeckCards(courseDeck.mainDeck, courseDeck.sideboard)
    }

    override fun resolveFirstDeckCards(): DeckCards? {
        val first = decks.findAllForPlayer(playerId).firstOrNull() ?: return null
        log.info("Fallback deck: {} ({})", first.name, first.id.value)
        return first.toDeckCards()
    }

    override fun resolveDeckCardsByName(name: String): DeckCards? {
        if (name.equals("random", ignoreCase = true)) return resolveRandomDeckCards()

        val deck = decks.findByName(name) ?: return null
        return deck.toDeckCards()
    }

    override fun resolveRandomDeckCardsPair(): Pair<DeckCards, DeckCards>? {
        val targetFormat = if (selectedEventName?.contains("Brawl", ignoreCase = true) == true) Format.Brawl else Format.Standard
        val candidates = decks.findAllForPlayer(playerId).filter { it.format == targetFormat }
        if (candidates.isEmpty()) return null

        val shuffled = candidates.shuffled()
        val seat1 = shuffled[0]
        val seat2 = shuffled.drop(1).firstOrNull() ?: seat1
        log.info(
            "Random spectator decks: seat1={} ({}) seat2={} ({}) format={}",
            seat1.name,
            seat1.id.value,
            seat2.name,
            seat2.id.value,
            targetFormat,
        )
        return seat1.toDeckCards() to seat2.toDeckCards()
    }

    private fun resolveRandomDeckCards(): DeckCards? {
        val targetFormat = if (selectedEventName?.contains("Brawl", ignoreCase = true) == true) Format.Brawl else Format.Standard
        val candidates = decks.findAllForPlayer(playerId).filter { it.format == targetFormat }
        if (candidates.isEmpty()) return null

        val pool = candidates.filterNot { it.id.value == selectedDeckId }.ifEmpty { candidates }
        val deck = pool.random()
        log.info("Random AI deck: {} ({}) format={}", deck.name, deck.id.value, deck.format)
        return deck.toDeckCards()
    }

    override fun resolveOpponentDeckCards(eventName: String): DeckCards? = resolveOpponentDeckCards(playerId, eventName)

    fun resolveOpponentDeckCards(
        playerId: PlayerId,
        eventName: String,
    ): DeckCards? {
        val session = draftRepo.findByPlayerAndEvent(playerId, eventName) ?: return null
        if (session.status != DraftStatus.Completed) return null
        val pod = draftRepo.findPodResults(session.id)
        if (pod.isEmpty()) return null

        val rotationKey = "${playerId.value}:$eventName"
        val counter = opponentRotationByEvent.computeIfAbsent(rotationKey) { AtomicInteger(0) }
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
        return DeckCards(mainDeck)
    }

    fun configureCourseMatch(
        matchId: String,
        playerId: PlayerId,
        eventName: String,
    ): Pair<DeckCards, DeckCards> {
        val course = courseService.getCourse(playerId, eventName) ?: missingCourseState("No course for $eventName")
        val deck = course.deck ?: missingCourseState("No course deck for $eventName")
        // Draft events have a pod of bot decks from the draft; sealed events don't
        // (no bots were seated to draft alongside the player), so mirror the
        // player's own deck as the opponent — same fallback the native Match Door
        // uses in MatchConnection.resolveSeat2Deck when no pod is available.
        val seat2 = resolveOpponentDeckCards(playerId, eventName) ?: DeckCards(deck.mainDeck, deck.sideboard)
        courseByMatchId[matchId] = playerId to eventName
        return DeckCards(deck.mainDeck, deck.sideboard) to seat2
    }

    private fun missingCourseState(message: String): Nothing = throw IllegalArgumentException(message)

    override fun reportMatchResult(won: Boolean) {
        val event = selectedEventName ?: return
        courseService.recordMatchResult(playerId, event, won)
        log.info("Match result recorded: event={} won={}", event, won)
    }

    override fun reportMatchResult(
        matchId: String,
        won: Boolean,
    ) {
        val routed = courseByMatchId.remove(matchId)
        if (routed == null) {
            reportMatchResult(won)
            return
        }
        val (player, event) = routed
        courseService.recordMatchResult(player, event, won)
        log.info("Match result recorded: matchId={} event={} won={}", matchId, event, won)
    }
}
