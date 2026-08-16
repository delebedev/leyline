package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.CardSelectInteractionResult
import leyline.bridge.handoff.CardSelectInteractionTimeoutException
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Shared publication transaction proofs for all [SinglePromptRuntimeKernel] users. */
class SinglePromptRuntimeKernelTest :
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

        test("materialization, enqueue, commit, and rollback retain the exact transaction boundary") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            val materialization =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.cardSelect.awaitSelection(request(board, Int.MAX_VALUE), options(board), 3_000)
                }
            assertSoftly {
                materialization.cardSelectDiagnostic.shouldNotBeNull()
                materialization.pendingCardSelectCut.shouldBeNull()
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe prior
            }

            val enqueueBoard = startPuzzleAtMain1(puzzle)
            val enqueueCoordinator = enqueueBoard.bridge.cutCoordinator
            enqueueCoordinator.drain(SeatId(1))
            val existing = listOf(GREToClientMessage.getDefaultInstance())
            enqueueCoordinator.enqueueCommittedBatchForTest(SeatId(1), existing)
            enqueueCoordinator.setBeforeBatchEnqueue(SeatId(1)) { _, _ -> error("feed unavailable") }
            val enqueue =
                shouldThrow<PlaybackTerminalFailure> {
                    enqueueCoordinator.cardSelect.awaitSelection(request(enqueueBoard), options(enqueueBoard), 3_000)
                }
            assertSoftly {
                enqueue.pendingCardSelectCut.shouldNotBeNull()
                enqueueCoordinator.drain(SeatId(1)) shouldContainExactly listOf(existing)
            }
        }

        test("stale install and post-install failures preserve owned output versus committed output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val competing =
                board.bridge
                    .projectionStateSnapshot()
                    .editor()
                    .freeze()
            coordinator.cardSelect.beforeInstall = { board.bridge.replaceProjectionStateForTest(competing) }
            val stale =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.cardSelect.awaitSelection(request(board), options(board), 3_000)
                }
            assertSoftly {
                stale.pendingCardSelectCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe competing
            }

            val next = startPuzzleAtMain1(puzzle)
            val nextCoordinator = next.bridge.cutCoordinator
            nextCoordinator.drain(SeatId(1))
            val prior = next.bridge.projectionStateSnapshot()
            nextCoordinator.cardSelect.afterInstall = { error("ack unavailable") }
            val committed =
                shouldThrow<PlaybackTerminalFailure> {
                    nextCoordinator.cardSelect.awaitSelection(request(next), options(next), 3_000)
                }
            val retained = nextCoordinator.drain(SeatId(1)).single()
            assertSoftly {
                committed.pendingCardSelectCut.shouldNotBeNull().messages shouldBe retained
                next.bridge.projectionStateSnapshot().revision shouldBe prior.revision + 1
                nextCoordinator.cardSelect.current().shouldBeNull()
            }
        }

        test("response wins timeout claim and stale or duplicate completion cannot re-enter") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val releaseTimeout = CountDownLatch(1)
            coordinator.cardSelect.beforeTimeoutClaim = {
                timeoutEntered.countDown()
                check(releaseTimeout.await(3, TimeUnit.SECONDS))
            }
            val (result, finished, cards) = startAwait(coordinator, board, 25)
            val published = awaitPublished(coordinator)
            val selected = ids(coordinator)[1]
            assertSoftly {
                timeoutEntered.await(3, TimeUnit.SECONDS) shouldBe true
                coordinator.cardSelect.submitSelectN(published.interactionId, published.gameStateId, listOf(selected)) shouldBe true
                coordinator.cardSelect.submitSelectN(published.interactionId, published.gameStateId, listOf(selected)) shouldBe false
            }
            releaseTimeout.countDown()
            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().handles.single() shouldBe cards[1]
                coordinator.failure().shouldBeNull()
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
                coordinator.cardSelect.submitSelectN(
                    timeoutPublished.interactionId,
                    timeoutPublished.gameStateId,
                    listOf(timeoutId),
                ) shouldBe
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
                delivery.pendingCardSelectCut.shouldNotBeNull().messages shouldBe attempted
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
                    teardownCoordinator.cardSelect.submitSelectN(
                        teardownPublished.interactionId,
                        teardownPublished.gameStateId,
                        listOf(1),
                    )
                } shouldBe teardownCoordinator.failure()
            }
        }

        test("request commits before the publication signal and leaves a priority horizon") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            board.bridge
                .actionBridge(SeatId(1))
                .getPending()
                .shouldNotBeNull()
                .published = false
            val (_, finished, _) = startAwait(coordinator, board, 3_000)
            awaitPublished(coordinator)
            assertSoftly {
                board.bridge.prioritySignal.awaitSignal(3_000) shouldBe true
                board.bridge.awaitPriorityWithTimeout(25) shouldBe true
                coordinator.cardSelect.submitSelectN(
                    checkNotNull(coordinator.cardSelect.current()).interactionId,
                    checkNotNull(coordinator.cardSelect.current()).gameStateId,
                    ids(coordinator).take(1),
                ) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
            }
        }
    })
