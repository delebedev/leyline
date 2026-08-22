package leyline.web

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.config.MatchConfig
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.CollationPool
import leyline.domain.Deck
import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.Format
import leyline.domain.PlayerId
import leyline.domain.SystemPlayers
import leyline.domain.repo.InMemoryCourseRepository
import leyline.domain.repo.InMemoryDeckRepository
import leyline.domain.repo.InMemoryDraftSessionRepository
import leyline.domain.service.CollectionService
import leyline.domain.service.CourseService
import leyline.domain.service.DeckService
import leyline.domain.service.DraftService
import leyline.domain.service.GeneratedPool
import leyline.domain.service.MatchCoordinator
import leyline.game.InMemoryCardRepository
import leyline.game.data.CardData
import org.jetbrains.exposed.v1.jdbc.Database
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.CardColor
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.PerformActionResp
import wotc.mtgo.gre.external.messaging.Messages.SubType
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

@Suppress("LargeClass") // Web route coverage shares a single server fixture.
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

        test("spectator feed declines until a rotation is seeded") {
            withWeb(json) {
                client.post("/api/public/spectator/start").status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }

        test("spectator feed pairs rotation decks and advances on each start") {
            withWeb(json) {
                repos.deck.save(spectatorDeck("alpha-deck", "Alpha Deck", grpId = 100))
                repos.deck.save(spectatorDeck("beta-deck", "Beta Deck", grpId = 101))

                val first = json.parseToJsonElement(client.post("/api/public/spectator/start").bodyAsText()).jsonObject
                val second = json.parseToJsonElement(client.post("/api/public/spectator/start").bodyAsText()).jsonObject

                assertSoftly {
                    first.seatName("seat1") shouldBe "Alpha Deck"
                    first.seatName("seat2") shouldBe "Beta Deck"
                    // Two decks, so the next start swaps the seats rather than repeating.
                    second.seatName("seat1") shouldBe "Beta Deck"
                    second.seatName("seat2") shouldBe "Alpha Deck"
                    greLaunches[0].seat1Deck shouldBe "60 Alpha Card"
                    greLaunches[0].seat2Deck shouldBe "60 Beta Card"
                    greLaunches[0].spectatorMode shouldBe true
                }
            }
        }

        test("spectator feed carries Brawl format into the match launch") {
            withWeb(json) {
                repos.deck.save(spectatorDeck("alpha-deck", "Alpha Deck", grpId = 100, format = Format.Brawl))
                repos.deck.save(spectatorDeck("beta-deck", "Beta Deck", grpId = 101, format = Format.Brawl))

                client.post("/api/public/spectator/start").status shouldBe HttpStatusCode.OK

                assertSoftly {
                    greLaunches.single().gameVariant shouldBe "brawl"
                    greLaunches.single().seat1Deck shouldBe "[Commander]\n1 Alpha Card\n60 Alpha Card"
                    greLaunches.single().seat2Deck shouldBe "[Commander]\n1 Beta Card\n60 Beta Card"
                }
            }
        }

        test("spectator feed declines mixed-format pairs") {
            withWeb(json) {
                repos.deck.save(spectatorDeck("alpha-deck", "Alpha Deck", grpId = 100))
                repos.deck.save(spectatorDeck("beta-deck", "Beta Deck", grpId = 101, format = Format.Brawl))

                client.post("/api/public/spectator/start").status shouldBe HttpStatusCode.ServiceUnavailable
                greLaunches shouldBe emptyList()
            }
        }

        test("spectator feed declines a deck whose cards cannot be named") {
            withWeb(json) {
                repos.deck.save(spectatorDeck("known-deck", "Known Deck", grpId = 100))
                repos.deck.save(spectatorDeck("unknown-deck", "Unknown Deck", grpId = 999_999))

                client.post("/api/public/spectator/start").status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }

        test("mints a guest session that can start a catalog puzzle") {
            withWeb(
                json,
                puzzleCatalog = { listOf(puzzleSummary("stock-up")) },
            ) {
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
            withWeb(
                json,
                puzzleCatalog = { listOf(puzzleSummary("stock-up")) },
            ) {
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

        test("guest session cannot start an arbitrary deck match") {
            withWeb(json) {
                val guest = client.post("/api/auth/guest")
                val cookie = checkNotNull(guest.headers[HttpHeaders.SetCookie]).substringBefore(";")
                val start =
                    client.post("/api/gre/start") {
                        auth(cookie)
                        jsonBody("""{"seat1Deck":"60 Plains","seat2Deck":"60 Island"}""")
                    }

                assertSoftly {
                    start.status shouldBe HttpStatusCode.Forbidden
                    greLaunches shouldBe emptyList()
                }
            }
        }

        test("guest session cannot supply a match id for a catalog puzzle") {
            withWeb(
                json,
                puzzleCatalog = { listOf(puzzleSummary("stock-up")) },
            ) {
                val guest = client.post("/api/auth/guest")
                val cookie = checkNotNull(guest.headers[HttpHeaders.SetCookie]).substringBefore(";")
                val start =
                    client.post("/api/gre/start") {
                        auth(cookie)
                        jsonBody("""{"puzzle":"stock-up","matchId":"match-1"}""")
                    }

                assertSoftly {
                    start.status shouldBe HttpStatusCode.Forbidden
                    greLaunches shouldBe emptyList()
                }
            }
        }

        test("account session can start a deck match") {
            withWeb(json) {
                val login = login()
                val start =
                    client.post("/api/gre/start") {
                        auth(login)
                        jsonBody("""{"seat1Deck":"60 Plains","seat2Deck":"60 Island"}""")
                    }

                assertSoftly {
                    start.status shouldBe HttpStatusCode.OK
                    greLaunches.single().seat1Deck shouldBe "60 Plains"
                }
            }
        }

        test("does not expose a public arbitrary GRE start") {
            withWeb(json) {
                client
                    .post("/api/public/gre/start") {
                        jsonBody("""{"seat1Deck":"60 Plains"}""")
                    }.status shouldBe HttpStatusCode.NotFound
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

        test("public GRE WebSocket attachments forward the handshake but stay read-only for game messages") {
            withWeb(json) {
                repos.registerGre(
                    "public",
                    reply = byteArrayOf(9, 8, 7),
                    ownerPlayerId = PlayerId("owner"),
                    publicAccess = true,
                )

                val auth =
                    ClientToMatchServiceMessage
                        .newBuilder()
                        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.AuthenticateRequest_f487)
                        .build()
                        .toByteArray()
                val gameMessage =
                    ClientToMatchServiceMessage
                        .newBuilder()
                        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.ClientToGremessage)
                        .build()
                        .toByteArray()

                publicGreSocket("public") {
                    // The connect handshake must reach the engine — without it the
                    // spectator stream never starts.
                    send(Frame.Binary(fin = true, data = auth))
                    val frame = incoming.receive() as Frame.Binary
                    frame.readBytes().contentEquals(byteArrayOf(9, 8, 7)) shouldBe true
                    repos.enginePayloads.size shouldBe 1

                    // Game messages from a viewer are dropped at the relay.
                    send(Frame.Binary(fin = true, data = gameMessage))
                    withTimeoutOrNull(100) { incoming.receive() } shouldBe null
                    repos.enginePayloads.size shouldBe 1
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

                // Idle close waits out the reconnect grace before tearing down.
                val deadline = System.currentTimeMillis() + 5_000
                while (!closed.get() && System.currentTimeMillis() < deadline) delay(20)
                assertSoftly {
                    closed.get() shouldBe true
                    cleanupCount shouldBe 1
                }
            }
        }

        test("GRE relay serializes engine access per match") {
            withWeb(json) {
                val login = login()
                lateinit var engine: ConcurrentProbeGreEngineSession
                repos.relay.register("serialized", ownerPlayerId = PlayerId(login.playerId)) { onFrame, _ ->
                    ConcurrentProbeGreEngineSession(onFrame).also { engine = it }
                }
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

        test("GRE relay streams engine frames as they're produced, not batched until the call returns") {
            withWeb(json) {
                val login = login()
                repos.relay.register("streamed", ownerPlayerId = PlayerId(login.playerId)) { onFrame, _ ->
                    SlowMultiStepGreEngineSession(onFrame, stepDelayMs = 400)
                }

                greSocket(login, "streamed") {
                    val start = System.currentTimeMillis()
                    send(Frame.Binary(fin = true, data = byteArrayOf(1)))

                    // The engine's first frame lands well before its second (400ms later) —
                    // if the relay batched until receiveFromBrowser returned, both would only
                    // become visible together, after the full ~800ms round trip.
                    val firstFrame = withTimeoutOrNull(200) { (incoming.receive() as Frame.Binary).readBytes() }
                    val elapsedAtFirst = System.currentTimeMillis() - start
                    val secondFrame = withTimeoutOrNull(600) { (incoming.receive() as Frame.Binary).readBytes() }
                    val elapsedAtSecond = System.currentTimeMillis() - start

                    assertSoftly {
                        firstFrame shouldBe byteArrayOf(1)
                        secondFrame shouldBe byteArrayOf(2)
                        // First frame observed well before the engine call (2x 400ms) completes.
                        elapsedAtFirst shouldBeLessThan 400
                        elapsedAtSecond shouldBeGreaterThanOrEqual 400
                    }
                }
            }
        }

        test("engine crash disconnects attached browsers instead of hanging silently") {
            withWeb(json) {
                val login = login()
                val matchId = "crash-me"
                val runtimeMatchConfigs = RuntimeMatchConfigRegistry()
                runtimeMatchConfigs.put(RuntimeMatchConfig(matchId = matchId, puzzle = "/no/such/puzzle.pzl"))
                repos.relay.register(matchId, ownerPlayerId = PlayerId(login.playerId)) { onFrame, onClosed ->
                    DirectWebGreEngineSession(MatchConfig(), MatchCoordinator.NOOP, repos.cards, runtimeMatchConfigs, onFrame, onClosed)
                }

                greSocket(login, matchId) {
                    send(Frame.Binary(fin = true, data = authRequestBytes("web-player")))
                    // Connect references a puzzle file that doesn't exist — PuzzleHandler
                    // throws, MatchHandler.exceptionCaught tears the match down internally.
                    // Without the onClosed wiring the browser would just hang here forever.
                    send(Frame.Binary(fin = true, data = connectRequestBytes(matchId, seatId = 1)))

                    shouldThrow<ClosedReceiveChannelException> {
                        while (true) incoming.receive()
                    }
                }
            }
        }

        test("separate web engines keep the first match usable after the second connects") {
            val cards =
                InMemoryCardRepository().also {
                    it.registerData(
                        CardData(
                            grpId = 1,
                            titleId = 1,
                            power = "",
                            toughness = "",
                            colors = emptyList(),
                            types = listOf(CardType.Land_a80b.number),
                            subtypes = emptyList(),
                            supertypes = emptyList(),
                            abilityIds = emptyList(),
                            manaCost = emptyList(),
                        ),
                        "Mountain",
                    )
                    it.registerData(
                        CardData(
                            grpId = 2,
                            titleId = 2,
                            power = "",
                            toughness = "",
                            colors = listOf(CardColor.Red_a3b0.number),
                            types = listOf(CardType.Instant.number),
                            subtypes = emptyList(),
                            supertypes = emptyList(),
                            abilityIds = emptyList(),
                            manaCost = listOf(ManaColor.Red_afc9 to 1),
                        ),
                        "Lightning Bolt",
                    )
                }
            val puzzle =
                Files.createTempFile("web-engine-isolation", ".pzl").toFile().apply {
                    writeText(
                        """
                        [metadata]
                        Name:Bolt Face
                        Goal:Win
                        Turns:1
                        Difficulty:Easy
                        Description:Bolt face.

                        [state]
                        ActivePlayer=Human
                        ActivePhase=Main1
                        HumanLife=20
                        AILife=3

                        humanhand=Lightning Bolt
                        humanbattlefield=Mountain
                        humanlibrary=Mountain
                        ailibrary=Mountain
                        """.trimIndent(),
                    )
                }
            val configs = RuntimeMatchConfigRegistry()
            val matchA = "web-engine-a"
            val matchB = "web-engine-b"
            configs.put(RuntimeMatchConfig(matchId = matchA, puzzle = puzzle.absolutePath))
            configs.put(RuntimeMatchConfig(matchId = matchB, puzzle = puzzle.absolutePath))
            val framesA = CopyOnWriteArrayList<ByteArray>()
            val framesB = CopyOnWriteArrayList<ByteArray>()
            val closedA = AtomicBoolean(false)
            val engineA =
                DirectWebGreEngineSession(
                    MatchConfig(),
                    MatchCoordinator.NOOP,
                    cards,
                    configs,
                    framesA::add,
                    { closedA.set(true) },
                )
            val engineB =
                DirectWebGreEngineSession(
                    MatchConfig(),
                    MatchCoordinator.NOOP,
                    cards,
                    configs,
                    framesB::add,
                )

            try {
                fun connect(
                    engine: DirectWebGreEngineSession,
                    matchId: String,
                ) {
                    engine.receiveFromBrowser(authRequestBytes("web-player"))
                    engine.receiveFromBrowser(connectRequestBytes(matchId, seatId = 1))
                }

                connect(engineA, matchA)
                connect(engineB, matchB)
                val beforePass = framesA.size
                val actionPrompt =
                    framesA
                        .map(MatchServiceToClientMessage::parseFrom)
                        .flatMap { it.greToClientEvent.greToClientMessagesList }
                        .last { it.hasActionsAvailableReq() }
                engineA.receiveFromBrowser(
                    ClientToMatchServiceMessage
                        .newBuilder()
                        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.ClientToGremessage)
                        .setPayload(
                            ClientToGREMessage
                                .newBuilder()
                                .setSystemSeatId(1)
                                .setType(ClientMessageType.PerformActionResp_097b)
                                .setGameStateId(actionPrompt.gameStateId)
                                .setRespId(actionPrompt.msgId)
                                .setPerformActionResp(
                                    PerformActionResp
                                        .newBuilder()
                                        .addActions(
                                            Action
                                                .newBuilder()
                                                .setActionType(ActionType.Pass),
                                        ),
                                ).build()
                                .toByteString(),
                        ).build()
                        .toByteArray(),
                )

                val postPassTypes =
                    framesA
                        .drop(beforePass)
                        .map(MatchServiceToClientMessage::parseFrom)
                        .flatMap { it.greToClientEvent.greToClientMessagesList }
                        .map { it.type }

                assertSoftly {
                    closedA.get() shouldBe false
                    framesB.shouldNotBeEmpty()
                    postPassTypes shouldContain wotc.mtgo.gre.external.messaging.Messages.GREMessageType.GameStateMessage_695e
                }
            } finally {
                engineA.close()
                engineB.close()
                Files.deleteIfExists(puzzle.toPath())
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

        test("serves public card metadata by grpIds, including nulls for unknown ids") {
            withWeb(json) {
                val response = client.get("/api/public/cards/by-grpids?ids=100,101,999,notanumber")
                val cards = json.parseToJsonElement(response.bodyAsText()).jsonObject

                assertSoftly {
                    response.status shouldBe HttpStatusCode.OK
                    cards.keys shouldBe setOf("100", "101", "999")
                    cards["100"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "Alpha Card"
                    cards["100"]!!.jsonObject["manaCost"]!!.jsonPrimitive.content shouldBe "{G}"
                    cards["100"]!!.jsonObject["types"]!!.jsonPrimitive.content shouldBe "Creature"
                    cards["100"]!!.jsonObject["subtypes"]!!.jsonPrimitive.content shouldBe "Elf"
                    cards["100"]!!.jsonObject["imageUrl"]!!.jsonPrimitive.content shouldBe
                        "https://api.scryfall.com/cards/named?exact=Alpha+Card&format=image&version=normal"
                    cards["101"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "Beta Card"
                    cards["999"]!!.jsonObject["grpId"]!!.jsonPrimitive.content shouldBe "999"
                    cards["999"]!!.jsonObject["name"] shouldBe JsonNull
                }
            }
        }

        test("caps an unbounded ids list on the by-grpids route") {
            withWeb(json) {
                val ids = (1..600).joinToString(",")
                val response = client.get("/api/public/cards/by-grpids?ids=$ids")
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

                assertSoftly {
                    response.status shouldBe HttpStatusCode.OK
                    body.keys.size shouldBe 500
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

        test("starts sealed course with generated pool") {
            withWeb(json) {
                val login = login()
                val response =
                    client.post("/api/sealed/start") {
                        auth(login)
                        jsonBody("""{"playerId":"${login.playerId}","eventName":"Sealed_FDN_20260307"}""")
                    }
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

                assertSoftly {
                    response.status shouldBe HttpStatusCode.OK
                    body["module"]!!.jsonPrimitive.content shouldBe "DeckSelect"
                    body["cardPool"]!!.jsonArray.size shouldBe 2
                }
            }
        }

        test("rejects sealed start for a non-sealed event") {
            withWeb(json) {
                val login = login()
                val response =
                    client.post("/api/sealed/start") {
                        auth(login)
                        jsonBody("""{"playerId":"${login.playerId}","eventName":"QuickDraft_FDN_20260223"}""")
                    }

                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("submits sealed deck, plays, and drops the course") {
            withWeb(json) {
                val login = login()
                client.post("/api/sealed/start") {
                    auth(login)
                    jsonBody("""{"playerId":"${login.playerId}","eventName":"Sealed_FDN_20260307"}""")
                }
                val deckResponse =
                    client.post("/api/sealed/deck") {
                        auth(login)
                        jsonBody(
                            """
                            {"playerId":"${login.playerId}","eventName":"Sealed_FDN_20260307","name":"Sealed Deck",
                            "mainDeck":[{"grpId":100,"quantity":40}]}
                            """.trimIndent(),
                        )
                    }
                val playResponse =
                    client.post("/api/sealed/play") {
                        auth(login)
                        jsonBody("""{"playerId":"${login.playerId}","eventName":"Sealed_FDN_20260307"}""")
                    }
                val dropResponse =
                    client.delete("/api/sealed?playerId=${login.playerId}&eventName=Sealed_FDN_20260307") {
                        auth(login)
                    }

                assertSoftly {
                    deckResponse.status shouldBe HttpStatusCode.OK
                    playResponse.status shouldBe HttpStatusCode.OK
                    dropResponse.status shouldBe HttpStatusCode.NoContent
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

private const val TEST_EMAIL = "player@example.test"

private fun JsonObject.seatName(seat: String): String {
    val seatObject = getValue(seat).jsonObject
    return seatObject.getValue("name").jsonPrimitive.content
}

private fun puzzleSummary(filename: String) =
    PuzzleSummaryView(
        filename = filename,
        name = filename,
    )

private fun spectatorDeck(
    id: String,
    name: String,
    grpId: Int,
    format: Format = Format.Standard,
) = Deck(
    id = DeckId(id),
    playerId = SystemPlayers.SPECTATOR,
    name = name,
    format = format,
    tileId = grpId,
    mainDeck = listOf(DeckCard(grpId, 60)),
    sideboard = emptyList(),
    commandZone = listOf(DeckCard(grpId, 1)).takeIf { format == Format.Brawl }.orEmpty(),
    companions = emptyList(),
)

private const val TEST_EVENT = "QuickDraft_FDN_20260223"

private fun withWeb(
    json: Json,
    puzzleCatalog: () -> List<PuzzleSummaryView> = { emptyList() },
    block: suspend WebFixture.() -> Unit,
) {
    testApplication {
        val repos = TestRepos()
        val greLaunches = mutableListOf<GreStartRequest>()
        val courseService =
            CourseService(
                repos.course,
            ) {
                GeneratedPool(
                    cards = listOf(100, 101),
                    byCollation = listOf(CollationPool(0, listOf(100, 101))),
                    collationId = 0,
                )
            }
        val services =
            WebServices(
                puzzleCatalog = puzzleCatalog,
                draftService = DraftService(repos.draft, StaticDraftDriver(), courseService),
                courseService = courseService,
                deckService = DeckService(repos.deck),
                collectionService = CollectionService { listOf(100, 101) },
                cardRepository = repos.cards,
                authService = WebAuthService(InMemoryWebAuthStore(), repos.emailSender, DEV_WEB_AUTH_SECRET),
                matchLauncher =
                    object : WebMatchLauncher {
                        override fun launchGreMatch(
                            playerId: PlayerId?,
                            request: GreStartRequest,
                        ): DraftPlayResponse {
                            greLaunches += request
                            return DraftPlayResponse("match-1", "wire-1")
                        }

                        override fun launchCourseMatch(
                            playerId: PlayerId,
                            eventName: String,
                        ) = DraftPlayResponse("match-1", "wire-1")
                    },
                greRelay = repos.relay,
                sealedSets = { listOf(LimitedSetView(code = "FDN", name = "Foundations", type = "core", cardCount = 281)) },
            )
        application { installWeb(services) }
        block(WebFixture(client, client.config { install(WebSockets) }, repos, json, greLaunches))
    }
}

private class WebFixture(
    val client: HttpClient,
    val wsClient: HttpClient,
    val repos: TestRepos,
    val json: Json,
    val greLaunches: List<GreStartRequest>,
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
    val course = InMemoryCourseRepository()
    val draft = InMemoryDraftSessionRepository()
    val deck = InMemoryDeckRepository()
    val emailSender = DevEmailSender()
    val enginePayloads = mutableListOf<ByteArray>()
    val relay = InProcessWebGreRelay(idleCloseGraceMs = 50)
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
            ownerPlayerId = ownerPlayerId,
            publicAccess = publicAccess,
            onClose = onClose,
        ) { onFrame, _ -> StaticGreEngineSession(enginePayloads, reply, onFrame, closed) }
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

/** Emits two frames [stepDelayMs] apart from within one [receiveFromBrowser] call. */
private class SlowMultiStepGreEngineSession(
    private val onFrame: (ByteArray) -> Unit,
    private val stepDelayMs: Long,
) : WebGreEngineSession {
    override fun receiveFromBrowser(payload: ByteArray) {
        onFrame(byteArrayOf(1))
        LockSupport.parkNanos(stepDelayMs * 1_000_000)
        onFrame(byteArrayOf(2))
    }

    override fun close() = Unit
}

private class StaticGreEngineSession(
    private val received: MutableList<ByteArray>,
    private val reply: ByteArray,
    private val onFrame: (ByteArray) -> Unit,
    private val closed: AtomicBoolean = AtomicBoolean(false),
) : WebGreEngineSession {
    override fun receiveFromBrowser(payload: ByteArray) {
        received += payload
        onFrame(reply)
    }

    override fun close() {
        closed.set(true)
    }
}

private class ConcurrentProbeGreEngineSession(
    private val onFrame: (ByteArray) -> Unit,
) : WebGreEngineSession {
    val receivedCount = AtomicInteger(0)
    val maxConcurrent = AtomicInteger(0)
    private val active = AtomicInteger(0)

    override fun receiveFromBrowser(payload: ByteArray) {
        val nowActive = active.incrementAndGet()
        maxConcurrent.updateAndGet { current -> maxOf(current, nowActive) }
        try {
            LockSupport.parkNanos(50_000_000)
            receivedCount.incrementAndGet()
            onFrame(payload)
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
