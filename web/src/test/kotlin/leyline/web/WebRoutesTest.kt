package leyline.web

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.domain.Course
import leyline.domain.CourseId
import leyline.domain.Deck
import leyline.domain.DeckId
import leyline.domain.DraftSession
import leyline.domain.DraftSessionId
import leyline.domain.DraftStatus
import leyline.domain.PlayerId
import leyline.domain.repo.CourseRepository
import leyline.domain.repo.DeckRepository
import leyline.domain.repo.DraftSessionRepository
import leyline.domain.service.CollectionService
import leyline.domain.service.CourseService
import leyline.domain.service.DeckService
import leyline.domain.service.DraftService
import leyline.domain.service.GeneratedPool
import leyline.game.InMemoryCardRepository
import leyline.game.data.CardData
import org.jetbrains.exposed.v1.jdbc.Database
import wotc.mtgo.gre.external.messaging.Messages.CardColor
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.SubType
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

class WebRoutesTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        test("serves checked-in OpenAPI contract") {
            withWeb(json) {
                val response = client.get("/openapi.json")
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

                response.status shouldBe HttpStatusCode.OK
                body["openapi"]?.jsonPrimitive?.content shouldBe "3.1.0"
            }
        }

        test("lists puzzles publicly without auth") {
            withWeb(
                json,
                puzzleCatalog = {
                    listOf(
                        PuzzleSummaryView(
                            filename = "stock-up",
                            name = "Stock Up",
                            goal = "Win",
                            turns = 4,
                            difficulty = "Tutorial",
                            description = "Cast Stock Up.",
                        ),
                    )
                },
            ) {
                val response = client.get("/api/puzzles")
                val body = json.parseToJsonElement(response.bodyAsText()).jsonArray

                assertSoftly {
                    response.status shouldBe HttpStatusCode.OK
                    body.size shouldBe 1
                    body[0].jsonObject["filename"]?.jsonPrimitive?.content shouldBe "stock-up"
                    body[0].jsonObject["name"]?.jsonPrimitive?.content shouldBe "Stock Up"
                }
            }
        }

        test("mints a guest session that can start a match") {
            withWeb(json) {
                val guest = client.post("/api/auth/guest")
                val cookie = checkNotNull(guest.headers[HttpHeaders.SetCookie]).substringBefore(";")
                val guestBody = json.parseToJsonElement(guest.bodyAsText()).jsonObject
                val playerId = checkNotNull(guestBody["playerId"]?.jsonPrimitive?.content)

                val me = client.get("/api/auth/me") { auth(cookie) }
                val meBody = json.parseToJsonElement(me.bodyAsText()).jsonObject

                val start =
                    client.post("/api/gre/start") {
                        auth(cookie)
                        jsonBody("""{"puzzle":"stock-up"}""")
                    }

                assertSoftly {
                    guest.status shouldBe HttpStatusCode.OK
                    guestBody["guest"]?.jsonPrimitive?.content shouldBe "true"
                    meBody["playerId"]?.jsonPrimitive?.content shouldBe playerId
                    meBody["guest"]?.jsonPrimitive?.content shouldBe "true"
                    start.status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("reuses an existing guest session instead of minting a new one") {
            withWeb(json) {
                val first = client.post("/api/auth/guest")
                val cookie = checkNotNull(first.headers[HttpHeaders.SetCookie]).substringBefore(";")
                val firstId =
                    json
                        .parseToJsonElement(first.bodyAsText())
                        .jsonObject["playerId"]!!
                        .jsonPrimitive.content

                val second = client.post("/api/auth/guest") { auth(cookie) }
                val secondBody = json.parseToJsonElement(second.bodyAsText()).jsonObject

                assertSoftly {
                    second.status shouldBe HttpStatusCode.OK
                    secondBody["playerId"]?.jsonPrimitive?.content shouldBe firstId
                    secondBody["guest"]?.jsonPrimitive?.content shouldBe "true"
                    second.headers[HttpHeaders.SetCookie] shouldBe null
                }
            }
        }

        test("gre start tolerates unknown client fields") {
            withWeb(json) {
                val guest = client.post("/api/auth/guest")
                val cookie = checkNotNull(guest.headers[HttpHeaders.SetCookie]).substringBefore(";")
                val start =
                    client.post("/api/gre/start") {
                        auth(cookie)
                        jsonBody("""{"puzzle":"stock-up","gameType":"puzzle","unknownClientField":true}""")
                    }

                start.status shouldBe HttpStatusCode.OK
            }
        }

        test("starts and reads draft status") {
            withWeb(json) {
                val login = login()
                val start = startDraft(login)
                val status = draftStatus(login)

                assertSoftly {
                    start.status shouldBe HttpStatusCode.OK
                    json
                        .parseToJsonElement(start.bodyAsText())
                        .jsonObject["draftPack"]!!
                        .jsonArray.size shouldBe 2
                    status.status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("rejects undersized draft deck") {
            withWeb(json) {
                val login = login()
                startDraft(login)
                val response =
                    client.post("/api/draft/deck") {
                        auth(login)
                        jsonBody(
                            """
                            {"playerId":"${login.playerId}","eventName":"$TEST_EVENT","mainDeck":[{"grpId":100,"quantity":1}]}
                            """.trimIndent(),
                        )
                    }

                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("drops draft and course") {
            withWeb(json) {
                val login = login()
                startDraft(login)
                val response = dropDraft(login)

                response.status shouldBe HttpStatusCode.NoContent
                repos.draft.findByPlayerAndEvent(PlayerId(login.playerId), TEST_EVENT) shouldBe null
            }
        }

        test("relays GRE WebSocket frames through engine session") {
            withWeb(json) {
                val login = login()
                repos.registerGre(
                    "m1",
                    reply = byteArrayOf(9, 8, 7),
                    ownerPlayerId = PlayerId(login.playerId),
                )
                greSocket(login, "m1") {
                    send(Frame.Binary(fin = true, data = byteArrayOf(1, 2, 3)))
                    val frame = incoming.receive() as Frame.Binary

                    repos.enginePayloads.single().contentEquals(byteArrayOf(1, 2, 3)) shouldBe true
                    frame.readBytes().contentEquals(byteArrayOf(9, 8, 7)) shouldBe true
                }
            }
        }

        test("public GRE WebSocket attachments are read-only") {
            withWeb(json) {
                repos.registerGre(
                    "public",
                    reply = byteArrayOf(9, 8, 7),
                    ownerPlayerId = PlayerId("owner"),
                    publicAccess = true,
                )

                publicGreSocket("public") {
                    send(Frame.Binary(fin = true, data = byteArrayOf(1, 2, 3)))

                    withTimeoutOrNull(100) { incoming.receive() } shouldBe null
                    repos.enginePayloads shouldBe emptyList()
                }
            }
        }

        test("GRE relay closes idle sessions") {
            withWeb(json) {
                val login = login()
                val closed = AtomicBoolean(false)
                var cleanupCount = 0
                repos.registerGre(
                    "close-me",
                    reply = byteArrayOf(1),
                    ownerPlayerId = PlayerId(login.playerId),
                    closed = closed,
                    onClose = { cleanupCount++ },
                )

                greSocket(login, "close-me") {
                    send(Frame.Binary(fin = true, data = byteArrayOf(1)))
                    incoming.receive()
                }

                assertSoftly {
                    closed.get() shouldBe true
                    cleanupCount shouldBe 1
                }
            }
        }

        test("GRE relay serializes engine access per match") {
            withWeb(json) {
                val login = login()
                val engine = ConcurrentProbeGreEngineSession()
                repos.relay.register("serialized", engine, ownerPlayerId = PlayerId(login.playerId))
                val attached = AtomicInteger(0)
                val bothAttached = CompletableDeferred<Unit>()
                val replies = AtomicInteger(0)
                val bothReplied = CompletableDeferred<Unit>()

                coroutineScope {
                    val first =
                        async { wsClient.roundTrip(login, "serialized", byteArrayOf(1), attached, bothAttached, replies, bothReplied) }
                    val second =
                        async { wsClient.roundTrip(login, "serialized", byteArrayOf(2), attached, bothAttached, replies, bothReplied) }
                    first.await()
                    second.await()
                }

                assertSoftly {
                    engine.receivedCount.get() shouldBe 2
                    engine.maxConcurrent.get() shouldBe 1
                }
            }
        }

        test("GRE WebSocket rejects private match without owner session") {
            withWeb(json) {
                repos.registerGre(
                    "private",
                    reply = byteArrayOf(),
                    ownerPlayerId = PlayerId("owner"),
                )

                publicGreSocket("private") {
                    shouldThrow<ClosedReceiveChannelException> { incoming.receive() }
                }
            }
        }

        test("GRE WebSocket accepts private match owner session") {
            withWeb(json) {
                val login = login()
                repos.registerGre(
                    "private",
                    reply = byteArrayOf(4, 5, 6),
                    ownerPlayerId = PlayerId(login.playerId),
                )

                greSocket(login, "private") {
                    send(Frame.Binary(fin = true, data = byteArrayOf(1, 2, 3)))
                    val frame = incoming.receive() as Frame.Binary

                    frame.readBytes().contentEquals(byteArrayOf(4, 5, 6)) shouldBe true
                }
            }
        }

        test("owned routes reject mismatched player id") {
            withWeb(json) {
                val login = login()
                val response = client.get("/api/collection?playerId=other") { auth(login) }

                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("serves public card metadata by grpIds") {
            withWeb(json) {
                val response = client.get("/api/public/cards/by-grpids?ids=100,101,999")
                val cards = json.parseToJsonElement(response.bodyAsText()).jsonObject

                assertSoftly {
                    response.status shouldBe HttpStatusCode.OK
                    cards["100"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "Alpha Card"
                    cards["100"]!!.jsonObject["manaCost"]!!.jsonPrimitive.content shouldBe "{G}"
                    cards["100"]!!.jsonObject["types"]!!.jsonPrimitive.content shouldBe "Creature"
                    cards["100"]!!.jsonObject["subtypes"]!!.jsonPrimitive.content shouldBe "Elf"
                    cards["100"]!!.jsonObject["imageUrl"]!!.jsonPrimitive.content shouldBe
                        "https://api.scryfall.com/cards/named?exact=Alpha+Card&format=image&version=normal"
                    cards["999"]!!.jsonObject["grpId"]!!.jsonPrimitive.content shouldBe "999"
                }
            }
        }

        test("searches cards by name") {
            withWeb(json) {
                val response = client.get("/api/cards/search?q=alpha&colors=G")
                val cards = json.parseToJsonElement(response.bodyAsText()).jsonArray

                assertSoftly {
                    response.status shouldBe HttpStatusCode.OK
                    cards.size shouldBe 1
                    cards[0].jsonObject["name"]!!.jsonPrimitive.content shouldBe "Alpha Card"
                    cards[0].jsonObject["typeLine"]!!.jsonPrimitive.content shouldBe "Creature — Elf"
                }
            }
        }

        test("parses decklists into sections") {
            withWeb(json) {
                val response =
                    client.post("/api/cards/parse-decklist") {
                        jsonBody("""{"text":"2 Alpha Card (TST) 1\nSideboard\n1 Missing Card\n[commander]\nBeta Card"}""")
                    }
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

                assertSoftly {
                    response.status shouldBe HttpStatusCode.OK
                    body["mainboard"]!!
                        .jsonArray
                        .single()
                        .jsonObject["grpId"]!!
                        .jsonPrimitive.content shouldBe "100"
                    body["sideboard"]!!
                        .jsonArray
                        .single()
                        .jsonObject["found"]!!
                        .jsonPrimitive.content shouldBe "false"
                    body["commander"]!!
                        .jsonArray
                        .single()
                        .jsonObject["grpId"]!!
                        .jsonPrimitive.content shouldBe "101"
                    body["errors"]!!
                        .jsonArray
                        .single()
                        .jsonPrimitive.content shouldBe "Card not found: Missing Card"
                }
            }
        }

        test("rejects oversized decklist") {
            withWeb(json) {
                val response =
                    client.post("/api/cards/parse-decklist") {
                        jsonBody("""{"text":"${"a".repeat(20_001)}"}""")
                    }

                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("serves limited set list") {
            withWeb(json) {
                val response = client.get("/api/sealed/sets")
                val sets = json.parseToJsonElement(response.bodyAsText()).jsonArray

                assertSoftly {
                    response.status shouldBe HttpStatusCode.OK
                    sets
                        .single()
                        .jsonObject["code"]!!
                        .jsonPrimitive.content shouldBe "FDN"
                    sets
                        .single()
                        .jsonObject["name"]!!
                        .jsonPrimitive.content shouldBe "Foundations"
                }
            }
        }

        test("auth creates and revokes opaque web session") {
            withWeb(json) {
                val request =
                    client.post("/api/auth/request-code") {
                        jsonBody("""{"email":"Player@Example.test"}""")
                    }
                val code = checkNotNull(repos.emailSender.latestCode("player@example.test"))
                val verify =
                    client.post("/api/auth/verify") {
                        jsonBody("""{"email":"player@example.test","code":"$code"}""")
                    }
                val cookie = checkNotNull(verify.headers[HttpHeaders.SetCookie]).substringBefore(";")
                val me = client.get("/api/auth/me") { auth(cookie) }
                val logout = client.post("/api/auth/logout") { auth(cookie) }
                val afterLogout = client.get("/api/auth/me") { auth(cookie) }

                assertSoftly {
                    request.status shouldBe HttpStatusCode.NoContent
                    verify.status shouldBe HttpStatusCode.OK
                    json
                        .parseToJsonElement(me.bodyAsText())
                        .jsonObject["playerId"]
                        ?.jsonPrimitive
                        ?.content
                        ?.isNotBlank() shouldBe true
                    logout.status shouldBe HttpStatusCode.NoContent
                    json.parseToJsonElement(afterLogout.bodyAsText()).jsonObject["playerId"].toString() shouldBe "null"
                }
            }
        }

        test("SQLite auth store persists and revokes opaque sessions") {
            val dbFile = Files.createTempFile("web-auth", ".sqlite").toFile()
            val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
            val sender = DevEmailSender()
            val store = SqliteWebAuthStore(db).also { it.createTables() }
            val service = WebAuthService(store, sender)

            service.requestCode("player@example.test") shouldBe StartLoginResult.Sent
            val code = checkNotNull(sender.latestCode("player@example.test"))
            val verified = service.verify("player@example.test", code) as VerifyLoginResult.Success
            val reloadedService = WebAuthService(SqliteWebAuthStore(db), sender)

            reloadedService.validate(verified.token)?.playerId shouldBe verified.player.playerId
            reloadedService.logout(verified.token)
            reloadedService.validate(verified.token) shouldBe null
        }
    })

private const val TEST_EMAIL = "player@example.test"
private const val TEST_EVENT = "QuickDraft_FDN_20260223"

private fun withWeb(
    json: Json,
    puzzleCatalog: () -> List<PuzzleSummaryView> = { emptyList() },
    block: suspend WebFixture.() -> Unit,
) {
    testApplication {
        val repos = TestRepos()
        val services =
            WebServices(
                puzzleCatalog = puzzleCatalog,
                draftService = DraftService(repos.draft, StaticDraftDriver()),
                courseService =
                    CourseService(
                        repos.course,
                    ) { GeneratedPool(cards = emptyList(), byCollation = emptyList(), collationId = 0) },
                deckService = DeckService(repos.deck),
                collectionService = CollectionService { listOf(100, 101) },
                cardRepository = repos.cards,
                authService = WebAuthService(InMemoryWebAuthStore(), repos.emailSender),
                matchLauncher =
                    object : WebMatchLauncher {
                        override fun launchGreMatch(
                            playerId: PlayerId?,
                            request: GreStartRequest,
                        ) = DraftPlayResponse("match-1", "wire-1")

                        override fun launchCourseMatch(
                            playerId: PlayerId,
                            eventName: String,
                        ) = DraftPlayResponse("match-1", "wire-1")
                    },
                greRelay = repos.relay,
            )
        application { installWeb(services) }
        block(WebFixture(client, client.config { install(WebSockets) }, repos, json))
    }
}

private class WebFixture(
    val client: HttpClient,
    val wsClient: HttpClient,
    val repos: TestRepos,
    val json: Json,
) {
    suspend fun login(email: String = TEST_EMAIL) = client.login(repos, json, email)

    suspend fun startDraft(login: TestLogin) =
        client.post("/api/draft/start") {
            auth(login)
            jsonBody("""{"playerId":"${login.playerId}","eventName":"$TEST_EVENT"}""")
        }

    suspend fun draftStatus(login: TestLogin) =
        client.get("/api/draft/status?playerId=${login.playerId}&eventName=$TEST_EVENT") {
            auth(login)
        }

    suspend fun dropDraft(login: TestLogin) =
        client.delete("/api/draft?playerId=${login.playerId}&eventName=$TEST_EVENT") {
            auth(login)
        }

    suspend fun greSocket(
        login: TestLogin,
        matchId: String,
        block: suspend DefaultClientWebSocketSession.() -> Unit,
    ) = wsClient.webSocket({
        url.takeFrom("/gre?matchId=$matchId")
        auth(login)
    }, block)

    suspend fun publicGreSocket(
        matchId: String,
        block: suspend DefaultClientWebSocketSession.() -> Unit,
    ) = wsClient.webSocket({
        url.takeFrom("/gre?matchId=$matchId")
    }, block)
}

private class TestRepos {
    val course = MemoryCourseRepo()
    val draft = MemoryDraftRepo()
    val deck = MemoryDeckRepo()
    val emailSender = DevEmailSender()
    val enginePayloads = mutableListOf<ByteArray>()
    val relay = InProcessWebGreRelay()
    val cards =
        InMemoryCardRepository().also {
            it.registerData(
                CardData(
                    grpId = 100,
                    titleId = 9001,
                    power = "2",
                    toughness = "3",
                    colors = listOf(CardColor.Green_a3b0.number),
                    types = listOf(CardType.Creature.number),
                    subtypes = listOf(SubType.Elf.number),
                    supertypes = emptyList(),
                    abilityIds = emptyList(),
                    manaCost = listOf(ManaColor.Green_afc9 to 1),
                ),
                "Alpha Card",
            )
            it.registerData(
                CardData(
                    grpId = 101,
                    titleId = 9002,
                    power = "",
                    toughness = "",
                    colors = listOf(CardColor.Blue_a3b0.number),
                    types = listOf(CardType.Instant.number),
                    subtypes = emptyList(),
                    supertypes = emptyList(),
                    abilityIds = emptyList(),
                    manaCost = listOf(ManaColor.Blue_afc9 to 1),
                ),
                "Beta Card",
            )
        }

    fun registerGre(
        matchId: String,
        reply: ByteArray,
        ownerPlayerId: PlayerId,
        publicAccess: Boolean = false,
        closed: AtomicBoolean = AtomicBoolean(false),
        onClose: () -> Unit = {},
    ) {
        relay.register(
            matchId,
            StaticGreEngineSession(enginePayloads, reply, closed),
            ownerPlayerId = ownerPlayerId,
            publicAccess = publicAccess,
            onClose = onClose,
        )
    }
}

private data class TestLogin(
    val cookie: String,
    val playerId: String,
)

private suspend fun HttpClient.login(
    repos: TestRepos,
    json: Json,
    email: String,
): TestLogin {
    post("/api/auth/request-code") {
        jsonBody("""{"email":"$email"}""")
    }
    val normalizedEmail = email.lowercase()
    val code = checkNotNull(repos.emailSender.latestCode(normalizedEmail))
    val verify =
        post("/api/auth/verify") {
            jsonBody("""{"email":"$normalizedEmail","code":"$code"}""")
        }
    val cookie = checkNotNull(verify.headers[HttpHeaders.SetCookie]).substringBefore(";")
    val playerId =
        json
            .parseToJsonElement(verify.bodyAsText())
            .jsonObject["playerId"]!!
            .jsonPrimitive.content
    return TestLogin(cookie, playerId)
}

private fun HttpRequestBuilder.auth(login: TestLogin) = auth(login.cookie)

private fun HttpRequestBuilder.auth(cookie: String) {
    header(HttpHeaders.Cookie, cookie)
}

private fun HttpRequestBuilder.jsonBody(body: String) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

private suspend fun HttpClient.roundTrip(
    login: TestLogin,
    matchId: String,
    payload: ByteArray,
    attached: AtomicInteger,
    bothAttached: CompletableDeferred<Unit>,
    replies: AtomicInteger,
    bothReplied: CompletableDeferred<Unit>,
) {
    webSocket({
        url.takeFrom("/gre?matchId=$matchId")
        auth(login)
    }) {
        if (attached.incrementAndGet() == 2) bothAttached.complete(Unit)
        bothAttached.await()
        send(Frame.Binary(fin = true, data = payload))
        while (true) {
            val reply = incoming.receive() as Frame.Binary
            if (reply.readBytes().contentEquals(payload)) break
        }
        if (replies.incrementAndGet() == 2) bothReplied.complete(Unit)
        bothReplied.await()
    }
}

private class StaticGreEngineSession(
    private val received: MutableList<ByteArray>,
    private val reply: ByteArray,
    private val closed: AtomicBoolean = AtomicBoolean(false),
) : WebGreEngineSession {
    override fun receiveFromBrowser(payload: ByteArray): List<ByteArray> {
        received += payload
        return listOf(reply)
    }

    override fun close() {
        closed.set(true)
    }
}

private class ConcurrentProbeGreEngineSession : WebGreEngineSession {
    val receivedCount = AtomicInteger(0)
    val maxConcurrent = AtomicInteger(0)
    private val active = AtomicInteger(0)

    override fun receiveFromBrowser(payload: ByteArray): List<ByteArray> {
        val nowActive = active.incrementAndGet()
        maxConcurrent.updateAndGet { current -> maxOf(current, nowActive) }
        try {
            LockSupport.parkNanos(50_000_000)
            receivedCount.incrementAndGet()
            return listOf(payload)
        } finally {
            active.decrementAndGet()
        }
    }

    override fun close() = Unit
}

private class StaticDraftDriver : DraftService.Driver {
    override fun start(
        sessionKey: String,
        setCode: String,
    ) = listOf(100, 101)

    override fun pick(
        sessionKey: String,
        grpId: Int,
    ) = DraftService.PickOutcome(0, 1, listOf(101), complete = false)

    override fun complete(sessionKey: String) = DraftService.PodOutcome(emptyList(), emptyList())
}

private class MemoryCourseRepo : CourseRepository {
    private val courses = mutableMapOf<CourseId, Course>()

    override fun findById(id: CourseId) = courses[id]

    override fun findByPlayer(playerId: PlayerId) = courses.values.filter { it.playerId == playerId }

    override fun findByPlayerAndEvent(
        playerId: PlayerId,
        eventName: String,
    ) = courses.values.firstOrNull {
        it.playerId == playerId &&
            it.eventName == eventName
    }

    override fun save(course: Course) {
        courses[course.id] = course
    }

    override fun delete(id: CourseId) {
        courses.remove(id)
    }
}

private class MemoryDraftRepo : DraftSessionRepository {
    private val sessions = mutableMapOf<DraftSessionId, DraftSession>()

    override fun findById(id: DraftSessionId) = sessions[id]

    override fun findByPlayerAndEvent(
        playerId: PlayerId,
        eventName: String,
    ) = sessions.values.firstOrNull {
        it.playerId == playerId &&
            it.eventName == eventName
    }

    override fun save(session: DraftSession) {
        sessions[session.id] = session
    }

    override fun delete(id: DraftSessionId) {
        sessions.remove(id)
    }

    override fun deleteIncomplete() {
        sessions.values.removeIf { it.status != DraftStatus.Completed }
    }

    override fun savePodResults(
        sessionId: DraftSessionId,
        botDecks: List<List<Int>>,
    ) = Unit

    override fun findPodResults(sessionId: DraftSessionId): List<List<Int>> = emptyList()
}

private class MemoryDeckRepo : DeckRepository {
    private val decks = mutableMapOf<DeckId, Deck>()

    override fun findById(id: DeckId) = decks[id]

    override fun findByName(name: String) = decks.values.firstOrNull { it.name == name }

    override fun findAllForPlayer(playerId: PlayerId) = decks.values.filter { it.playerId == playerId }

    override fun save(deck: Deck) {
        decks[deck.id] = deck
    }

    override fun delete(id: DeckId) {
        decks.remove(id)
    }
}
