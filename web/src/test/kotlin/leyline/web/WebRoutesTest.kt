package leyline.web

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

class WebRoutesTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        test("serves checked-in OpenAPI contract") {
            withWeb { client, _ ->
                val response = client.get("/openapi.json")
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

                response.status shouldBe HttpStatusCode.OK
                body["openapi"]?.jsonPrimitive?.content shouldBe "3.1.0"
            }
        }

        test("starts and reads draft status") {
            withWeb { client, repos ->
                val login = client.login(repos)
                val start =
                    client.post("/api/draft/start") {
                        header(HttpHeaders.Cookie, login.cookie)
                        contentType(ContentType.Application.Json)
                        setBody("""{"playerId":"${login.playerId}","eventName":"QuickDraft_FDN_20260223"}""")
                    }
                val status =
                    client.get("/api/draft/status?playerId=${login.playerId}&eventName=QuickDraft_FDN_20260223") {
                        header(HttpHeaders.Cookie, login.cookie)
                    }

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
            withWeb { client, repos ->
                val login = client.login(repos)
                client.post("/api/draft/start") {
                    header(HttpHeaders.Cookie, login.cookie)
                    contentType(ContentType.Application.Json)
                    setBody("""{"playerId":"${login.playerId}","eventName":"QuickDraft_FDN_20260223"}""")
                }
                val response =
                    client.post("/api/draft/deck") {
                        header(HttpHeaders.Cookie, login.cookie)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {"playerId":"${login.playerId}","eventName":"QuickDraft_FDN_20260223","mainDeck":[{"grpId":100,"quantity":1}]}
                            """.trimIndent(),
                        )
                    }

                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("drops draft and course") {
            withWeb { client, repos ->
                val login = client.login(repos)
                client.post("/api/draft/start") {
                    header(HttpHeaders.Cookie, login.cookie)
                    contentType(ContentType.Application.Json)
                    setBody("""{"playerId":"${login.playerId}","eventName":"QuickDraft_FDN_20260223"}""")
                }
                val response =
                    client.delete("/api/draft?playerId=${login.playerId}&eventName=QuickDraft_FDN_20260223") {
                        header(HttpHeaders.Cookie, login.cookie)
                    }

                response.status shouldBe HttpStatusCode.NoContent
                repos.draft.findByPlayerAndEvent(PlayerId(login.playerId), "QuickDraft_FDN_20260223") shouldBe null
            }
        }

        test("relays GRE WebSocket frames through engine session") {
            withWeb { client, repos ->
                val login = client.login(repos)
                repos.relay.register(
                    "m1",
                    StaticGreEngineSession(repos.enginePayloads, reply = byteArrayOf(9, 8, 7)),
                    ownerPlayerId = PlayerId(login.playerId),
                )
                val wsClient = client.config { install(WebSockets) }
                wsClient.webSocket({
                    url.takeFrom("/gre?matchId=m1")
                    header(HttpHeaders.Cookie, login.cookie)
                }) {
                    send(Frame.Binary(fin = true, data = byteArrayOf(1, 2, 3)))
                    val frame = incoming.receive() as Frame.Binary

                    repos.enginePayloads.single().contentEquals(byteArrayOf(1, 2, 3)) shouldBe true
                    frame.readBytes().contentEquals(byteArrayOf(9, 8, 7)) shouldBe true
                }
            }
        }

        test("public GRE WebSocket attachments are read-only") {
            withWeb { client, repos ->
                repos.relay.register(
                    "public",
                    StaticGreEngineSession(repos.enginePayloads, reply = byteArrayOf(9, 8, 7)),
                    ownerPlayerId = PlayerId("owner"),
                    publicAccess = true,
                )
                val wsClient = client.config { install(WebSockets) }

                wsClient.webSocket("/gre?matchId=public") {
                    send(Frame.Binary(fin = true, data = byteArrayOf(1, 2, 3)))

                    withTimeoutOrNull(100) { incoming.receive() } shouldBe null
                    repos.enginePayloads shouldBe emptyList()
                }
            }
        }

        test("GRE relay closes idle sessions") {
            withWeb { client, repos ->
                val login = client.login(repos)
                val closed = AtomicBoolean(false)
                var cleanupCount = 0
                repos.relay.register(
                    "close-me",
                    StaticGreEngineSession(repos.enginePayloads, reply = byteArrayOf(1), closed = closed),
                    ownerPlayerId = PlayerId(login.playerId),
                    onClose = { cleanupCount++ },
                )
                val wsClient = client.config { install(WebSockets) }

                wsClient.webSocket({
                    url.takeFrom("/gre?matchId=close-me")
                    header(HttpHeaders.Cookie, login.cookie)
                }) {
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
            withWeb { client, repos ->
                val login = client.login(repos)
                val engine = ConcurrentProbeGreEngineSession()
                repos.relay.register("serialized", engine, ownerPlayerId = PlayerId(login.playerId))
                val wsClient = client.config { install(WebSockets) }
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
            withWeb { client, repos ->
                repos.relay.register(
                    "private",
                    StaticGreEngineSession(repos.enginePayloads, reply = byteArrayOf()),
                    ownerPlayerId = PlayerId("owner"),
                )
                val wsClient = client.config { install(WebSockets) }

                wsClient.webSocket("/gre?matchId=private") {
                    shouldThrow<ClosedReceiveChannelException> { incoming.receive() }
                }
            }
        }

        test("GRE WebSocket accepts private match owner session") {
            withWeb { client, repos ->
                val login = client.login(repos)
                repos.relay.register(
                    "private",
                    StaticGreEngineSession(repos.enginePayloads, reply = byteArrayOf(4, 5, 6)),
                    ownerPlayerId = PlayerId(login.playerId),
                )
                val wsClient = client.config { install(WebSockets) }

                wsClient.webSocket({
                    url.takeFrom("/gre?matchId=private")
                    header(HttpHeaders.Cookie, login.cookie)
                }) {
                    send(Frame.Binary(fin = true, data = byteArrayOf(1, 2, 3)))
                    val frame = incoming.receive() as Frame.Binary

                    frame.readBytes().contentEquals(byteArrayOf(4, 5, 6)) shouldBe true
                }
            }
        }

        test("owned routes reject mismatched player id") {
            withWeb { client, repos ->
                val login = client.login(repos)
                val response = client.get("/api/collection?playerId=other") { header(HttpHeaders.Cookie, login.cookie) }

                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("serves card metadata from card repository") {
            withWeb { client, _ ->
                val response = client.get("/api/cards/metadata")
                val cards = json.parseToJsonElement(response.bodyAsText()).jsonObject["cards"]!!.jsonArray

                assertSoftly {
                    response.status shouldBe HttpStatusCode.OK
                    cards.size shouldBe 2
                    cards[0].jsonObject["grpId"]!!.jsonPrimitive.content shouldBe "100"
                    cards[0].jsonObject["name"]!!.jsonPrimitive.content shouldBe "Alpha Card"
                    cards[1].jsonObject["grpId"]!!.jsonPrimitive.content shouldBe "101"
                    cards[1].jsonObject["name"]!!.jsonPrimitive.content shouldBe "Beta Card"
                }
            }
        }

        test("serves limited set list") {
            withWeb { client, _ ->
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
            withWeb { client, repos ->
                val request =
                    client.post("/api/auth/request-code") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"Player@Example.test"}""")
                    }
                val code = checkNotNull(repos.emailSender.latestCode("player@example.test"))
                val verify =
                    client.post("/api/auth/verify") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"player@example.test","code":"$code"}""")
                    }
                val cookie = checkNotNull(verify.headers[HttpHeaders.SetCookie]).substringBefore(";")
                val me = client.get("/api/auth/me") { header(HttpHeaders.Cookie, cookie) }
                val logout = client.post("/api/auth/logout") { header(HttpHeaders.Cookie, cookie) }
                val afterLogout = client.get("/api/auth/me") { header(HttpHeaders.Cookie, cookie) }

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
            val service = WebAuthService(store, sender, DEV_WEB_AUTH_SECRET)

            service.requestCode("player@example.test") shouldBe StartLoginResult.Sent
            val code = checkNotNull(sender.latestCode("player@example.test"))
            val verified = service.verify("player@example.test", code) as VerifyLoginResult.Success
            val reloadedService = WebAuthService(SqliteWebAuthStore(db), sender, DEV_WEB_AUTH_SECRET)

            reloadedService.validate(verified.token)?.playerId shouldBe verified.player.playerId
            reloadedService.logout(verified.token)
            reloadedService.validate(verified.token) shouldBe null
        }
    })

private fun withWeb(block: suspend (io.ktor.client.HttpClient, TestRepos) -> Unit) =
    testApplication {
        val repos = TestRepos()
        val services =
            WebServices(
                draftService = DraftService(repos.draft, StaticDraftDriver()),
                courseService =
                    CourseService(
                        repos.course,
                    ) { GeneratedPool(cards = emptyList(), byCollation = emptyList(), collationId = 0) },
                deckService = DeckService(repos.deck),
                collectionService = CollectionService { listOf(100, 101) },
                cardRepository = repos.cards,
                authService = WebAuthService(InMemoryWebAuthStore(), repos.emailSender, DEV_WEB_AUTH_SECRET),
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
        block(client, repos)
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
            it.register(100, "Alpha Card")
            it.register(101, "Beta Card")
        }
}

private data class TestLogin(
    val cookie: String,
    val playerId: String,
)

private suspend fun io.ktor.client.HttpClient.login(repos: TestRepos): TestLogin {
    post("/api/auth/request-code") {
        contentType(ContentType.Application.Json)
        setBody("""{"email":"player@example.test"}""")
    }
    val code = checkNotNull(repos.emailSender.latestCode("player@example.test"))
    val verify =
        post("/api/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"player@example.test","code":"$code"}""")
        }
    val cookie = checkNotNull(verify.headers[HttpHeaders.SetCookie]).substringBefore(";")
    val playerId =
        Json
            .parseToJsonElement(verify.bodyAsText())
            .jsonObject["playerId"]!!
            .jsonPrimitive.content
    return TestLogin(cookie, playerId)
}

private suspend fun io.ktor.client.HttpClient.roundTrip(
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
        header(HttpHeaders.Cookie, login.cookie)
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
