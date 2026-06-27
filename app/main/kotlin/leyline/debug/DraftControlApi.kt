package leyline.debug

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.Course
import leyline.domain.CourseDeck
import leyline.domain.CourseDeckSummary
import leyline.domain.CourseModule
import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.DraftSession
import leyline.domain.DraftStatus
import leyline.domain.PlayerId
import leyline.domain.service.CourseService
import leyline.domain.service.DraftService
import leyline.domain.service.EventRegistry
import leyline.infra.AppMatchCoordinator
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID

/** Server-to-server draft/course control API for web clients. */
class DraftControlApi(
    private val draftServiceProvider: () -> DraftService?,
    private val courseServiceProvider: () -> CourseService?,
    private val matchCoordinatorProvider: () -> AppMatchCoordinator?,
    private val runtimeMatchConfigs: RuntimeMatchConfigRegistry?,
    private val controlToken: String?,
) {
    private val log = LoggerFactory.getLogger(DraftControlApi::class.java)
    private val json =
        Json {
            prettyPrint = false
            encodeDefaults = true
        }

    fun mount(server: HttpServer) {
        server.createContext("/api/draft/start") { ex -> route(ex, setOf("POST"), ::serveStart) }
        server.createContext("/api/draft/pick") { ex -> route(ex, setOf("POST"), ::servePick) }
        server.createContext("/api/draft/status") { ex -> route(ex, setOf("GET"), ::serveStatus) }
        server.createContext("/api/draft/deck") { ex -> route(ex, setOf("POST"), ::serveDeck) }
        server.createContext("/api/draft/play") { ex -> route(ex, setOf("POST"), ::servePlay) }
        server.createContext("/api/draft") { ex -> route(ex, setOf("DELETE"), ::serveDrop) }
    }

    private fun route(
        ex: HttpExchange,
        methods: Set<String>,
        handler: (HttpExchange) -> Unit,
    ) {
        try {
            if (!authorize(ex)) return
            if (ex.requestMethod !in methods) {
                ex.sendResponseHeaders(405, -1)
                ex.close()
                return
            }
            handler(ex)
        } catch (e: IllegalArgumentException) {
            respond(ex, 400, "text/plain", e.message ?: "Bad request")
        } catch (t: Throwable) {
            log.error("{} error: {}", ex.requestURI.path, t.message, t)
            respond(ex, 500, "text/plain", "Error: ${t.message}")
        }
    }

    private fun serveStart(ex: HttpExchange) {
        val request = readBody<StartDraftRequest>(ex)
        val playerId = request.playerId.toPlayerId()
        val courseService = courseService(ex) ?: return
        val draftService = draftService(ex) ?: return
        val existingCourse = courseService.getCourse(playerId, request.eventName)
        if (EventRegistry.isDraft(request.eventName) && existingCourse?.module == CourseModule.Complete) {
            draftService.drop(playerId, request.eventName)
        }
        courseService.join(playerId, request.eventName)
        respondJson(ex, sessionView(draftService.startDraft(playerId, request.eventName)))
    }

    private fun servePick(ex: HttpExchange) {
        val request = readBody<PickDraftRequest>(ex)
        val playerId = request.playerId.toPlayerId()
        val draftService = draftService(ex) ?: return
        val courseService = courseService(ex) ?: return
        val session = draftService.pick(playerId, request.eventName, request.cardId, request.packNumber, request.pickNumber)
        if (session.status == DraftStatus.Completed) {
            val collationId = EventRegistry.findEvent(request.eventName)?.collationId ?: 0
            courseService.completeDraft(playerId, request.eventName, session.pickedCards, collationId)
        }
        respondJson(ex, sessionView(session))
    }

    private fun serveStatus(ex: HttpExchange) {
        val playerId = requiredQuery(ex, "playerId").toPlayerId()
        val eventName = requiredQuery(ex, "eventName")
        val draftService = draftService(ex) ?: return
        val session =
            draftService.getStatus(playerId, eventName) ?: run {
                respond(ex, 404, "text/plain", "Draft session not found")
                return
            }
        respondJson(ex, sessionView(session))
    }

    private fun serveDrop(ex: HttpExchange) {
        val playerId = requiredQuery(ex, "playerId").toPlayerId()
        val eventName = requiredQuery(ex, "eventName")
        val draftService = draftService(ex) ?: return
        val courseService = courseService(ex) ?: return
        draftService.drop(playerId, eventName)
        courseService.drop(playerId, eventName)
        respond(ex, 200, "text/plain", "Draft dropped")
    }

    private fun serveDeck(ex: HttpExchange) {
        val request = readBody<SubmitDeckRequest>(ex)
        val playerId = request.playerId.toPlayerId()
        val deckId = DeckId(request.deckId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
        val mainDeck = request.mainDeck.map { DeckCard(it.grpId, it.quantity) }
        val sideboard = request.sideboard.map { DeckCard(it.grpId, it.quantity) }
        val course =
            courseService(ex)?.setDeck(
                playerId = playerId,
                eventName = request.eventName,
                deck = CourseDeck(deckId, mainDeck, sideboard),
                summary =
                    CourseDeckSummary(
                        deckId = deckId,
                        name = request.name.ifBlank { "Draft Deck" },
                        tileId = mainDeck.firstOrNull()?.grpId ?: 0,
                        format = "Limited",
                    ),
            ) ?: return
        respondJson(ex, courseView(course))
    }

    private fun servePlay(ex: HttpExchange) {
        val request = readBody<PlayDraftRequest>(ex)
        val matchId = UUID.randomUUID().toString()
        val coordinator = matchCoordinator(ex) ?: return
        val registry =
            runtimeMatchConfigs ?: run {
                respond(ex, 503, "text/plain", "Runtime match config registry unavailable")
                return
            }
        val (seat1, seat2) = coordinator.configureCourseMatch(matchId, request.playerId.toPlayerId(), request.eventName)
        val launch = registry.configure(RuntimeMatchConfig(matchId = matchId, seat1Deck = seat1, seat2Deck = seat2))
        respondJson(ex, DraftPlayResponse(matchId = launch.matchId, wireMatchId = launch.wireMatchId))
    }

    private fun draftService(ex: HttpExchange): DraftService? =
        draftServiceProvider() ?: run {
            respond(ex, 503, "text/plain", "Draft service unavailable")
            null
        }

    private fun courseService(ex: HttpExchange): CourseService? =
        courseServiceProvider() ?: run {
            respond(ex, 503, "text/plain", "Course service unavailable")
            null
        }

    private fun matchCoordinator(ex: HttpExchange): AppMatchCoordinator? =
        matchCoordinatorProvider() ?: run {
            respond(ex, 503, "text/plain", "Match coordinator unavailable")
            null
        }

    private inline fun <reified T> readBody(ex: HttpExchange): T {
        val body =
            ex.requestBody
                .bufferedReader()
                .readText()
                .trim()
        require(body.isNotEmpty()) { "Body is required" }
        return json.decodeFromString(body)
    }

    private fun requiredQuery(
        ex: HttpExchange,
        key: String,
    ): String = queryParam(ex, key)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$key is required")

    private fun queryParam(
        ex: HttpExchange,
        key: String,
    ): String? =
        ex.requestURI.query
            ?.split("&")
            ?.firstNotNullOfOrNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.firstOrNull() == key) parts.getOrNull(1)?.urlDecode().orEmpty() else null
            }

    private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8)

    private fun String.toPlayerId(): PlayerId {
        require(isNotBlank()) { "playerId is required" }
        return PlayerId(this)
    }

    private fun authorize(ex: HttpExchange): Boolean {
        val expected = controlToken?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val actual =
            ex.requestHeaders
                .getFirst("Authorization")
                ?.trim()
                ?.removePrefix("Bearer ")
                ?.takeIf { it.isNotEmpty() }
        if (actual != null && actual.constantTimeEquals(expected)) return true
        respond(ex, 401, "text/plain", "Unauthorized")
        return false
    }

    private fun String.constantTimeEquals(other: String): Boolean =
        MessageDigest.isEqual(toByteArray(Charsets.UTF_8), other.toByteArray(Charsets.UTF_8))

    private fun sessionView(session: DraftSession): DraftSessionView =
        DraftSessionView(
            eventName = session.eventName,
            status = session.status.name,
            packNumber = session.packNumber,
            pickNumber = session.pickNumber,
            draftPack = session.draftPack,
            pickedCards = session.pickedCards,
        )

    private fun courseView(course: Course): CourseView =
        CourseView(
            eventName = course.eventName,
            module = course.module.name,
            wins = course.wins,
            losses = course.losses,
            cardPool = course.cardPool,
            deckId = course.deck?.deckId?.value,
        )

    private fun respondJson(
        ex: HttpExchange,
        body: DraftSessionView,
    ) = respond(ex, 200, "application/json; charset=utf-8", json.encodeToString(DraftSessionView.serializer(), body))

    private fun respondJson(
        ex: HttpExchange,
        body: CourseView,
    ) = respond(ex, 200, "application/json; charset=utf-8", json.encodeToString(CourseView.serializer(), body))

    private fun respondJson(
        ex: HttpExchange,
        body: DraftPlayResponse,
    ) = respond(ex, 200, "application/json; charset=utf-8", json.encodeToString(DraftPlayResponse.serializer(), body))

    private fun respond(
        ex: HttpExchange,
        code: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}

@Serializable
private data class StartDraftRequest(
    val playerId: String,
    val eventName: String,
)

@Serializable
private data class PickDraftRequest(
    val playerId: String,
    val eventName: String,
    val cardId: Int,
    val packNumber: Int,
    val pickNumber: Int,
)

@Serializable
private data class SubmitDeckRequest(
    val playerId: String,
    val eventName: String,
    val mainDeck: List<DeckCardRequest>,
    val sideboard: List<DeckCardRequest> = emptyList(),
    val name: String,
    val deckId: String? = null,
)

@Serializable
private data class PlayDraftRequest(
    val playerId: String,
    val eventName: String,
)

@Serializable
private data class DeckCardRequest(
    val grpId: Int,
    val quantity: Int,
)

@Serializable
private data class DraftSessionView(
    val eventName: String,
    val status: String,
    val packNumber: Int,
    val pickNumber: Int,
    val draftPack: List<Int>,
    val pickedCards: List<Int>,
)

@Serializable
private data class CourseView(
    val eventName: String,
    val module: String,
    val wins: Int,
    val losses: Int,
    val cardPool: List<Int>,
    val deckId: String?,
)

@Serializable
private data class DraftPlayResponse(
    val matchId: String,
    val wireMatchId: String,
)
