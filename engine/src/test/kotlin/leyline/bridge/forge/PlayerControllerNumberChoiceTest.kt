package leyline.bridge.forge

import forge.game.ability.AbilityUtils
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.acceptSettled
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PublishedStaticChoiceInteraction
import leyline.bridge.types.SeatId
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.cancelActionReq
import leyline.testkit.selectNResp
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.IdType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PlayerControllerNumberChoiceTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:Finite number choices
            Goal:Win
            Turns:4
            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=The Toymaker's Trap
            humanlibrary=Forest;Island;Mountain
            ailibrary=Forest;Island;Mountain
            """.trimIndent()

        for (values in listOf(listOf(1, 2, 3, 4, 5), listOf(5, 1, 3))) {
            test("number callback retains offered order $values and returns the selected integer") {
                val board = startPuzzleAtMain1(puzzle)
                val source = board.human.battlefield.card("The Toymaker's Trap")
                val controller = board.human.controller as PlayerController
                val result =
                    numberCallback(board) {
                        controller.chooseNumber(source.triggers.first().overridingAbility, "Choose a number", values.toMutableList(), null)
                    }
                val published = awaitNumberChoice(board, result)
                val message =
                    board.bridge.cutCoordinator
                        .drain(SeatId(1))
                        .flatten()
                        .single { it.hasSelectNReq() }
                assertSoftly {
                    message.selectNReq.idType shouldBe IdType.PromptParameterIndex
                    message.selectNReq.idsList shouldBe values.indices.map { it + 1 }
                    message.selectNReq.prompt.parametersList
                        .map { it.stringValue } shouldBe values.map(Int::toString)
                    message.selectNReq.minSel shouldBe 1
                    message.selectNReq.maxSel shouldBe 1
                    message.allowCancel shouldBe AllowCancel.No_a526
                    board.bridge.cutCoordinator.acceptSettled(cancelActionReq(), published.gameStateId) shouldBe false
                    board.bridge.cutCoordinator.acceptSettled(selectNResp(emptyList()), published.gameStateId) shouldBe false
                    board.bridge.cutCoordinator.acceptSettled(selectNResp(listOf(99)), published.gameStateId) shouldBe false
                    board.bridge.cutCoordinator.acceptSettled(selectNResp(listOf(values.size)), published.gameStateId) shouldBe true
                    result.get(3, TimeUnit.SECONDS) shouldBe values.last()
                    board.bridge.cutCoordinator.staticChoices
                        .current() shouldBe null
                }
            }
        }

        test("singleton returns its offered integer and an empty domain fails without publication") {
            val board = startPuzzleAtMain1(puzzle)
            val source = board.human.battlefield.card("The Toymaker's Trap")
            val controller = board.human.controller as PlayerController
            val ability = source.triggers.first().overridingAbility
            assertSoftly {
                controller.chooseNumber(ability, "Choose", mutableListOf(7), null) shouldBe 7
                shouldThrow<IllegalArgumentException> {
                    controller.chooseNumber(ability, "Choose", mutableListOf(), null)
                }
                board.bridge.cutCoordinator.staticChoices
                    .current() shouldBe null
            }
        }

        test("number choice timeout returns the first offered value and retires the mandatory window") {
            val board = startPuzzleAtMain1(puzzle)
            val source = board.human.battlefield.card("The Toymaker's Trap")
            val prompt =
                InteractivePromptBridge(timeoutMs = 25, strict = false).also {
                    it.runtimeBindings =
                        board.bridge.cutCoordinator.prompts
                            .bindings(SeatId(1))
                    it.setDiagnosticContext(board.game, Thread.currentThread())
                }
            val controller =
                PlayerController(
                    board.game,
                    board.human,
                    board.human.lobbyPlayer,
                    prompt,
                    board.bridge.seating,
                    interactionRuntime = board.bridge.cutCoordinator,
                )
            assertSoftly {
                controller.chooseNumber(source.triggers.first().overridingAbility, "Choose", mutableListOf(5, 1, 3), null) shouldBe 5
                board.bridge.cutCoordinator.staticChoices
                    .current() shouldBe null
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .allowCancel shouldBe AllowCancel.No_a526
            }
        }

        test("Toymaker repeat resolution excludes remembered numbers and applies the selected guesses") {
            val board = startPuzzleAtMain1(puzzle)
            val source = board.human.battlefield.card("The Toymaker's Trap")
            val opponent = board.ai
            opponent.addController(10, board.human)
            val result =
                numberCallback(board) {
                    repeat(2) {
                        val ability = source.triggers.first().overridingAbility
                        ability.activatingPlayer = board.human
                        AbilityUtils.resolve(ability)
                    }
                }
            val choices =
                listOf(
                    listOf(1, 2, 3, 4, 5) to 4,
                    listOf(1, 2, 3, 4, 5) to 2,
                    listOf(1, 2, 3, 5) to 5,
                    listOf(1, 2, 3, 5) to 1,
                )
            var previousInteractionId: String? = null
            for ((offered, selected) in choices) {
                val published = awaitNumberChoice(board, result, previousInteractionId)
                previousInteractionId = published.interactionId
                val request =
                    board.bridge.cutCoordinator
                        .drain(SeatId(1))
                        .flatten()
                        .single { it.hasSelectNReq() }
                        .selectNReq
                request.prompt.parametersList.map { it.stringValue } shouldBe offered.map(Int::toString)
                board.bridge.cutCoordinator.acceptSettled(
                    selectNResp(listOf(offered.indexOf(selected) + 1)),
                    published.gameStateId,
                ) shouldBe true
            }
            result.get(3, TimeUnit.SECONDS)
            assertSoftly {
                source.remembered.toList() shouldBe listOf(4, 5)
                opponent.life shouldBe 17
                board.human.getZone(ZoneType.Hand).size() shouldBe 2
                board.human.battlefield.card("The Toymaker's Trap") shouldBe source
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .snapshotChoiceResults() shouldBe emptyList()
            }
        }
    })

private fun <T> numberCallback(
    board: Board,
    callback: () -> T,
): CompletableFuture<T> =
    CompletableFuture<T>().also { result ->
        Thread {
            try {
                board.bridge.promptBridge(SeatId(1)).setDiagnosticContext(board.game, Thread.currentThread())
                result.complete(callback())
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }.start()
    }

private fun awaitNumberChoice(
    board: Board,
    result: CompletableFuture<*>,
    previousInteractionId: String? = null,
): PublishedStaticChoiceInteraction {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (System.nanoTime() < deadline) {
        board.bridge.cutCoordinator.staticChoices
            .current()
            ?.takeIf { it.interactionId != previousInteractionId }
            ?.let { return it }
        if (result.isDone) {
            result.get()
            error("Number callback completed without publishing a choice")
        }
        Thread.onSpinWait()
    }
    error("Number choice was not published")
}
