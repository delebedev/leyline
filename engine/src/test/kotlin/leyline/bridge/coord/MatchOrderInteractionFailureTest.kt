package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.OrderInteractionResult
import leyline.bridge.handoff.OrderInteractionTimeoutException
import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.bundle.BundleBuilder
import leyline.game.state.GameBridge
import leyline.match.GreMessageSink
import leyline.match.drainOneCoordinatorBarrier
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ResultReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchOrderInteractionFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:order failures
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
                promptType = "order",
                message = "Order cards",
                options = cards.map { it.name },
                min = cards.size,
                max = cards.size,
                candidateRefs = cards.mapIndexed { index, card -> PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, "Hand") },
                route = ResolvedPromptRoute.Order(PromptSemantic.OrderForTop, OrderRouteKind.Top),
                sourceEntityId = sourceId,
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedOrderInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var current = coordinator.order.current()
            while (current == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                current = coordinator.order.current()
            }
            return checkNotNull(current)
        }

        test("materialization failure retains frozen input without projection or output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.order.awaitOrder(request(board, Int.MAX_VALUE), options(board), null, 3_000)
                }
            assertSoftly {
                terminal.orderDiagnostic
                    .shouldNotBeNull()
                    .interaction.sourceForgeCardId
                    ?.value shouldBe Int.MAX_VALUE
                terminal.pendingOrderCut.shouldBeNull()
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                board.bridge.projectionStateSnapshot() shouldBe prior
                coordinator.order
                    .current()
                    .shouldBeNull()
            }
        }

        test("enqueue failure retains the exact cut without installing projection") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            val existing = listOf(GREToClientMessage.getDefaultInstance())
            coordinator.enqueueCommittedBatchForTest(SeatId(1), existing)
            coordinator.setBeforeBatchEnqueue(SeatId(1)) { _, _ -> error("order feed unavailable") }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.order.awaitOrder(request(board), options(board), null, 3_000)
                }
            assertSoftly {
                terminal.cause?.message shouldBe "order feed unavailable"
                terminal.pendingOrderCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)) shouldContainExactly listOf(existing)
                board.bridge.projectionStateSnapshot() shouldBe prior
            }
            coordinator.setBeforeBatchEnqueue(SeatId(1), null)
        }

        test("stale install removes owned output and retains the competing projection") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val competing =
                board.bridge
                    .projectionStateSnapshot()
                    .editor()
                    .freeze()
            coordinator.order.beforeInstall = { board.bridge.replaceProjectionStateForTest(competing) }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.order.awaitOrder(request(board), options(board), null, 3_000)
                }
            assertSoftly {
                terminal.pendingOrderCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                board.bridge.projectionStateSnapshot() shouldBe competing
            }
            coordinator.order.beforeInstall = null
        }

        test("post-install acknowledgement failure retains committed state and output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.order.afterInstall = { error("order acknowledgement unavailable") }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.order.awaitOrder(request(board), options(board), null, 3_000)
                }
            val retained = coordinator.drain(SeatId(1)).single()
            assertSoftly {
                terminal.pendingOrderCut.shouldNotBeNull().messages shouldBe retained
                retained.any { it.hasOrderReq() } shouldBe true
                board.bridge.projectionStateSnapshot().revision shouldBe prior.revision + 1
                coordinator.order
                    .current()
                    .shouldBeNull()
            }
            coordinator.order.afterInstall = null
        }

        test("response wins an overlapping timeout claim") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val releaseTimeout = CountDownLatch(1)
            coordinator.order.beforeTimeoutClaim = {
                timeoutEntered.countDown()
                check(releaseTimeout.await(3, TimeUnit.SECONDS))
            }
            val result = AtomicReference<OrderInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.order.awaitOrder(request(board), options(board), null, 25))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val ids =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasOrderReq() }
                    .orderReq.idsList
                    .reversed()
            timeoutEntered.await(3, TimeUnit.SECONDS) shouldBe true
            coordinator.order.submit(published.interactionId, published.gameStateId, ids) shouldBe true
            releaseTimeout.countDown()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().optionIndices shouldContainExactly listOf(1, 0)
                coordinator.failure().shouldBeNull()
            }
            coordinator.order.beforeTimeoutClaim = null
        }

        test("timeout winner retires the window and rejects a late permutation") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.order.awaitOrder(request(board), options(board), null, 25) }
                    .onFailure(failure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val orderedIds =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasOrderReq() }
                    .orderReq.idsList
            finished.await(3, TimeUnit.SECONDS) shouldBe true
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()

            assertSoftly {
                failure.get().shouldBeInstanceOf<OrderInteractionTimeoutException>()
                coordinator.order.submit(published.interactionId, published.gameStateId, orderedIds) shouldBe false
                coordinator.order
                    .current()
                    .shouldBeNull()
                coordinator.failure().shouldBeNull()
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                coordinator.drain(SeatId(1)) shouldBe emptyList()
            }
        }

        test("delivery failure wakes the engine and retains the attempted exact cut") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.order.awaitOrder(request(board), options(board), null, null) }
                    .onFailure(engineFailure::set)
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            val cause = IllegalStateException("order delivery unavailable")
            val attempted = AtomicReference<List<GREToClientMessage>>()
            val sink =
                object : GreMessageSink {
                    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
                        attempted.set(messages)
                        throw cause
                    }

                    override fun sendRealGameState(
                        bridge: GameBridge,
                        revealForSeat: Int?,
                    ) = Unit

                    override fun sendBundle(result: BundleBuilder.BundleResult) = Unit

                    override fun sendGameOver(reason: ResultReason) = Unit

                    override fun makeGRE(
                        type: GREMessageType,
                        gsId: Int,
                        msgId: Int,
                        configure: (GREToClientMessage.Builder) -> Unit,
                    ): GREToClientMessage = GREToClientMessage.getDefaultInstance()
                }
            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    drainOneCoordinatorBarrier(
                        sink,
                        synchronizationActionId = null,
                        drainCommitted = { coordinator.drain(SeatId(1)) },
                        completeSynchronization = { false },
                        awaitNext = {},
                        failDelivery = coordinator::failDelivery,
                    )
                }

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFailure.get() shouldBe terminal
                terminal.cause shouldBe cause
                terminal.pendingOrderCut.shouldNotBeNull().messages shouldBe attempted.get()
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                coordinator.order
                    .current()
                    .shouldBeNull()
            }
        }

        test("delivery failure terminalizes before a concurrent response can claim the window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val engineFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.order.awaitOrder(request(board), options(board), null, null) }
                    .onFailure(engineFailure::set)
                engineFinished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val committed = coordinator.drain(SeatId(1)).single()
            val orderedIds = committed.single { it.hasOrderReq() }.orderReq.idsList
            val cutLocated = CountDownLatch(1)
            val releaseDelivery = CountDownLatch(1)
            coordinator.order.afterDeliveryCutLookup = {
                cutLocated.countDown()
                check(releaseDelivery.await(3, TimeUnit.SECONDS))
            }
            val cause = IllegalStateException("order delivery unavailable")
            val deliveryFailure = AtomicReference<Throwable>()
            val deliveryFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.failDelivery(cause) }.onFailure(deliveryFailure::set)
                deliveryFinished.countDown()
            }.start()
            cutLocated.await(3, TimeUnit.SECONDS) shouldBe true

            val responseFailure = AtomicReference<Throwable>()
            val responseFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.order.submit(published.interactionId, published.gameStateId, orderedIds) }
                    .onFailure(responseFailure::set)
                responseFinished.countDown()
            }.start()
            responseFinished.count shouldBe 1
            releaseDelivery.countDown()

            assertSoftly {
                deliveryFinished.await(3, TimeUnit.SECONDS) shouldBe true
                responseFinished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFinished.await(3, TimeUnit.SECONDS) shouldBe true
                val terminal = deliveryFailure.get().shouldBeInstanceOf<PlaybackTerminalFailure>()
                terminal.cause shouldBe cause
                terminal.pendingOrderCut.shouldNotBeNull().messages shouldBe committed
                responseFailure.get() shouldBe terminal
                engineFailure.get() shouldBe terminal
                coordinator.order
                    .current()
                    .shouldBeNull()
            }
            coordinator.order.afterDeliveryCutLookup = null
        }

        test("shutdown wakes an unbounded waiter and clears exact handles") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.order.awaitOrder(request(board), options(board), null, null) }
                    .onFailure(failure::set)
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            val cause = IllegalStateException("match closed")
            coordinator.shutdown(cause)

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                failure.get().shouldBeInstanceOf<PlaybackTerminalFailure>().cause shouldBe cause
                coordinator.order
                    .current()
                    .shouldBeNull()
            }
        }
    })
