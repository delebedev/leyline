package leyline.protocol

import forge.util.MyRandom
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.bundle.LifecycleMessageMaterializer
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.DeckMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Integration tests for [HandshakeMessages] — die roll determinism and range. */
class HandshakeMessagesTest :
    FunSpec({

        tags(IntegrationTag)

        var bridge: GameBridge? = null

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        fun extractDieRolls(
            b: GameBridge,
            winner: Int = 2,
        ): Map<Int, Int> {
            val bundle =
                LifecycleMessageMaterializer.initialBundle(
                    seatId = SeatId(2),
                    matchId = "test",
                    msgIdStart = 1,
                    gameStateId = 1,
                    deckMessage = DeckMessage.getDefaultInstance(),
                    bridge = b,
                    dieRollWinner = winner,
                )
            val dieRoll =
                bundle.messages
                    .first { it.type == GREMessageType.DieRollResultsResp_695e }
                    .dieRollResultsResp
            return dieRoll.playerDieRollsList.associate { it.systemSeatId to it.rollValue }
        }

        test("die roll winner rolls higher") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 1L)
            repeat(10) { i ->
                MyRandom.setRandom(Random(i.toLong()))
                val rolls = extractDieRolls(b, winner = 2)
                assertSoftly {
                    rolls.getValue(1) shouldBeInRange 1..20
                    rolls.getValue(2) shouldBeInRange 1..20
                    rolls.getValue(2) shouldBeGreaterThan rolls.getValue(1)
                }
            }
        }

        test("die roll deterministic with seed") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 1L)

            MyRandom.setRandom(Random(42))
            val first = extractDieRolls(b)

            MyRandom.setRandom(Random(42))
            val second = extractDieRolls(b)

            first shouldBe second
        }

        test("initial bundle omits response requests for Observer seats") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 1L)
            val planner = LogicalSequencePlanner(b.projectionStateSnapshot().sequence)
            val bundle =
                LifecycleMessageMaterializer.initialBundles(
                    viewers =
                        listOf(
                            ProjectionViewer(SeatId(1), ProjectionViewerRole.Observer),
                            ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                        ),
                    matchId = "test",
                    gameStateId = 1,
                    planner = planner,
                    bridge = b,
                    includeStartingPlayerPrompt = true,
                )

            val messages = bundle.viewers.single { it.first == SeatId(2) }.second
            assertSoftly {
                messages.map { it.type } shouldBe
                    listOf(GREMessageType.DieRollResultsResp_695e, GREMessageType.GameStateMessage_695e)
                messages
                    .single { it.type == GREMessageType.GameStateMessage_695e }
                    .gameStateMessage
                    .pendingMessageCount shouldBe 0
                bundle.transition.nextState.viewerCursors[SeatId(1)]
                    ?.previousSnapshot shouldNotBe null
                bundle.transition.nextState.viewerCursors[SeatId(2)]
                    ?.previousSnapshot shouldNotBe null
            }
        }

        test("player and Familiar bundles share the starting-player decision state") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 1L)
            val bundle =
                LifecycleMessageMaterializer.initialBundles(
                    viewers =
                        listOf(
                            ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                            ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                        ),
                    matchId = "test",
                    gameStateId = 1,
                    planner = LogicalSequencePlanner(b.projectionStateSnapshot().sequence),
                    bridge = b,
                )

            val seat1State =
                bundle.viewers
                    .single { it.first == SeatId(1) }
                    .second
                    .single { it.type == GREMessageType.GameStateMessage_695e }
                    .gameStateMessage
            val seat2State =
                bundle.viewers
                    .single { it.first == SeatId(2) }
                    .second
                    .single { it.type == GREMessageType.GameStateMessage_695e }
                    .gameStateMessage

            listOf(seat1State, seat2State).forEach { state ->
                assertSoftly {
                    state.turnInfo.decisionPlayer shouldBe 2
                    state.playersList
                        .single { it.systemSeatNumber == 2 }
                        .pendingMessageType shouldBe ClientMessageType.ChooseStartingPlayerResp_097b
                }
            }
            assertSoftly {
                seat1State.pendingMessageCount shouldBe 0
                seat2State.pendingMessageCount shouldBe 0
            }
        }

        test("concurrent seat initial bundles serialize projection commits") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 1L)
            val beforeRevision = b.projectionStateSnapshot().revision
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val bundles =
                    listOf(SeatId(1), SeatId(2)).map { seat ->
                        pool.submit<Int> {
                            start.await(5, TimeUnit.SECONDS)
                            b.cutCoordinator.lifecycle.publishInitial(
                                seat,
                                includeStartingPlayerPrompt = true,
                            )
                        }
                    }
                start.countDown()
                assertSoftly {
                    bundles.map { it.get(10, TimeUnit.SECONDS) }.distinct().size shouldBe 1
                    b.projectionStateSnapshot().revision shouldBe beforeRevision + 1
                    b.cutCoordinator.drain(SeatId(1)).size shouldBe 1
                    b.cutCoordinator.drain(SeatId(2)).size shouldBe 1
                }
            } finally {
                pool.shutdownNow()
            }
        }

        test("puzzle actions request carries pass-priority prompt") {
            val (messages, nextMsgId) =
                LifecycleMessageMaterializer.puzzleActionsReq(7, 5, SeatId(1), ActionMapper.passOnlyActions())
            val gre = messages.single()

            assertSoftly {
                nextMsgId shouldBe 8
                gre.type shouldBe GREMessageType.ActionsAvailableReq_695e
                gre.prompt.promptId shouldBe PromptIds.PASS_PRIORITY
            }
        }
    })
