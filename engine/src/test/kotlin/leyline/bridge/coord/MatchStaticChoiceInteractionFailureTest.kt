package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PublishedStaticChoiceInteraction
import leyline.bridge.types.SeatId
import leyline.bridge.types.StaticChoiceIds
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchStaticChoiceInteractionFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:static choice failures
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Island
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        val values = listOf(StaticChoiceIds.colorIdForName("Red")!!, StaticChoiceIds.colorIdForName("Blue")!!)

        fun sourceId(board: Board): Int =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .single()
                .id

        fun request(
            board: Board,
            source: Int? = sourceId(board),
            max: Int = 1,
        ): PromptRequest =
            PromptRequest(
                promptType = "choose_one",
                message = "Choose a color",
                options = listOf("Red", "Blue"),
                min = 1,
                max = max,
                route = PromptRouteResolver.resolve(PromptSemantic.StaticColorChoice),
                sourceEntityId = source,
                staticList = StaticList.Colors,
                staticOptionIds = values,
            )

        fun awaitPublished(coordinator: MatchCutCoordinator): PublishedStaticChoiceInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.staticChoices.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.staticChoices.current()
            }
            return checkNotNull(published)
        }

        test("invalid response shapes leave the exact window and projection unchanged") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val finished = CountDownLatch(1)
            Thread {
                coordinator.staticChoices.awaitSelection(request(board, max = 2), 3_000)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()

            assertSoftly {
                coordinator.staticChoices.submit(
                    "${published.interactionId}-stale",
                    published.gameStateId,
                    listOf(values[0]),
                ) shouldBe
                    false
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId + 1, listOf(values[0])) shouldBe
                    false
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId, emptyList()) shouldBe false
                coordinator.staticChoices.submit(
                    published.interactionId,
                    published.gameStateId,
                    listOf(values[0], values[0]),
                ) shouldBe
                    false
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(Int.MAX_VALUE)) shouldBe
                    false
                coordinator.staticChoices.current() shouldBe published
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(values[1])) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(values[1])) shouldBe false
            }
        }

        test("delivery failure terminalizes before a concurrent response can claim the window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val engineFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.staticChoices.awaitSelection(request(board), null) }
                    .onFailure(engineFailure::set)
                engineFinished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val committed = coordinator.drain(SeatId(1)).single()
            val choiceResultsBefore =
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .snapshotChoiceResults()
            val cutLocated = CountDownLatch(1)
            val releaseDelivery = CountDownLatch(1)
            coordinator.staticChoices.afterDeliveryCutLookup = {
                cutLocated.countDown()
                check(releaseDelivery.await(3, TimeUnit.SECONDS))
            }
            val cause = IllegalStateException("static choice delivery unavailable")
            val deliveryFailure = AtomicReference<Throwable>()
            val deliveryFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.failDelivery(cause) }.onFailure(deliveryFailure::set)
                deliveryFinished.countDown()
            }.start()
            cutLocated.await(3, TimeUnit.SECONDS) shouldBe true

            val responseStarted = CountDownLatch(1)
            val responseFailure = AtomicReference<Throwable>()
            val responseFinished = CountDownLatch(1)
            Thread {
                responseStarted.countDown()
                runCatching {
                    coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(values[0]))
                }.onFailure(responseFailure::set)
                responseFinished.countDown()
            }.start()
            responseStarted.await(3, TimeUnit.SECONDS) shouldBe true
            responseFinished.count shouldBe 1
            releaseDelivery.countDown()

            assertSoftly {
                deliveryFinished.await(3, TimeUnit.SECONDS) shouldBe true
                responseFinished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFinished.await(3, TimeUnit.SECONDS) shouldBe true
                val terminal = deliveryFailure.get().shouldBeInstanceOf<PlaybackTerminalFailure>()
                terminal.cause shouldBe cause
                terminal.pendingPromptCut.shouldNotBeNull().messages shouldBe committed
                responseFailure.get() shouldBe terminal
                engineFailure.get() shouldBe terminal
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .snapshotChoiceResults() shouldBe choiceResultsBefore
                coordinator.staticChoices
                    .current()
                    .shouldBeNull()
            }
            coordinator.staticChoices.afterDeliveryCutLookup = null
        }
    })
