package leyline.webdoor

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import kotlinx.serialization.json.Json
import leyline.domain.Course
import leyline.domain.CourseDeck
import leyline.domain.CourseDeckSummary
import leyline.domain.CourseModule
import leyline.domain.Deck
import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.DraftSession
import leyline.domain.DraftStatus
import leyline.domain.Format
import leyline.domain.PlayerId
import leyline.domain.service.CollectionService
import leyline.domain.service.CourseService
import leyline.domain.service.DeckService
import leyline.domain.service.DraftService
import leyline.domain.service.EventRegistry
import java.util.UUID

data class WebDoorServices(
    val draftService: DraftService,
    val courseService: CourseService,
    val deckService: DeckService,
    val collectionService: CollectionService,
    val matchLauncher: WebMatchLauncher,
    val greRelay: WebGreRelay = InProcessWebGreRelay(),
    val authService: WebAuthService = WebAuthService(InMemoryWebAuthStore(), DevEmailSender()),
)

interface WebMatchLauncher {
    fun launchGreMatch(
        playerId: PlayerId?,
        request: GreStartRequest,
    ): DraftPlayResponse

    fun launchCourseMatch(
        playerId: PlayerId,
        eventName: String,
    ): DraftPlayResponse
}

fun Application.installWebDoor(services: WebDoorServices) {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true })
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, cause.message ?: "Bad request")
        }
        exception<UnauthorizedPlayer> { _, _ -> }
    }
    install(WebSockets)
    routing {
        webSocket("/gre") {
            val matchId = call.request.queryParameters["matchId"]?.takeIf { it.isNotBlank() }
            if (matchId == null) {
                close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.CANNOT_ACCEPT, "matchId is required"))
                return@webSocket
            }
            services.greRelay.attach(matchId, this)
        }
        get("/openapi.json") {
            call.respondText(WebDoorOpenApi.generate(), contentType = io.ktor.http.ContentType.Application.Json)
        }
        route("/api") {
            installAuthRoutes(services)
            post("/gre/start") {
                call.respond(services.matchLauncher.launchGreMatch(call.authenticatedPlayerId(services), call.receive()))
            }
            post("/public/gre/start") {
                call.respond(services.matchLauncher.launchGreMatch(null, call.receive<GreStartRequest>().copy(spectatorMode = true)))
            }
            get("/collection") {
                val playerId = call.ownedPlayerId(services, call.request.queryParameters["playerId"])
                call.respond(
                    CollectionView(
                        services.collectionService
                            .getCollection(playerId)
                            .keys
                            .sorted(),
                    ),
                )
            }
            get("/cards/metadata") { call.respond(CardMetadataView()) }
            route("/courses") {
                get {
                    val playerId = call.ownedPlayerId(services, call.request.queryParameters["playerId"])
                    call.respond(services.courseService.getCoursesForPlayer(playerId).map(::courseView))
                }
            }
            route("/decks") {
                get {
                    val playerId = call.ownedPlayerId(services, call.request.queryParameters["playerId"])
                    call.respond(services.deckService.listForPlayer(playerId).map(::deckView))
                }
                post {
                    val request = call.receive<CreateDeckRequest>()
                    val playerId = call.ownedPlayerId(services, request.playerId)
                    val deck = request.toDeck(playerId)
                    services.deckService.save(deck)
                    call.respond(deckView(deck))
                }
                get("/{deckId}") {
                    val deck = services.deckService.getById(DeckId(call.parameters["deckId"].orEmpty()))
                    if (deck == null) call.respond(HttpStatusCode.NotFound) else call.respond(deckView(deck))
                }
                delete("/{deckId}") {
                    services.deckService.delete(DeckId(call.parameters["deckId"].orEmpty()))
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            installDraftRoutes(services)
        }
    }
}

private fun Route.installDraftRoutes(services: WebDoorServices) {
    route("/draft") {
        post("/start") {
            val request = call.receive<StartDraftRequest>()
            val playerId = call.ownedPlayerId(services, request.playerId)
            val existingCourse = services.courseService.getCourse(playerId, request.eventName)
            if (EventRegistry.isDraft(request.eventName) && existingCourse?.module == CourseModule.Complete) {
                services.draftService.drop(playerId, request.eventName)
            }
            services.courseService.join(playerId, request.eventName)
            call.respond(sessionView(services.draftService.startDraft(playerId, request.eventName)))
        }
        post("/pick") {
            val request = call.receive<PickDraftRequest>()
            val playerId = call.ownedPlayerId(services, request.playerId)
            val session =
                services.draftService.pick(
                    playerId,
                    request.eventName,
                    request.cardId,
                    request.packNumber,
                    request.pickNumber,
                )
            if (session.status == DraftStatus.Completed) {
                val collationId = EventRegistry.findEvent(request.eventName)?.collationId ?: 0
                services.courseService.completeDraft(playerId, request.eventName, session.pickedCards, collationId)
            }
            call.respond(sessionView(session))
        }
        get("/status") {
            val playerId = call.ownedPlayerId(services, call.request.queryParameters["playerId"])
            val eventName = call.requiredQuery("eventName")
            val session = services.draftService.getStatus(playerId, eventName)
            if (session == null) call.respond(HttpStatusCode.NotFound) else call.respond(sessionView(session))
        }
        post("/deck") {
            val request = call.receive<SubmitDeckRequest>()
            val playerId = call.ownedPlayerId(services, request.playerId)
            require(request.mainDeck.sumOf { it.quantity } >= 40) { "mainDeck must contain at least 40 cards" }
            val course =
                services.courseService.setDeck(
                    playerId,
                    request.eventName,
                    request.toCourseDeck(),
                    request.toCourseSummary(),
                )
            call.respond(courseView(course))
        }
        post("/play") {
            val request = call.receive<PlayDraftRequest>()
            val playerId = call.ownedPlayerId(services, request.playerId)
            call.respond(services.matchLauncher.launchCourseMatch(playerId, request.eventName))
        }
        delete {
            val playerId = call.ownedPlayerId(services, call.request.queryParameters["playerId"])
            val eventName = call.requiredQuery("eventName")
            services.draftService.drop(playerId, eventName)
            services.courseService.drop(playerId, eventName)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.installAuthRoutes(services: WebDoorServices) {
    route("/auth") {
        get("/me") {
            val player = services.authService.validate(call.request.cookies[WEB_SESSION_COOKIE])
            call.respond(AuthView(playerId = player?.playerId))
        }
        post("/request-code") {
            val request = call.receive<RequestLoginCodeRequest>()
            when (
                services.authService.requestCode(
                    request.email,
                    call.request.origin.remoteHost,
                    call.request.headers["User-Agent"],
                )
            ) {
                StartLoginResult.Sent -> call.respond(HttpStatusCode.NoContent)
                StartLoginResult.Cooldown,
                StartLoginResult.RateLimited,
                -> call.respond(HttpStatusCode.TooManyRequests)

                StartLoginResult.EmailSendFailed -> call.respond(HttpStatusCode.ServiceUnavailable)
            }
        }
        post("/verify") {
            val request = call.receive<VerifyLoginCodeRequest>()
            when (
                val result =
                    services.authService.verify(
                        request.email,
                        request.code,
                        call.request.origin.remoteHost,
                        call.request.headers["User-Agent"],
                    )
            ) {
                VerifyLoginResult.InvalidOrExpired -> call.respond(HttpStatusCode.Unauthorized)
                VerifyLoginResult.TooManyAttempts,
                VerifyLoginResult.RateLimited,
                -> call.respond(HttpStatusCode.TooManyRequests)

                is VerifyLoginResult.Success -> call.respondLoginSuccess(result)
            }
        }
        post("/logout") {
            services.authService.logout(call.request.cookies[WEB_SESSION_COOKIE].orEmpty())
            call.response.cookies.append(Cookie(WEB_SESSION_COOKIE, "", path = "/", maxAge = 0))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondLoginSuccess(result: VerifyLoginResult.Success) {
    response.cookies.append(
        Cookie(
            name = WEB_SESSION_COOKIE,
            value = result.token,
            path = "/",
            httpOnly = true,
            maxAge = WEB_SESSION_MAX_AGE_SECONDS,
        ),
    )
    respond(LoginResponse(result.player.playerId, result.player.email))
}

private object WebDoorRoutes

private fun io.ktor.server.application.ApplicationCall.requiredQuery(name: String): String =
    requireNotNull(request.queryParameters[name]?.takeIf { it.isNotBlank() }) { "$name is required" }

private fun String.toPlayerId(): PlayerId = PlayerId(this)

private suspend fun io.ktor.server.application.ApplicationCall.authenticatedPlayerId(services: WebDoorServices): PlayerId {
    val player =
        services.authService.validate(request.cookies[WEB_SESSION_COOKIE])
            ?: run {
                respond(HttpStatusCode.Unauthorized)
                throw UnauthorizedPlayer()
            }
    return PlayerId(player.playerId)
}

private suspend fun io.ktor.server.application.ApplicationCall.ownedPlayerId(
    services: WebDoorServices,
    requested: String?,
): PlayerId {
    val playerId = authenticatedPlayerId(services)
    if (requested != null && requested != playerId.value) {
        respond(HttpStatusCode.Forbidden)
        throw UnauthorizedPlayer()
    }
    return playerId
}

private class UnauthorizedPlayer : RuntimeException()

private fun sessionView(session: DraftSession) =
    DraftSessionView(session.eventName, session.status.name, session.packNumber, session.pickNumber, session.draftPack, session.pickedCards)

private fun courseView(course: Course) =
    CourseView(course.eventName, course.module.name, course.wins, course.losses, course.cardPool, course.deck?.deckId?.value)

private fun deckView(deck: Deck) =
    DeckView(
        id = deck.id.value,
        playerId = deck.playerId.value,
        name = deck.name,
        format = deck.format.name,
        mainDeck = deck.mainDeck.map { WebDeckCard(it.grpId, it.quantity) },
        sideboard = deck.sideboard.map { WebDeckCard(it.grpId, it.quantity) },
    )

private fun CreateDeckRequest.toDeck(playerId: PlayerId) =
    Deck(
        id = DeckId(UUID.randomUUID().toString()),
        playerId = playerId,
        name = name,
        format = Format.valueOf(format),
        tileId = mainDeck.firstOrNull()?.grpId ?: 0,
        mainDeck = mainDeck.map { DeckCard(it.grpId, it.quantity) },
        sideboard = sideboard.map { DeckCard(it.grpId, it.quantity) },
        commandZone = emptyList(),
        companions = emptyList(),
    )

private fun SubmitDeckRequest.toCourseDeck(): CourseDeck {
    val id = DeckId(deckId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
    return CourseDeck(id, mainDeck.map { DeckCard(it.grpId, it.quantity) }, sideboard.map { DeckCard(it.grpId, it.quantity) })
}

private fun SubmitDeckRequest.toCourseSummary(): CourseDeckSummary {
    val id = DeckId(deckId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
    return CourseDeckSummary(id, name.ifBlank { "Draft Deck" }, mainDeck.firstOrNull()?.grpId ?: 0, "Limited")
}
