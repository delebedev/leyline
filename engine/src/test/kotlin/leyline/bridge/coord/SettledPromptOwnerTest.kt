package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.CardSelectInteractionResult
import leyline.bridge.handoff.CardSelectInteractionTimeoutException
import leyline.bridge.handoff.CardSelectWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.Board
import leyline.testkit.BoardTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared correlation, timeout arbitration, and retirement proofs for all
 * [SettledPromptOwner] slots. The publication transaction itself belongs
 * to [CoordinatorCutInstallerTest].
 */
class SettledPromptOwnerTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:single prompt kernel
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Mountain;Forest
            humanbattlefield=Island
            ailibrary=Forest
            """.trimIndent()

        fun options(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Hand)
                .cards
                .toList()

        fun request(
            board: Board,
            sourceId: Int? =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
                    .id,
        ): PromptRequest {
            val cards = options(board)
            return PromptRequest(
                promptType = "choose_cards",
                message = "Choose a permanent",
                options = cards.map { it.name },
                min = 1,
                max = 1,
                defaultIndex = 0,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Hand.name)
                    },
                route = PromptRouteResolver.resolve(PromptSemantic.SelectNSacrificeEffect),
                sourceEntityId = sourceId,
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedCardSelectInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.cardSelect.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.cardSelect.current()
            }
            return checkNotNull(published)
        }

        fun ids(coordinator: MatchCutCoordinator): List<Int> =
            coordinator
                .drain(SeatId(1))
                .flatten()
                .single { it.hasSelectNReq() }
                .selectNReq
                .idsList

        fun startAwait(
            coordinator: MatchCutCoordinator,
            board: Board,
            timeoutMs: Long?,
        ): Triple<AtomicReference<CardSelectInteractionResult>, CountDownLatch, List<Card>> {
            val result = AtomicReference<CardSelectInteractionResult>()
            val finished = CountDownLatch(1)
            val cards = options(board)
            Thread {
                try {
                    result.set(coordinator.cardSelect.awaitSelection(request(board), cards, timeoutMs))
                } finally {
                    finished.countDown()
                }
            }.start()
            return Triple(result, finished, cards)
        }

        test("response wins timeout claim and stale or duplicate completion cannot re-enter") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val releaseTimeout = CountDownLatch(1)
            coordinator.prompts.settled.beforeTimeoutClaim = {
                timeoutEntered.countDown()
                check(releaseTimeout.await(3, TimeUnit.SECONDS))
            }
            val (result, finished, cards) = startAwait(coordinator, board, 25)
            val published = awaitPublished(coordinator)
            val selected = ids(coordinator)[1]
            assertSoftly {
                timeoutEntered.await(3, TimeUnit.SECONDS) shouldBe true
                coordinator.prompts.hasPendingInteraction() shouldBe true
                coordinator.acceptSettled(leyline.testkit.selectNResp(listOf(selected)), published.gameStateId) shouldBe true
                coordinator.acceptSettled(leyline.testkit.selectNResp(listOf(selected)), published.gameStateId) shouldBe false
            }
            releaseTimeout.countDown()
            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().handles.single() shouldBe cards[1]
                coordinator.prompts.hasPendingInteraction() shouldBe false
                coordinator.failure().shouldBeNull()
            }
        }

        test("exact correlation owns rejection acceptance and retirement") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val (result, finished, cards) = startAwait(coordinator, board, 3_000)
            val published = awaitPublished(coordinator)
            val selected = ids(coordinator)[1]
            val requestMsgId = board.bridge.committedSequence().lastPromptMsgId
            val projection = board.bridge.projectionStateSnapshot()
            val acceptedBefore = board.bridge.responseAcceptance.responsesAccepted()

            assertSoftly {
                coordinator.admitSettled(
                    leyline.testkit.selectNResp(listOf(selected)),
                    published.gameStateId,
                    requestMsgId + 1,
                ) shouldBe SettledPromptAdmission.NotOwned
                coordinator.admitSettled(
                    leyline.testkit.selectNResp(listOf(selected)),
                    published.gameStateId + 1,
                    requestMsgId,
                ) shouldBe SettledPromptAdmission.Rejected
                coordinator.admitSettled(
                    leyline.testkit.orderResp(listOf(selected)),
                    published.gameStateId,
                    requestMsgId,
                ) shouldBe SettledPromptAdmission.Rejected
                coordinator.admitSettled(
                    leyline.testkit.selectNResp(emptyList()),
                    published.gameStateId,
                    requestMsgId,
                ) shouldBe SettledPromptAdmission.Rejected
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore
                coordinator
                    .admitSettled(
                        leyline.testkit.selectNResp(listOf(selected)),
                        published.gameStateId,
                        requestMsgId,
                    ).shouldBeInstanceOf<SettledPromptAdmission.Accepted>()
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().handles.single() shouldBe cards[1]
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore + 1
                coordinator.admitSettled(
                    leyline.testkit.selectNResp(listOf(selected)),
                    published.gameStateId,
                    requestMsgId,
                ) shouldBe SettledPromptAdmission.Rejected
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore + 1
            }
        }

        test("timeout, delivery failure, and teardown retire the exact window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val timeoutFailure = AtomicReference<Throwable>()
            val timeoutFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.cardSelect.awaitSelection(request(board), options(board), 25) }
                    .onFailure(timeoutFailure::set)
                    .also { timeoutFinished.countDown() }
            }.start()
            val timeoutPublished = awaitPublished(coordinator)
            val timeoutId = ids(coordinator).first()
            timeoutFinished.await(3, TimeUnit.SECONDS) shouldBe true
            assertSoftly {
                timeoutFailure.get().shouldBeInstanceOf<CardSelectInteractionTimeoutException>()
                coordinator.acceptSettled(leyline.testkit.selectNResp(listOf(timeoutId)), timeoutPublished.gameStateId) shouldBe
                    false
            }

            val deliveryBoard = startPuzzleAtMain1(puzzle)
            val deliveryCoordinator = deliveryBoard.bridge.cutCoordinator
            deliveryCoordinator.drain(SeatId(1))
            val (_, deliveryFinished, _) = startAwait(deliveryCoordinator, deliveryBoard, null)
            awaitPublished(deliveryCoordinator)
            val attempted = deliveryCoordinator.drain(SeatId(1)).single()
            val delivery =
                shouldThrow<PlaybackTerminalFailure> {
                    deliveryCoordinator.failDelivery(IllegalStateException("delivery unavailable"))
                }
            assertSoftly {
                delivery.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<CardSelectWindowValue>()
                delivery.pendingPromptCut.shouldNotBeNull().messages shouldBe attempted
                deliveryFinished.await(3, TimeUnit.SECONDS) shouldBe true
            }

            val teardownBoard = startPuzzleAtMain1(puzzle)
            val teardownCoordinator = teardownBoard.bridge.cutCoordinator
            teardownCoordinator.drain(SeatId(1))
            val (_, teardownFinished, _) = startAwait(teardownCoordinator, teardownBoard, null)
            val teardownPublished = awaitPublished(teardownCoordinator)
            teardownCoordinator.shutdown(IllegalStateException("match closed"))
            assertSoftly {
                teardownFinished.await(3, TimeUnit.SECONDS) shouldBe true
                teardownCoordinator.cardSelect.current().shouldBeNull()
                shouldThrow<PlaybackTerminalFailure> {
                    teardownCoordinator.acceptSettled(leyline.testkit.selectNResp(listOf(1)), teardownPublished.gameStateId)
                } shouldBe teardownCoordinator.failure()
            }
        }

        test("request commits before the publication signal and leaves a priority horizon") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            coordinator.hidePublishedActionWindow(
                board.bridge
                    .actionBridge(SeatId(1))
                    .getPending()
                    .shouldNotBeNull()
                    .actionId,
            )
            val (_, finished, _) = startAwait(coordinator, board, 3_000)
            awaitPublished(coordinator)
            assertSoftly {
                board.bridge.prioritySignal.awaitSignal(3_000) shouldBe true
                board.bridge.awaitPriorityWithTimeout(25) shouldBe true
                coordinator.acceptSettled(
                    leyline.testkit.selectNResp(ids(coordinator).take(1)),
                    checkNotNull(coordinator.cardSelect.current()).gameStateId,
                ) shouldBe
                    true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
            }
        }
    })
