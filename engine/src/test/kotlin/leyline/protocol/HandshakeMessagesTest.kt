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
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
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
                bundle.message.greToClientEvent.greToClientMessagesList
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

        test("initial bundle can suppress starting-player prompt for spectator seats") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 1L)
            val bundle =
                LifecycleMessageMaterializer.initialBundle(
                    seatId = SeatId(2),
                    matchId = "test",
                    msgIdStart = 1,
                    gameStateId = 1,
                    deckMessage = DeckMessage.getDefaultInstance(),
                    bridge = b,
                    includeStartingPlayerPrompt = false,
                    seedProjectionCursor = true,
                )

            val messages = bundle.message.greToClientEvent.greToClientMessagesList
            assertSoftly {
                messages.map { it.type } shouldBe
                    listOf(GREMessageType.DieRollResultsResp_695e, GREMessageType.GameStateMessage_695e)
                messages
                    .single { it.type == GREMessageType.GameStateMessage_695e }
                    .gameStateMessage
                    .pendingMessageCount shouldBe 0
                bundle.transition
                    ?.nextState
                    ?.viewerCursors
                    ?.get(0)
                    ?.previousSnapshot shouldNotBe null
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
                                seedProjectionCursor = false,
                            )
                        }
                    }
                start.countDown()
                bundles.map { it.get(10, TimeUnit.SECONDS) }.size shouldBe 2
                b.projectionStateSnapshot().revision shouldBe beforeRevision + 2
            } finally {
                pool.shutdownNow()
            }
        }

        test("puzzle actions request carries pass-priority prompt") {
            val (message, nextMsgId) =
                LifecycleMessageMaterializer.puzzleActionsReq(7, 5, SeatId(1), ActionMapper.passOnlyActions())
            val gre = message.greToClientEvent.greToClientMessagesList.single()

            assertSoftly {
                nextMsgId shouldBe 8
                gre.type shouldBe GREMessageType.ActionsAvailableReq_695e
                gre.prompt.promptId shouldBe PromptIds.PASS_PRIORITY
            }
        }
    })
