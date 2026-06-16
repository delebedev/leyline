package leyline.match

import com.google.protobuf.ByteString
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.netty.channel.embedded.EmbeddedChannel
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.GameConfig
import leyline.config.MatchConfig
import leyline.config.RuntimeDecks
import leyline.config.ServerConfig
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ChooseStartingPlayerResp
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.ConnectReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MulliganOption
import wotc.mtgo.gre.external.messaging.Messages.MulliganResp
import wotc.mtgo.gre.external.messaging.Messages.TeamType

class MatchDoorMulliganFlowTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        val deck = "60 Forest"

        fun matchConfig() =
            MatchConfig(
                server =
                    ServerConfig(
                        bridgeTimeoutMs = 2_000L,
                        promptFailsafeMs = 2_000L,
                        aiTurnWaitMs = 2_000L,
                        mulliganWaitMs = 2_000L,
                    ),
                game = GameConfig(seed = 42L, dieRollWinner = 1, skipMulligan = false),
            )

        fun handler(registry: MatchRegistry) =
            MatchHandler(
                registry = registry,
                matchConfig = matchConfig(),
                cardRepository = TestCardRegistry.repo,
                runtimeDecks = { RuntimeDecks(seat1Deck = deck, seat2Deck = deck) },
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

        fun connectPair(
            registry: MatchRegistry,
            matchId: String,
        ): Pair<EmbeddedChannel, EmbeddedChannel> {
            val local = EmbeddedChannel(handler(registry))
            val familiar = EmbeddedChannel(handler(registry))

            local.writeInbound(auth("local-player", 1))
            familiar.writeInbound(auth("local-player_Familiar", 2))
            greOutbound(local)
            greOutbound(familiar)

            local.writeInbound(connect(matchId, seatId = 1, requestId = 3))
            familiar.writeInbound(connect(matchId, seatId = 2, requestId = 4))
            greOutbound(local)
            greOutbound(familiar)
            return local to familiar
        }

        fun chooseStartingPlayer(seatId: Int = 1): ClientToGREMessage =
            greMessage(2, ClientMessageType.ChooseStartingPlayerResp_097b) {
                setChooseStartingPlayerResp(
                    ChooseStartingPlayerResp
                        .newBuilder()
                        .setTeamType(TeamType.Individual)
                        .setSystemSeatId(seatId)
                        .setTeamId(seatId),
                )
            }

        fun mulliganDecision(decision: MulliganOption): ClientToGREMessage =
            greMessage(1, ClientMessageType.MulliganResp_097b) {
                setMulliganResp(MulliganResp.newBuilder().setDecision(decision))
            }

        test("normal keep flows through MatchHandler mulligan request and response path") {
            val registry = MatchRegistry()
            val matchId = "mulligan-flow-keep"
            val (local, familiar) = connectPair(registry, matchId)

            try {
                familiar.writeInbound(greServiceMessage(chooseStartingPlayer(), 5))
                val mulliganPrompt = greOutbound(local).map { it.type }

                local.writeInbound(greServiceMessage(mulliganDecision(MulliganOption.AcceptHand), 6))
                val postKeep = greOutbound(local).map { it.type }

                assertSoftly {
                    mulliganPrompt shouldContain GREMessageType.GameStateMessage_695e
                    mulliganPrompt shouldContain GREMessageType.PromptReq
                    mulliganPrompt shouldContain GREMessageType.MulliganReq_aa0d
                    postKeep shouldContain GREMessageType.GameStateMessage_695e
                    postKeep shouldContain GREMessageType.ActionsAvailableReq_695e
                    (registry.getHandler(matchId, leyline.bridge.types.SeatId(1))?.session as MatchSession)
                        .gameBridge
                        .getGame()
                        ?.isGameOver shouldBe false
                }
            } finally {
                local.close()
                familiar.close()
            }
        }

        test("mulligan then keep re-deals through MatchHandler response path") {
            val registry = MatchRegistry()
            val matchId = "mulligan-flow-redraw"
            val (local, familiar) = connectPair(registry, matchId)

            try {
                familiar.writeInbound(greServiceMessage(chooseStartingPlayer(), 5))
                greOutbound(local)

                local.writeInbound(greServiceMessage(mulliganDecision(MulliganOption.Mulligan), 6))
                val redrawPrompt = greOutbound(local)
                val redrawTypes = redrawPrompt.map { it.type }
                val redrawMulligan = redrawPrompt.last { it.type == GREMessageType.MulliganReq_aa0d }.mulliganReq

                local.writeInbound(greServiceMessage(mulliganDecision(MulliganOption.AcceptHand), 7))
                val postKeep = greOutbound(local).map { it.type }

                assertSoftly {
                    redrawTypes shouldContain GREMessageType.GameStateMessage_695e
                    redrawTypes shouldContain GREMessageType.PromptReq
                    redrawTypes shouldContain GREMessageType.MulliganReq_aa0d
                    redrawMulligan.mulliganCount shouldBe 0
                    postKeep shouldContain GREMessageType.GameStateMessage_695e
                    postKeep shouldContain GREMessageType.ActionsAvailableReq_695e
                }
            } finally {
                local.close()
                familiar.close()
            }
        }
    })
