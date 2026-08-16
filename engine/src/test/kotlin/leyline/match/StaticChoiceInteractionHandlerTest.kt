package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.SeatId
import leyline.bridge.types.StaticChoiceIds
import leyline.testkit.BoardTest
import leyline.testkit.selectNResp
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StaticChoiceInteractionHandlerTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:static choice session adapter
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

        test("SelectNResp completes the exact value window without a legacy pending prompt") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val values = listOf(StaticChoiceIds.parityIdForName("Odds")!!, StaticChoiceIds.parityIdForName("Evens")!!)
            val request =
                PromptRequest(
                    promptType = "confirm",
                    message = "Odd or even",
                    options = listOf("Odds", "Evens"),
                    min = 1,
                    max = 1,
                    route = PromptRouteResolver.resolve(PromptSemantic.StaticParityChoice),
                    staticList = StaticList.Parities,
                    staticOptionIds = values,
                )
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.staticChoices.awaitSelection(request, 3_000))
                finished.countDown()
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.staticChoices.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.staticChoices.current()
            }
            val exact = checkNotNull(published)
            coordinator.drain(SeatId(1))
            var autoPassed = false

            StaticChoiceInteractionHandler(SessionContext(checkNotNull(board.bridge.getGame()), board.bridge))
                .tryHandleSelectN(
                    selectNResp(listOf(0)).toBuilder().setGameStateId(exact.gameStateId).build(),
                ) { autoPassed = true }
                .shouldBeTrue()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldBe listOf(1)
                autoPassed shouldBe true
                coordinator.staticChoices
                    .current()
                    .shouldBeNull()
            }
        }
    })
