package leyline.match

import com.google.protobuf.ByteString
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.netty.channel.embedded.EmbeddedChannel
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.config.AiConfig
import leyline.config.GameConfig
import leyline.config.MatchConfig
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.config.ServerConfig
import leyline.game.EngineCut
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.ConnectReq
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.MatchGameRoomStateType
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import kotlin.time.Duration.Companion.seconds

class SpectatorMatchDoorFlowTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        fun matchConfig() =
            MatchConfig(
                server =
                    ServerConfig(
                        bridgeTimeoutMs = 2_000L,
                        promptFailsafeMs = 2_000L,
                        aiTurnWaitMs = 2_000L,
                    ),
                game = GameConfig(seed = 42L),
                ai = AiConfig(speed = 1.0),
            )

        fun handler(
            registry: MatchRegistry,
            runtimeMatchConfigs: RuntimeMatchConfigRegistry,
        ) = MatchHandler(
            registry = registry,
            matchConfig = matchConfig(),
            cardRepository = TestCardRegistry.repo,
            runtimeMatchConfigs = runtimeMatchConfigs,
        )

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
            requestId: Int,
        ): ClientToMatchServiceMessage {
            val gre =
                ClientToGREMessage
                    .newBuilder()
                    .setSystemSeatId(1)
                    .setType(ClientMessageType.ConnectReq_097b)
                    .setConnectReq(ConnectReq.newBuilder())
                    .build()
            return serviceMessage(
                ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487,
                ClientToMatchDoorConnectRequest
                    .newBuilder()
                    .setMatchId(matchId)
                    .setClientToGreMessageBytes(gre.toByteString())
                    .build()
                    .toByteString(),
                requestId,
            )
        }

        fun drain(channel: EmbeddedChannel): List<MatchServiceToClientMessage> =
            generateSequence { channel.readOutbound<MatchServiceToClientMessage>() }.toList()

        fun gameStateType(message: MatchServiceToClientMessage): GameStateType? =
            message.greToClientEvent.greToClientMessagesList
                .firstOrNull { it.hasGameStateMessage() }
                ?.gameStateMessage
                ?.type

        test("existing spectator reconnect sends RoomState and Full before pending Diff") {
            val registry = MatchRegistry()
            val runtimeMatchConfigs = RuntimeMatchConfigRegistry()
            val matchId = "spectator-reconnect-order"
            runtimeMatchConfigs.put(
                RuntimeMatchConfig(
                    matchId = matchId,
                    seat1Deck = "60 Forest",
                    seat2Deck = "60 Forest",
                    spectatorMode = true,
                ),
            )
            val first = EmbeddedChannel(handler(registry, runtimeMatchConfigs))
            val reconnect = EmbeddedChannel(handler(registry, runtimeMatchConfigs))

            try {
                first.writeInbound(auth("spectator", 1))
                drain(first)
                first.writeInbound(connect(matchId, 2))
                drain(first)

                val active = registry.getConnection(matchId, SeatId(1))?.session as SpectatorSession
                active.close()
                val bridge = checkNotNull(registry.getBridge(matchId))
                eventually(5.seconds) {
                    (
                        bridge.peekEngineCutThrough(bridge.latestEngineCutCheckpoint()) is
                            EngineCut.Observation
                    ).shouldBeTrue()
                }

                reconnect.writeInbound(auth("spectator-reconnect", 3))
                drain(reconnect)
                reconnect.writeInbound(connect(matchId, 4))
                val outbound = drain(reconnect)

                val roomIndex =
                    outbound.indexOfFirst {
                        it.hasMatchGameRoomStateChangedEvent() &&
                            it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                            MatchGameRoomStateType.Playing
                    }
                val fullIndex = outbound.indexOfFirst { gameStateType(it) == GameStateType.Full }
                val diffIndex = outbound.indexOfFirst { gameStateType(it) == GameStateType.Diff }

                assertSoftly {
                    roomIndex shouldBe 0
                    fullIndex shouldBeGreaterThan roomIndex
                    diffIndex shouldBeGreaterThan fullIndex
                }
            } finally {
                reconnect.close()
                first.close()
            }
        }
    })
