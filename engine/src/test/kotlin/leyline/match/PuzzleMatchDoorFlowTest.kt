package leyline.match

import com.google.protobuf.ByteString
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.netty.channel.embedded.EmbeddedChannel
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.config.PuzzleDefinition
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.game.generator.PuzzleLibrary
import leyline.game.generator.PuzzleSource
import leyline.infra.ListMessageSink
import leyline.infra.MatchOutput
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.ConnectReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.PerformActionResp
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives a puzzle match through [MatchHandler] the same way the web GRE relay
 * does — a [RuntimeMatchConfigRegistry]-configured matchId over a single
 * [EmbeddedChannel] with no separate Familiar connection (the headless runtime
 * never opens a second socket for the AI seat).
 */
class PuzzleMatchDoorFlowTest :
    FunSpec({

        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        fun tempPuzzleFile(): File =
            File.createTempFile("leyline-puzzle-matchdoor-", ".pzl").apply {
                deleteOnExit()
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

        fun serviceMessage(
            type: ClientToMatchServiceMessageType,
            payload: ByteString,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            ClientToMatchServiceMessage
                .newBuilder()
                .setRequestId(requestId)
                .setClientToMatchServiceMessageType(type)
                .setPayload(payload)
                .build()

        fun greMessage(
            seatId: Int,
            type: ClientMessageType,
            customize: ClientToGREMessage.Builder.() -> Unit = {},
        ): ClientToGREMessage =
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(seatId)
                .setType(type)
                .apply(customize)
                .build()

        fun auth(
            clientId: String,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            serviceMessage(
                ClientToMatchServiceMessageType.AuthenticateRequest_f487,
                AuthenticateRequest
                    .newBuilder()
                    .setClientId(clientId)
                    .setPlayerName(clientId)
                    .build()
                    .toByteString(),
                requestId,
            )

        fun connect(
            matchId: String,
            seatId: Int,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            serviceMessage(
                ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487,
                ClientToMatchDoorConnectRequest
                    .newBuilder()
                    .setMatchId(matchId)
                    .setClientToGreMessageBytes(
                        greMessage(seatId, ClientMessageType.ConnectReq_097b) {
                            setConnectReq(ConnectReq.newBuilder())
                        }.toByteString(),
                    ).build()
                    .toByteString(),
                requestId,
            )

        fun greServiceMessage(
            gre: ClientToGREMessage,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            serviceMessage(
                ClientToMatchServiceMessageType.ClientToGremessage,
                gre.toByteString(),
                requestId,
            )

        fun greOutbound(channel: EmbeddedChannel): List<GREToClientMessage> =
            generateSequence { channel.readOutbound<MatchServiceToClientMessage>() }
                .filter { it.hasGreToClientEvent() }
                .flatMap { it.greToClientEvent.greToClientMessagesList }
                .toList()

        fun passPriority(prompt: GREToClientMessage): ClientToGREMessage =
            greMessage(1, ClientMessageType.PerformActionResp_097b) {
                setGameStateId(prompt.gameStateId)
                setRespId(prompt.msgId)
                setPerformActionResp(
                    PerformActionResp
                        .newBuilder()
                        .addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                )
            }

        test("puzzle replacement publishes outside the registry key and routes the next action to its new game") {
            val registry = MatchRegistry()
            val configs = RuntimeMatchConfigRegistry()
            val matchId = "puzzle-registration-owner"
            val temp = tempPuzzleFile()
            val frames = CopyOnWriteArrayList<MatchServiceToClientMessage>()
            val registerFamiliarOnFrame = AtomicBoolean(false)
            val executor = Executors.newSingleThreadExecutor()
            val familiar = FamiliarSession(SeatId(2), matchId, ListMessageSink())
            val output =
                object : MatchOutput {
                    override fun send(message: MatchServiceToClientMessage) {
                        frames.add(message)
                        if (registerFamiliarOnFrame.compareAndSet(true, false)) {
                            // A synchronous host output callback may wait for another
                            // thread's registry work on the same match key.
                            executor.submit { registry.registerSession(matchId, SeatId(2), familiar) }.get(5, TimeUnit.SECONDS)
                        }
                    }

                    override fun close() {}
                }
            val connection =
                MatchConnection(
                    registry,
                    output,
                    EngineSettings(),
                    PuzzleLibrary(temp.parentFile),
                    cardRepository = TestCardRegistry.repo,
                    runtimeMatchConfigs = configs,
                )
            try {
                configs.put(RuntimeMatchConfig(matchId, puzzleDefinition = PuzzleDefinition(temp.nameWithoutExtension, temp.readText())))
                connection.receive(auth("puzzle-player", 1))
                connection.receive(connect(matchId, seatId = 1, requestId = 2))
                val original = registry.activeHumanSession().shouldNotBeNull()
                val originalGame = original.gameBridge.getGame().shouldNotBeNull()
                temp.writeText(temp.readText().replace("humanhand=Lightning Bolt", "humanhand=Mountain"))
                val puzzle = PuzzleSource.load(PuzzleLibrary(temp.parentFile).require(temp.nameWithoutExtension))
                registerFamiliarOnFrame.set(true)

                original.replaceForPuzzle(puzzle)

                val replacement = registry.activeHumanSession().shouldNotBeNull()
                val replacementGame = replacement.gameBridge.getGame().shouldNotBeNull()
                val sequence = replacement.gameBridge.committedSequence()
                shouldThrow<IllegalStateException> { original.replaceForPuzzle(puzzle) }
                assertSoftly {
                    replacement shouldNotBeSameInstanceAs original
                    replacementGame shouldNotBeSameInstanceAs originalGame
                    replacement.connection shouldBeSameInstanceAs original.connection
                    connection.session shouldBeSameInstanceAs replacement
                    registry.getPeer(matchId, SeatId(2)) shouldBeSameInstanceAs replacement
                    registry.getPeer(matchId, SeatId(1)) shouldBeSameInstanceAs familiar
                    registry.getConnection(matchId, SeatId(1)) shouldBeSameInstanceAs connection
                    replacement.gameBridge.getGame() shouldBeSameInstanceAs replacementGame
                    replacement.gameBridge.committedSequence() shouldBe sequence
                    registerFamiliarOnFrame.get() shouldBe false
                }
                val prompt = frames.flatMap { it.greToClientEvent.greToClientMessagesList }.last { it.hasActionsAvailableReq() }
                val land = prompt.actionsAvailableReq.actionsList.single { it.actionType == ActionType.Play_add3 }
                connection.submitGREMessage(
                    greMessage(1, ClientMessageType.PerformActionResp_097b) {
                        setGameStateId(prompt.gameStateId)
                        setRespId(prompt.msgId)
                        setPerformActionResp(PerformActionResp.newBuilder().addActions(land))
                    },
                )
                assertSoftly {
                    replacement.gameBridge.getHandGrpIds(SeatId(1)) shouldBe emptyList()
                    replacement.ctx.game shouldBeSameInstanceAs replacementGame
                    frames
                        .flatMap { it.greToClientEvent.greToClientMessagesList }
                        .none { it.type == GREMessageType.IllegalRequest } shouldBe true
                }
            } finally {
                registry.teardownMatch(matchId, MatchTeardownReason.Disconnect)
                executor.shutdownNow()
                executor.awaitTermination(2, TimeUnit.SECONDS) shouldBe true
                temp.delete()
            }
        }

        test("puzzle match over a single web-shaped EmbeddedChannel: connect sends bundle, Pass advances the loop") {
            val registry = MatchRegistry()
            val runtimeMatchConfigs = RuntimeMatchConfigRegistry()
            val matchId = "web-puzzle-1"
            val temp = tempPuzzleFile()

            try {
                runtimeMatchConfigs.put(
                    RuntimeMatchConfig(
                        matchId = matchId,
                        puzzleDefinition = PuzzleDefinition(temp.nameWithoutExtension, temp.readText()),
                    ),
                )
                val handler =
                    MatchHandler(
                        registry = registry,
                        engineSettings = EngineSettings(),
                        cardRepository = TestCardRegistry.repo,
                        runtimeMatchConfigs = runtimeMatchConfigs,
                    )
                val channel = EmbeddedChannel(handler)

                channel.writeInbound(auth("web-player", 1))
                greOutbound(channel)

                channel.writeInbound(connect(matchId, seatId = 1, requestId = 2))
                val connectMessages = greOutbound(channel)
                val connectTypes = connectMessages.map { it.type }

                channel.writeInbound(
                    greServiceMessage(passPriority(connectMessages.last { it.hasActionsAvailableReq() }), 3),
                )
                val postPassTypes = greOutbound(channel).map { it.type }

                assertSoftly {
                    connectTypes.take(3) shouldBe
                        listOf(
                            GREMessageType.ConnectResp_695e,
                            GREMessageType.GameStateMessage_695e,
                            GREMessageType.ActionsAvailableReq_695e,
                        )
                    // Reproduces the reported hang if this is empty: the human passed
                    // priority but the loop never re-emits state for the next stop.
                    postPassTypes shouldContain GREMessageType.GameStateMessage_695e
                }
            } finally {
                temp.delete()
            }
        }

        test("a configured puzzle identity resolves against the library root") {
            val registry = MatchRegistry()
            val runtimeMatchConfigs = RuntimeMatchConfigRegistry()
            val matchId = "web-puzzle-name-1"
            val temp = tempPuzzleFile()

            try {
                runtimeMatchConfigs.put(RuntimeMatchConfig(matchId = matchId, puzzle = temp.nameWithoutExtension))
                val handler =
                    MatchHandler(
                        registry = registry,
                        engineSettings = EngineSettings(),
                        puzzlesDir = temp.parentFile,
                        cardRepository = TestCardRegistry.repo,
                        runtimeMatchConfigs = runtimeMatchConfigs,
                    )
                val channel = EmbeddedChannel(handler)

                channel.writeInbound(auth("web-player", 1))
                greOutbound(channel)

                channel.writeInbound(connect(matchId, seatId = 1, requestId = 2))
                val connectTypes = greOutbound(channel).map { it.type }

                assertSoftly {
                    channel.isActive shouldBe true
                    connectTypes.take(3) shouldBe
                        listOf(
                            GREMessageType.ConnectResp_695e,
                            GREMessageType.GameStateMessage_695e,
                            GREMessageType.ActionsAvailableReq_695e,
                        )
                }
            } finally {
                temp.delete()
            }
        }

        test(
            "a second Auth+Connect handshake on the same web-shaped channel (reconnect) " +
                "resyncs instead of silently killing the shared engine",
        ) {
            val registry = MatchRegistry()
            val runtimeMatchConfigs = RuntimeMatchConfigRegistry()
            val matchId = "web-puzzle-reconnect"
            val temp = tempPuzzleFile()

            try {
                runtimeMatchConfigs.put(
                    RuntimeMatchConfig(
                        matchId = matchId,
                        puzzleDefinition = PuzzleDefinition(temp.nameWithoutExtension, temp.readText()),
                    ),
                )
                val handler =
                    MatchHandler(
                        registry = registry,
                        engineSettings = EngineSettings(),
                        cardRepository = TestCardRegistry.repo,
                        runtimeMatchConfigs = runtimeMatchConfigs,
                    )
                // One EmbeddedChannel for the whole match lifetime — mirrors
                // EmbeddedWebGreEngineSession, which builds exactly one MatchHandler
                // per matchId and reuses it across every browser (re)attach, unlike
                // native's one-MatchHandler-per-TCP-connection model.
                val channel = EmbeddedChannel(handler)

                channel.writeInbound(auth("web-player", 1))
                greOutbound(channel)
                channel.writeInbound(connect(matchId, seatId = 1, requestId = 2))
                val firstConnectMessages = greOutbound(channel)
                val firstConnectTypes = firstConnectMessages.map { it.type }
                val firstSession = registry.activeHumanSession().shouldNotBeNull()

                // A reconnect (page reload, retried websocket, duplicate attach) replays
                // the handshake on the SAME shared engine instance.
                channel.writeInbound(auth("web-player", 3))
                val reconnectAuthReply =
                    generateSequence { channel.readOutbound<MatchServiceToClientMessage>() }.toList()

                channel.writeInbound(connect(matchId, seatId = 1, requestId = 4))
                val reconnectConnectMessages = greOutbound(channel)
                val reconnectConnectTypes = reconnectConnectMessages.map { it.type }
                val reconnectedSession = registry.activeHumanSession().shouldNotBeNull()

                channel.writeInbound(
                    greServiceMessage(
                        passPriority(reconnectConnectMessages.last { it.hasActionsAvailableReq() }),
                        5,
                    ),
                )
                val postReconnectTypes = greOutbound(channel).map { it.type }

                assertSoftly {
                    firstConnectTypes shouldContain GREMessageType.ActionsAvailableReq_695e
                    reconnectAuthReply.any { it.hasAuthenticateResponse() } shouldBe true
                    // Resync bundle: full state + actions again, not a crash.
                    reconnectConnectTypes shouldContain GREMessageType.GameStateMessage_695e
                    reconnectConnectTypes shouldContain GREMessageType.ActionsAvailableReq_695e
                    // The engine still works after the resync — Pass keeps driving the loop.
                    postReconnectTypes shouldContain GREMessageType.GameStateMessage_695e
                    channel.isActive shouldBe true
                    reconnectedSession shouldNotBeSameInstanceAs firstSession
                    handler.connection.session shouldBeSameInstanceAs reconnectedSession
                    registry.getPeer(matchId, SeatId(2)) shouldBeSameInstanceAs reconnectedSession
                    registry.getConnection(matchId, SeatId(1)) shouldBeSameInstanceAs handler.connection
                }
            } finally {
                temp.delete()
            }
        }
    })
