package leyline.match

import com.google.protobuf.ByteString
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.netty.channel.embedded.EmbeddedChannel
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
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

/**
 * Drives a puzzle match through [MatchHandler] the same way the web GRE relay
 * does — a [RuntimeMatchConfigRegistry]-configured matchId over a single
 * [EmbeddedChannel] with no separate Familiar connection (the web profile
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

        test("puzzle match over a single web-shaped EmbeddedChannel: connect sends bundle, Pass advances the loop") {
            val registry = MatchRegistry()
            val runtimeMatchConfigs = RuntimeMatchConfigRegistry()
            val matchId = "web-puzzle-1"
            val temp = tempPuzzleFile()

            try {
                runtimeMatchConfigs.put(RuntimeMatchConfig(matchId = matchId, puzzle = temp.absolutePath))
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

        test("a bare puzzle name in the runtime match config resolves against the puzzles directory") {
            val registry = MatchRegistry()
            val runtimeMatchConfigs = RuntimeMatchConfigRegistry()
            val matchId = "web-puzzle-name-1"

            runtimeMatchConfigs.put(RuntimeMatchConfig(matchId = matchId, puzzle = "warmup-land-permanent"))
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
                runtimeMatchConfigs.put(RuntimeMatchConfig(matchId = matchId, puzzle = temp.absolutePath))
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

                // A reconnect (page reload, retried websocket, duplicate attach) replays
                // the handshake on the SAME shared engine instance.
                channel.writeInbound(auth("web-player", 3))
                val reconnectAuthReply =
                    generateSequence { channel.readOutbound<MatchServiceToClientMessage>() }.toList()

                channel.writeInbound(connect(matchId, seatId = 1, requestId = 4))
                val reconnectConnectMessages = greOutbound(channel)
                val reconnectConnectTypes = reconnectConnectMessages.map { it.type }

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
                }
            } finally {
                temp.delete()
            }
        }
    })
