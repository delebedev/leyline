package leyline.match

import com.google.protobuf.ByteString
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.netty.channel.embedded.EmbeddedChannel
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.service.MatchCoordinator
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MatchDoorMulliganFlowTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        val deck = "60 Forest"

        fun engineSettings() =
            EngineSettings(
                seed = 42L,
                dieRollWinner = 1,
                skipMulligan = false,
                bridgeTimeoutMs = 2_000L,
                promptFailsafeMs = 2_000L,
                aiTurnWaitMs = 2_000L,
                mulliganWaitMs = 2_000L,
            )

        val runtimeMatchConfigs = RuntimeMatchConfigRegistry()

        fun handler(registry: MatchRegistry) =
            MatchHandler(
                registry = registry,
                engineSettings = engineSettings(),
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
            deckList: String = deck,
        ): Pair<EmbeddedChannel, EmbeddedChannel> {
            runtimeMatchConfigs.put(RuntimeMatchConfig(matchId = matchId, seat1Deck = deckList, seat2Deck = deckList))
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

        test("one-shot AI deck override is consumed once per shared match") {
            val registry = MatchRegistry()
            val configs = RuntimeMatchConfigRegistry()
            val override = AtomicReference<String?>("Green test")
            val overrideReads = AtomicInteger()
            val forestGrpId = TestCardRegistry.repo.findGrpIdByName("Forest")!!
            val mountainGrpId = TestCardRegistry.repo.findGrpIdByName("Mountain")!!
            val coordinator =
                object : MatchCoordinator by MatchCoordinator.NOOP {
                    override fun resolveDeckJsonByName(name: String): String? =
                        if (name == "Green test") {
                            """{"MainDeck":[{"cardId":$forestGrpId,"quantity":60}]}"""
                        } else {
                            null
                        }
                }

            fun testHandler() =
                MatchHandler(
                    registry = registry,
                    engineSettings = engineSettings(),
                    coordinator = coordinator,
                    cardRepository = TestCardRegistry.repo,
                    runtimeMatchConfigs = configs,
                    aiDeckNameOverride = {
                        overrideReads.incrementAndGet()
                        override.getAndSet(null)
                    },
                )

            fun connectWithSeatOneDeck(matchId: String): Pair<EmbeddedChannel, EmbeddedChannel> {
                configs.put(RuntimeMatchConfig(matchId = matchId, seat1Deck = "60 Mountain"))
                val local = EmbeddedChannel(testHandler())
                val familiar = EmbeddedChannel(testHandler())
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

            val first = connectWithSeatOneDeck("ai-override-first")
            try {
                assertSoftly {
                    override.get() shouldBe null
                    overrideReads.get() shouldBe 1
                    registry
                        .getMatch("ai-override-first")!!
                        .bridge
                        .getDeckGrpIds(SeatId(2))
                        .toSet() shouldBe setOf(forestGrpId)
                }
            } finally {
                first.first.close()
                first.second.close()
            }

            val second = connectWithSeatOneDeck("ai-override-second")
            try {
                assertSoftly {
                    overrideReads.get() shouldBe 2
                    registry
                        .getMatch("ai-override-second")!!
                        .bridge
                        .getDeckGrpIds(SeatId(2))
                        .toSet() shouldBe setOf(mountainGrpId)
                }
            } finally {
                second.first.close()
                second.second.close()
            }
        }

        fun chooseStartingPlayer(
            respId: Int,
            seatId: Int = 1,
        ): ClientToGREMessage =
            greMessage(2, ClientMessageType.ChooseStartingPlayerResp_097b) {
                setRespId(respId)
                setChooseStartingPlayerResp(
                    ChooseStartingPlayerResp
                        .newBuilder()
                        .setTeamType(TeamType.Individual)
                        .setSystemSeatId(seatId)
                        .setTeamId(seatId),
                )
            }

        fun mulliganDecision(
            decision: MulliganOption,
            respId: Int,
        ): ClientToGREMessage =
            greMessage(1, ClientMessageType.MulliganResp_097b) {
                setRespId(respId)
                setMulliganResp(MulliganResp.newBuilder().setDecision(decision))
            }

        test("normal keep flows through MatchHandler mulligan request and response path") {
            val registry = MatchRegistry()
            val matchId = "mulligan-flow-keep"
            val (local, familiar) = connectPair(registry, matchId)

            try {
                familiar.writeInbound(
                    greServiceMessage(
                        chooseStartingPlayer(
                            registry
                                .getMatch(matchId)!!
                                .bridge.messageCounter
                                .lastPromptMsgId(),
                        ),
                        5,
                    ),
                )
                val mulliganPrompt = greOutbound(local).map { it.type }

                local.writeInbound(
                    greServiceMessage(
                        mulliganDecision(
                            MulliganOption.AcceptHand,
                            registry
                                .getMatch(matchId)!!
                                .bridge.messageCounter
                                .lastPromptMsgId(),
                        ),
                        6,
                    ),
                )
                val postKeepGre = greOutbound(local)
                val postKeep = postKeepGre.map { it.type }
                val session = registry.getConnection(matchId, leyline.bridge.types.SeatId(1))?.session as MatchSession
                val pending = checkNotNull(session.gameBridge.actionBridge(leyline.bridge.types.SeatId(1)).getPending())
                val actionPrompt = postKeepGre.single { it.hasActionsAvailableReq() }

                assertSoftly {
                    mulliganPrompt shouldContain GREMessageType.GameStateMessage_695e
                    mulliganPrompt shouldContain GREMessageType.PromptReq
                    mulliganPrompt shouldContain GREMessageType.MulliganReq_aa0d
                    postKeep shouldContain GREMessageType.GameStateMessage_695e
                    postKeep shouldContain GREMessageType.ActionsAvailableReq_695e
                    pending.promptGameStateId shouldBe actionPrompt.gameStateId
                    session.gameBridge
                        .getGame()
                        ?.isGameOver shouldBe false
                }
            } finally {
                local.close()
                familiar.close()
            }
        }

        test("familiar channel ignores mirrored stale gameplay responses") {
            val registry = MatchRegistry()
            val matchId = "familiar-stale-gameplay-response"
            val (local, familiar) = connectPair(registry, matchId)

            try {
                familiar.writeInbound(
                    greServiceMessage(
                        greMessage(2, ClientMessageType.PerformActionResp_097b) {
                            setRespId(1)
                        },
                        5,
                    ),
                )

                greOutbound(familiar).map { it.type } shouldBe emptyList()
            } finally {
                local.close()
                familiar.close()
            }
        }

        test("two mulligans then keep preserve the final redraw hand") {
            val registry = MatchRegistry()
            val matchId = "mulligan-flow-redraw"
            val mixedDeck =
                """
                30 Forest
                30 Mountain
                """.trimIndent()
            val (local, familiar) = connectPair(registry, matchId, deckList = mixedDeck)

            try {
                familiar.writeInbound(
                    greServiceMessage(
                        chooseStartingPlayer(
                            registry
                                .getMatch(matchId)!!
                                .bridge.messageCounter
                                .lastPromptMsgId(),
                        ),
                        5,
                    ),
                )
                greOutbound(local)
                val session = registry.getConnection(matchId, leyline.bridge.types.SeatId(1))?.session as MatchSession
                val firstHand = session.gameBridge.getHandGrpIds(leyline.bridge.types.SeatId(1))

                local.writeInbound(
                    greServiceMessage(
                        mulliganDecision(
                            MulliganOption.Mulligan,
                            registry
                                .getMatch(matchId)!!
                                .bridge.messageCounter
                                .lastPromptMsgId(),
                        ),
                        6,
                    ),
                )
                val firstRedrawPrompt = greOutbound(local)
                val firstRedrawTypes = firstRedrawPrompt.map { it.type }
                val firstRedrawMulligan = firstRedrawPrompt.last { it.type == GREMessageType.MulliganReq_aa0d }
                val firstRedrawHand = session.gameBridge.getHandGrpIds(leyline.bridge.types.SeatId(1))

                local.writeInbound(
                    greServiceMessage(
                        mulliganDecision(
                            MulliganOption.Mulligan,
                            registry
                                .getMatch(matchId)!!
                                .bridge.messageCounter
                                .lastPromptMsgId(),
                        ),
                        7,
                    ),
                )
                val secondRedrawPrompt = greOutbound(local)
                val secondRedrawMulligan = secondRedrawPrompt.last { it.type == GREMessageType.MulliganReq_aa0d }
                val secondRedrawHand = session.gameBridge.getHandGrpIds(leyline.bridge.types.SeatId(1))

                local.writeInbound(
                    greServiceMessage(
                        mulliganDecision(
                            MulliganOption.AcceptHand,
                            registry
                                .getMatch(matchId)!!
                                .bridge.messageCounter
                                .lastPromptMsgId(),
                        ),
                        8,
                    ),
                )
                val postKeep = greOutbound(local).map { it.type }
                val keptHand = session.gameBridge.getHandGrpIds(leyline.bridge.types.SeatId(1))

                assertSoftly {
                    firstRedrawTypes shouldContain GREMessageType.GameStateMessage_695e
                    firstRedrawTypes shouldContain GREMessageType.PromptReq
                    firstRedrawTypes shouldContain GREMessageType.MulliganReq_aa0d
                    firstRedrawMulligan.mulliganReq.mulliganCount shouldBe 0
                    firstRedrawMulligan.prompt.parametersList.map { it.numberValue } shouldContain 6
                    firstRedrawHand.size shouldBe 6
                    firstRedrawHand shouldNotBe firstHand
                    secondRedrawMulligan.mulliganReq.mulliganCount shouldBe 0
                    secondRedrawMulligan.prompt.parametersList.map { it.numberValue } shouldContain 5
                    secondRedrawHand.size shouldBe 5
                    keptHand shouldBe secondRedrawHand
                    postKeep shouldContain GREMessageType.GameStateMessage_695e
                    postKeep shouldContain GREMessageType.ActionsAvailableReq_695e
                }
            } finally {
                local.close()
                familiar.close()
            }
        }
    })
