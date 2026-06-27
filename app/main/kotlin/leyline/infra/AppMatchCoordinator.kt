package leyline.infra

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import leyline.bridge.bootstrap.CardEntry
import leyline.bridge.bootstrap.DeckConverter
import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.DraftStatus
import leyline.domain.Format
import leyline.domain.PlayerId
import leyline.domain.repo.DraftSessionRepository
import leyline.domain.service.CourseService
import leyline.domain.service.DeckService
import leyline.domain.service.MatchCoordinator
import leyline.native.frontdoor.wire.DeckWireBuilder
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
    private val nameByGrpId: (Int) -> String? = { null },
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

    override fun resolveRandomDeckPairJson(): Pair<String, String>? {
        val targetFormat = if (selectedEventName?.contains("Brawl", ignoreCase = true) == true) Format.Brawl else Format.Standard
        val decks = deckService.listForPlayer(playerId).filter { it.format == targetFormat }
        if (decks.isEmpty()) return null

        val shuffled = decks.shuffled()
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
        return cardsToJson(seat1.mainDeck, seat1.sideboard, seat1.commandZone) to
            cardsToJson(seat2.mainDeck, seat2.sideboard, seat2.commandZone)
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

    override fun resolveOpponentDeckJson(eventName: String): String? = resolveOpponentDeckJson(playerId, eventName)

    fun resolveOpponentDeckJson(
        playerId: PlayerId,
        eventName: String,
    ): String? {
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
        return cardsToJson(mainDeck, sideboard = emptyList())
    }

    fun configureCourseMatch(
        matchId: String,
        playerId: PlayerId,
        eventName: String,
    ): Pair<String, String> {
        val course = courseService.getCourse(playerId, eventName) ?: missingCourseState("No course for $eventName")
        val deck = course.deck ?: missingCourseState("No course deck for $eventName")
        val seat2Json = resolveOpponentDeckJson(playerId, eventName) ?: missingCourseState("No pod opponent for $eventName")
        courseByMatchId[matchId] = playerId to eventName
        return DeckConverter.toDeckText(deck.mainDeck.toCardEntries(), deck.sideboard.toCardEntries(), nameByGrpId = nameByGrpId) to
            jsonCardsToDeckText(seat2Json)
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
        log.info("Match result recorded: matchId={} player={} event={} won={}", matchId, player.value, event, won)
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

    private fun jsonCardsToDeckText(cardsJson: String): String {
        val obj =
            kotlinx.serialization.json.Json
                .parseToJsonElement(cardsJson)
                .jsonObject
        val main = parseCards(obj["MainDeck"]?.jsonArray)
        val sideboard = parseCards(obj["Sideboard"]?.jsonArray)
        return DeckConverter.toDeckText(main.toCardEntries(), sideboard.toCardEntries(), nameByGrpId = nameByGrpId)
    }

    private fun List<DeckCard>.toCardEntries(): List<CardEntry> = map { CardEntry(it.grpId, it.quantity) }

    private fun parseCards(array: kotlinx.serialization.json.JsonArray?): List<DeckCard> =
        array.orEmpty().map { element ->
            val obj = element.jsonObject
            DeckCard(
                grpId = obj["cardId"]!!.jsonPrimitive.int,
                quantity = obj["quantity"]!!.jsonPrimitive.int,
            )
        }
}
