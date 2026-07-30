package leyline.web

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
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
import io.ktor.websocket.CloseReason
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
import leyline.domain.SystemPlayers
import leyline.domain.json.productionJson
import leyline.domain.service.CollectionService
import leyline.domain.service.CourseService
import leyline.domain.service.DeckService
import leyline.domain.service.DraftService
import leyline.domain.service.EventRegistry
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleCatalog
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class WebServices(
    val draftService: DraftService,
    val courseService: CourseService,
    val deckService: DeckService,
    val collectionService: CollectionService,
    val cardRepository: CardRepository,
    val matchLauncher: WebMatchLauncher,
    val authService: WebAuthService,
    val greRelay: WebGreRelay = InProcessWebGreRelay(),
    val sealedSets: () -> List<LimitedSetView> = { emptyList() },
    val puzzleCatalog: () -> List<PuzzleSummaryView> = { defaultPuzzleCatalog() },
)

private fun defaultPuzzleCatalog(): List<PuzzleSummaryView> =
    PuzzleCatalog.list().map {
        PuzzleSummaryView(
            filename = it.filename,
            name = it.name,
            goal = it.goal,
            turns = it.turns,
            difficulty = it.difficulty,
            description = it.description,
        )
    }

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

fun Application.installWeb(services: WebServices) {
    // Immutable for the server lifetime: derived from static DTO descriptors.
    val openApiJson = WebOpenApi.generate()
    install(ContentNegotiation) {
        // Tolerate client-only view-model fields on request bodies (e.g. the SPA's
        // GreStartConfig carries gameType, which the server infers and ignores).
        json(
            productionJson {
                encodeDefaults = true
                ignoreUnknownKeys = true
            },
        )
    }
    installErrorHandling()
    install(WebSockets)
    routing {
        installGreSocket(services)
        get("/openapi.json") {
            call.respondText(openApiJson, contentType = ContentType.Application.Json)
        }
        route("/api") {
            installAuthRoutes(services)
            post("/gre/start") {
                call.respond(services.matchLauncher.launchGreMatch(call.authenticatedPlayerId(services), call.receive()))
            }
            installPublicRoutes(services)
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
            installCardRoutes(services)
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
            installSealedRoutes(services)
        }
    }
}

private fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, cause.message ?: "Bad request")
        }
        exception<UnauthorizedPlayer> { _, _ -> }
        exception<Throwable> { call, cause ->
            call.application.log.error(
                "Unhandled error on {} {}",
                call.request.local.method.value,
                call.request.local.uri,
                cause,
            )
            call.respond(HttpStatusCode.InternalServerError, "Internal error")
        }
    }
}

private fun Route.installGreSocket(services: WebServices) {
    webSocket("/gre") {
        val matchId = call.request.queryParameters["matchId"]?.takeIf { it.isNotBlank() }
        if (matchId == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "matchId is required"))
            return@webSocket
        }
        val playerId = services.authService.validate(call.request.cookies[WEB_SESSION_COOKIE])?.let { PlayerId(it.playerId) }
        val attached = services.greRelay.attach(matchId, playerId, this)
        if (!attached) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "match access denied"))
        }
    }
}

private fun Route.installDraftRoutes(services: WebServices) {
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

/**
 * Sealed's course-lifecycle shape mirrors draft's (start/deck/play/drop) minus
 * pack picking — [CourseService.join] already generates and persists the pool
 * for a sealed eventName, so start is a thin wrapper around it. Status reads
 * go through the existing `/api/courses` list rather than a duplicate route,
 * since sealed courses (unlike draft sessions) live in [CourseService] alone.
 */
private fun Route.installSealedRoutes(services: WebServices) {
    route("/sealed") {
        get("/sets") {
            call.respond(services.sealedSets())
        }
        post("/start") {
            val request = call.receive<StartDraftRequest>()
            val playerId = call.ownedPlayerId(services, request.playerId)
            require(EventRegistry.isSealed(request.eventName)) { "${request.eventName} is not a sealed event" }
            call.respond(courseView(services.courseService.join(playerId, request.eventName)))
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
            services.courseService.drop(playerId, eventName)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Which pair the spectator feed serves next. Process-local and deliberately not
 * persisted: the point is that consecutive viewers see different matches, not
 * that the sequence survives a restart.
 */
private val spectatorRotationCursor = AtomicInteger()

/**
 * A stored deck as the decklist string the match launcher takes. Decks are held
 * by grpId, so every entry goes back through the card repository for its name —
 * one that resolves to nothing is dropped rather than failing the whole launch.
 */
private fun decklistText(
    deck: Deck,
    cards: CardRepository,
): String =
    deck.mainDeck
        .mapNotNull { entry -> cards.findNameByGrpId(entry.grpId)?.let { "${entry.quantity} $it" } }
        .joinToString("\n")

private fun Route.installPublicRoutes(services: WebServices) {
    installPublicCardRoutes(services)
    post("/public/gre/start") {
        call.respond(services.matchLauncher.launchGreMatch(null, call.receive<GreStartRequest>().copy(spectatorMode = true)))
    }
    post("/public/spectator/start") {
        val rotation = services.deckService.listForPlayer(SystemPlayers.SPECTATOR).sortedBy { it.name }
        if (rotation.size < 2) {
            // Nothing seeded to play. Refusing beats launching a match nobody
            // wants to watch; the client shows its own pairing instead.
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@post
        }
        val turn = spectatorRotationCursor.getAndIncrement()
        val seat1 = rotation[Math.floorMod(turn, rotation.size)]
        val seat2 = rotation[Math.floorMod(turn + 1, rotation.size)]
        val launched =
            services.matchLauncher.launchGreMatch(
                null,
                GreStartRequest(
                    seat1Deck = decklistText(seat1, services.cardRepository),
                    seat2Deck = decklistText(seat2, services.cardRepository),
                    spectatorMode = true,
                ),
            )
        call.respond(
            PublicSpectatorResponse(launched.matchId, launched.wireMatchId, PublicSeatView(seat1.name), PublicSeatView(seat2.name)),
        )
    }
    get("/public/spectate/viewers") {
        call.respond(ViewerCountView(1))
    }
    get("/puzzles") {
        call.respond(services.puzzleCatalog())
    }
}

private fun Route.installAuthRoutes(services: WebServices) {
    route("/auth") {
        get("/me") {
            val player = services.authService.validate(call.request.cookies[WEB_SESSION_COOKIE])
            call.respond(AuthView(playerId = player?.playerId, guest = player?.let { isGuestEmail(it.email) } ?: false))
        }
        post("/guest") {
            // Reuse an existing valid session (account or guest) so a returning
            // browser doesn't mint a fresh guest player on every page load.
            val existing = services.authService.validate(call.request.cookies[WEB_SESSION_COOKIE])
            if (existing != null) {
                call.respond(AuthView(playerId = existing.playerId, guest = isGuestEmail(existing.email)))
                return@post
            }
            val result =
                services.authService.guestSession(
                    call.request.origin.remoteHost,
                    call.request.headers["User-Agent"],
                )
            if (result == null) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            call.response.cookies.append(
                Cookie(
                    name = WEB_SESSION_COOKIE,
                    value = result.token,
                    path = "/",
                    httpOnly = true,
                    maxAge = WEB_SESSION_MAX_AGE_SECONDS,
                ),
            )
            call.respond(AuthView(playerId = result.player.playerId, guest = true))
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

private suspend fun ApplicationCall.respondLoginSuccess(result: VerifyLoginResult.Success) {
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

internal fun ApplicationCall.requiredQuery(name: String): String =
    requireNotNull(request.queryParameters[name]?.takeIf { it.isNotBlank() }) { "$name is required" }

private suspend fun ApplicationCall.authenticatedPlayerId(services: WebServices): PlayerId {
    val player =
        services.authService.validate(request.cookies[WEB_SESSION_COOKIE])
            ?: run {
                respond(HttpStatusCode.Unauthorized)
                throw UnauthorizedPlayer()
            }
    return PlayerId(player.playerId)
}

private suspend fun ApplicationCall.ownedPlayerId(
    services: WebServices,
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
        mainDeck = deck.mainDeck.toWebDeckCards(),
        sideboard = deck.sideboard.toWebDeckCards(),
    )

private fun CreateDeckRequest.toDeck(playerId: PlayerId) =
    Deck(
        id = DeckId(UUID.randomUUID().toString()),
        playerId = playerId,
        name = name,
        format = Format.valueOf(format),
        tileId = mainDeck.firstOrNull()?.grpId ?: 0,
        mainDeck = mainDeck.toDomainDeckCards(),
        sideboard = sideboard.toDomainDeckCards(),
        commandZone = emptyList(),
        companions = emptyList(),
    )

private fun SubmitDeckRequest.toCourseDeck(): CourseDeck {
    val id = DeckId(deckId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
    return CourseDeck(id, mainDeck.toDomainDeckCards(), sideboard.toDomainDeckCards())
}

private fun SubmitDeckRequest.toCourseSummary(): CourseDeckSummary {
    val id = DeckId(deckId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
    return CourseDeckSummary(id, name.ifBlank { "Draft Deck" }, mainDeck.firstOrNull()?.grpId ?: 0, "Limited")
}

private fun List<WebDeckCard>.toDomainDeckCards(): List<DeckCard> = map { DeckCard(it.grpId, it.quantity) }

private fun List<DeckCard>.toWebDeckCards(): List<WebDeckCard> = map { WebDeckCard(it.grpId, it.quantity) }
