package leyline.match

import com.google.protobuf.ByteString
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.netty.channel.embedded.EmbeddedChannel
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.GameConfig
import leyline.config.MatchConfig
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

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

        val runtimeMatchConfigs = RuntimeMatchConfigRegistry()

        fun handler(registry: MatchRegistry) =
            MatchHandler(
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
                val familiarPrompt = greOutbound(familiar).map { it.type }

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
                val postKeep = greOutbound(local).map { it.type }

                assertSoftly {
                    mulliganPrompt shouldContain GREMessageType.GameStateMessage_695e
                    mulliganPrompt shouldContain GREMessageType.PromptReq
                    mulliganPrompt shouldContain GREMessageType.MulliganReq_aa0d
                    familiarPrompt shouldBe emptyList()
                    postKeep shouldContain GREMessageType.GameStateMessage_695e
                    postKeep shouldContain GREMessageType.ActionsAvailableReq_695e
                    (registry.getConnection(matchId, leyline.bridge.types.SeatId(1))?.session as MatchSession)
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
                val redrawPrompt = greOutbound(local)
                val redrawTypes = redrawPrompt.map { it.type }
                val redrawMulligan = redrawPrompt.last { it.type == GREMessageType.MulliganReq_aa0d }.mulliganReq
                val redrawHand = session.gameBridge.getHandGrpIds(leyline.bridge.types.SeatId(1))

                local.writeInbound(
                    greServiceMessage(
                        mulliganDecision(
                            MulliganOption.AcceptHand,
                            registry
                                .getMatch(matchId)!!
                                .bridge.messageCounter
                                .lastPromptMsgId(),
                        ),
                        7,
                    ),
                )
                val postKeep = greOutbound(local).map { it.type }

                assertSoftly {
                    redrawTypes shouldContain GREMessageType.GameStateMessage_695e
                    redrawTypes shouldContain GREMessageType.PromptReq
                    redrawTypes shouldContain GREMessageType.MulliganReq_aa0d
                    redrawMulligan.mulliganCount shouldBe 1
                    redrawHand shouldNotBe firstHand
                    postKeep shouldContain GREMessageType.GameStateMessage_695e
                    postKeep shouldContain GREMessageType.ActionsAvailableReq_695e
                }
            } finally {
                local.close()
                familiar.close()
            }
        }

        test("queued familiar response is revalidated after entering session authority") {
            val registry = MatchRegistry()
            val matchId = "mulligan-flow-authority"
            val (local, familiar) = connectPair(registry, matchId)
            val session = registry.getConnection(matchId, leyline.bridge.types.SeatId(1))?.session as MatchSession
            val authorityEntered = CountDownLatch(1)
            val advancePrompt = CountDownLatch(1)
            val promptAdvanced = CountDownLatch(1)
            val releaseAuthority = CountDownLatch(1)
            val familiarEntrantStarted = CountDownLatch(1)
            val familiarEntrantThread = AtomicReference<Thread>()
            val entrants = Executors.newFixedThreadPool(2)

            try {
                val staleRespId = session.counter.lastPromptMsgId()
                val currentRespId = AtomicReference<Int>()
                val holder =
                    entrants.submit {
                        session.withSessionAuthority {
                            authorityEntered.countDown()
                            advancePrompt.await()
                            val replacementPromptMsgId = session.counter.nextMsgId()
                            session.counter.markPromptMsgId(replacementPromptMsgId)
                            currentRespId.set(replacementPromptMsgId)
                            promptAdvanced.countDown()
                            releaseAuthority.await()
                        }
                    }
                authorityEntered.await(2, TimeUnit.SECONDS) shouldBe true

                val response = chooseStartingPlayer(staleRespId)
                val familiarEntrant =
                    entrants.submit {
                        familiarEntrantThread.set(Thread.currentThread())
                        familiarEntrantStarted.countDown()
                        familiar.writeInbound(greServiceMessage(response, 5))
                    }
                familiarEntrantStarted.await(2, TimeUnit.SECONDS) shouldBe true

                eventually(2.seconds) {
                    familiarEntrantThread.get()?.state shouldBe Thread.State.BLOCKED
                }

                advancePrompt.countDown()
                promptAdvanced.await(2, TimeUnit.SECONDS) shouldBe true
                releaseAuthority.countDown()
                holder.get(2, TimeUnit.SECONDS)
                familiarEntrant.get(2, TimeUnit.SECONDS)

                greOutbound(familiar).map { it.type } shouldContain GREMessageType.IllegalRequest

                familiar.writeInbound(greServiceMessage(chooseStartingPlayer(currentRespId.get()), 6))
                greOutbound(local).map { it.type } shouldContain GREMessageType.MulliganReq_aa0d
            } finally {
                advancePrompt.countDown()
                releaseAuthority.countDown()
                entrants.shutdownNow()
                local.close()
                familiar.close()
            }
        }

        test("familiar connect and initial bundle wait for session authority") {
            val registry = MatchRegistry()
            val matchId = "mulligan-flow-connect-authority"
            runtimeMatchConfigs.put(RuntimeMatchConfig(matchId = matchId, seat1Deck = deck, seat2Deck = deck))
            val local = EmbeddedChannel(handler(registry))
            val familiar = EmbeddedChannel(handler(registry))
            val authorityEntered = CountDownLatch(1)
            val releaseAuthority = CountDownLatch(1)
            val familiarConnectStarted = CountDownLatch(1)
            val familiarConnectThread = AtomicReference<Thread>()
            val entrants = Executors.newFixedThreadPool(2)

            try {
                local.writeInbound(auth("local-player", 1))
                familiar.writeInbound(auth("local-player_Familiar", 2))
                greOutbound(local)
                greOutbound(familiar)
                local.writeInbound(connect(matchId, seatId = 1, requestId = 3))
                greOutbound(local)
                val session = registry.getConnection(matchId, leyline.bridge.types.SeatId(1))?.session as MatchSession
                val initialMsgId = session.counter.currentMsgId()
                val holder =
                    entrants.submit {
                        session.withSessionAuthority {
                            authorityEntered.countDown()
                            releaseAuthority.await()
                        }
                    }
                authorityEntered.await(2, TimeUnit.SECONDS) shouldBe true

                val familiarConnect =
                    entrants.submit {
                        familiarConnectThread.set(Thread.currentThread())
                        familiarConnectStarted.countDown()
                        familiar.writeInbound(connect(matchId, seatId = 2, requestId = 4))
                    }
                familiarConnectStarted.await(2, TimeUnit.SECONDS) shouldBe true
                eventually(2.seconds) {
                    familiarConnectThread.get()?.state shouldBe Thread.State.BLOCKED
                }
                assertSoftly {
                    registry.getConnection(matchId, leyline.bridge.types.SeatId(2)) shouldBe null
                    greOutbound(familiar) shouldBe emptyList()
                    session.counter.currentMsgId() shouldBe initialMsgId
                }

                releaseAuthority.countDown()
                holder.get(2, TimeUnit.SECONDS)
                familiarConnect.get(2, TimeUnit.SECONDS)
                familiar.runPendingTasks()
                assertSoftly {
                    registry.getConnection(matchId, leyline.bridge.types.SeatId(2)) shouldNotBe null
                    session.counter.currentMsgId() shouldNotBe initialMsgId
                    greOutbound(familiar).map { it.type } shouldContain GREMessageType.GameStateMessage_695e
                }
            } finally {
                releaseAuthority.countDown()
                entrants.shutdownNow()
                local.close()
                familiar.close()
            }
        }

        test("familiar can establish the match before the human session exists") {
            val registry = MatchRegistry()
            val matchId = "mulligan-flow-familiar-first"
            runtimeMatchConfigs.put(RuntimeMatchConfig(matchId = matchId, seat1Deck = deck, seat2Deck = deck))
            val local = EmbeddedChannel(handler(registry))
            val familiar = EmbeddedChannel(handler(registry))

            try {
                familiar.writeInbound(auth("local-player_Familiar", 1))
                greOutbound(familiar)
                familiar.writeInbound(connect(matchId, seatId = 2, requestId = 2))
                val familiarInitial = greOutbound(familiar).map { it.type }

                local.writeInbound(auth("local-player", 3))
                greOutbound(local)
                local.writeInbound(connect(matchId, seatId = 1, requestId = 4))
                val localInitial = greOutbound(local).map { it.type }

                assertSoftly {
                    registry.getConnection(matchId, leyline.bridge.types.SeatId(1)) shouldNotBe null
                    registry.getConnection(matchId, leyline.bridge.types.SeatId(2)) shouldNotBe null
                    familiarInitial shouldContain GREMessageType.GameStateMessage_695e
                    localInitial shouldContain GREMessageType.GameStateMessage_695e
                }
            } finally {
                local.close()
                familiar.close()
            }
        }

        test("disconnect waits for session authority before teardown") {
            val registry = MatchRegistry()
            val matchId = "mulligan-flow-disconnect-authority"
            val (local, familiar) = connectPair(registry, matchId)
            val session = registry.getConnection(matchId, leyline.bridge.types.SeatId(1))?.session as MatchSession
            val authorityEntered = CountDownLatch(1)
            val releaseAuthority = CountDownLatch(1)
            val disconnectStarted = CountDownLatch(1)
            val disconnectThread = AtomicReference<Thread>()
            val entrants = Executors.newFixedThreadPool(2)

            try {
                val holder =
                    entrants.submit {
                        session.withSessionAuthority {
                            authorityEntered.countDown()
                            releaseAuthority.await()
                        }
                    }
                authorityEntered.await(2, TimeUnit.SECONDS) shouldBe true

                val disconnect =
                    entrants.submit {
                        disconnectThread.set(Thread.currentThread())
                        disconnectStarted.countDown()
                        registry.getConnection(matchId, leyline.bridge.types.SeatId(1))!!.disconnected()
                    }
                disconnectStarted.await(2, TimeUnit.SECONDS) shouldBe true
                eventually(2.seconds) {
                    disconnectThread.get()?.state shouldBe Thread.State.BLOCKED
                }
                registry.getMatch(matchId) shouldNotBe null

                releaseAuthority.countDown()
                holder.get(2, TimeUnit.SECONDS)
                disconnect.get(2, TimeUnit.SECONDS)
                registry.getMatch(matchId) shouldBe null
            } finally {
                releaseAuthority.countDown()
                entrants.shutdownNow()
                local.close()
                familiar.close()
            }
        }
    })
