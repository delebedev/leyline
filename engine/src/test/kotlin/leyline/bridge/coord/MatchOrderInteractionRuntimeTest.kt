package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.OrderInteractionResult
import leyline.bridge.handoff.OrderMoveIntent
import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.OrderingContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchOrderInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:order runtime
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

        fun cards(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Hand)
                .cards
                .toList()

        fun request(
            board: Board,
            kind: OrderRouteKind,
        ): PromptRequest {
            val options = cards(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            return PromptRequest(
                promptType = "order",
                message = "Order cards",
                options = options.map { it.name },
                min = options.size,
                max = options.size,
                defaultIndex = 0,
                candidateRefs =
                    options.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Hand.name)
                    },
                route =
                    ResolvedPromptRoute.Order(
                        if (kind ==
                            OrderRouteKind.Top
                        ) {
                            PromptSemantic.OrderForTop
                        } else {
                            PromptSemantic.OrderForBottom
                        },
                        kind,
                    ),
                sourceEntityId = source.id,
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedOrderInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.order.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.order.current()
            }
            return checkNotNull(published)
        }

        test("top order publishes move state and returns the exact requested permutation") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            val result = AtomicReference<OrderInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    coordinator.orderRuntime(SeatId(1)).awaitOrder(
                        request(board, OrderRouteKind.Top),
                        options,
                        OrderMoveIntent(SeatId(1), options.map { ForgeCardId(it.id) }, putOnTop = true),
                        3_000,
                    ),
                )
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val batch = coordinator.drain(SeatId(1)).single()
            val order = batch.single { it.hasOrderReq() }
            val annotations = batch.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.annotationsList }
            val orderedIds = order.orderReq.idsList.reversed()

            assertSoftly {
                batch.last().hasOrderReq() shouldBe true
                order.gameStateId shouldBe published.gameStateId
                order.prompt.promptId shouldBe PromptIds.ORDER_LIBRARY_TOP
                order.prompt.parametersList
                    .single()
                    .numberValue shouldBeGreaterThan 0
                order.allowCancel shouldBe AllowCancel.No_a526
                order.allowUndo shouldBe true
                annotations.map { it.typeList.first() }.filter {
                    it == AnnotationType.ObjectIdChanged || it == AnnotationType.ZoneTransfer_af5a
                } shouldContainExactly
                    listOf(
                        AnnotationType.ObjectIdChanged,
                        AnnotationType.ZoneTransfer_af5a,
                        AnnotationType.ObjectIdChanged,
                        AnnotationType.ZoneTransfer_af5a,
                    )
                coordinator.order.submit(published.interactionId, published.gameStateId + 1, orderedIds) shouldBe false
                coordinator.order.submit(published.interactionId, published.gameStateId, orderedIds) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                coordinator.order.submit(published.interactionId, published.gameStateId, orderedIds) shouldBe false
                result.get().optionIndices shouldContainExactly listOf(1, 0)
                (result.get().handles[0] === options[1]) shouldBe true
                (result.get().handles[1] === options[0]) shouldBe true
                coordinator.order.current().shouldBeNull()
            }
        }

        test("bottom order preserves its envelope and rejects invalid permutations") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            val finished = CountDownLatch(1)
            Thread {
                coordinator.orderRuntime(SeatId(1)).awaitOrder(request(board, OrderRouteKind.Bottom), options, null, 3_000)
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val order = coordinator.drain(SeatId(1)).flatten().single { it.hasOrderReq() }
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()
            assertSoftly {
                order.prompt.promptId shouldBe PromptIds.ORDER_LIBRARY_BOTTOM
                order.orderReq.orderingContext shouldBe OrderingContext.OrderingForBottom
                order.allowUndo shouldBe false
                coordinator.order.submit(published.interactionId, published.gameStateId, listOf(order.orderReq.idsList.first())) shouldBe
                    false
                coordinator.order.submit(published.interactionId, published.gameStateId, listOf(1, 1)) shouldBe false
                coordinator.order.submit(
                    published.interactionId,
                    published.gameStateId,
                    listOf(order.orderReq.idsList.first(), Int.MAX_VALUE),
                ) shouldBe false
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                coordinator.order.submit(published.interactionId, published.gameStateId, order.orderReq.idsList) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
            }
        }

        test("published Order remains an awaitPriority horizon after its signal is consumed") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            board.bridge
                .actionBridge(SeatId(1))
                .getPending()
                .shouldNotBeNull()
                .published = false
            val finished = CountDownLatch(1)
            Thread {
                coordinator.orderRuntime(SeatId(1)).awaitOrder(
                    request(board, OrderRouteKind.Top),
                    cards(board),
                    null,
                    3_000,
                )
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            board.bridge.prioritySignal.awaitSignal(3_000) shouldBe true
            assertSoftly {
                board.bridge
                    .actionBridge(SeatId(1))
                    .getPending()
                    .shouldBeNull()
                board.bridge
                    .promptBridge(SeatId(1))
                    .getPendingPrompt()
                    .shouldBeNull()
                coordinator.currentBlockingInteraction().shouldBeNull()
                coordinator.targeting.current().shouldBeNull()
                coordinator.search.current().shouldBeNull()
                coordinator.manaSourcePayments.current().shouldBeNull()
                coordinator.oneShotPayCosts.current().shouldBeNull()
                board.bridge.awaitPriorityWithTimeout(25) shouldBe true
            }
            val ids =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasOrderReq() }
                    .orderReq.idsList
            coordinator.order.submit(published.interactionId, published.gameStateId, ids) shouldBe true
            finished.await(3, TimeUnit.SECONDS) shouldBe true
        }

        test("timeout returns the legacy default-first order and retires the window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            var timedOut = false
            val prompt =
                InteractivePromptBridge(timeoutMs = 25, strict = false).also {
                    it.orderRuntime = coordinator.orderRuntime(SeatId(1))
                    it.timeoutListener = { timedOut = true }
                }
            val result = prompt.requestOrder(request(board, OrderRouteKind.Top), options)
            val publishedBatch = coordinator.drain(SeatId(1)).single()

            assertSoftly {
                result.optionIndices shouldContainExactly listOf(0, 1)
                (result.handles[0] === options[0]) shouldBe true
                publishedBatch.last().hasOrderReq() shouldBe true
                timedOut shouldBe true
                coordinator.order.current().shouldBeNull()
            }
        }
    })
