package leyline.match

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.RevealChoiceInteractionResult
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.selectNResp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RevealChoiceInteractionHandlerTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:reveal choice session adapter
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Island
            humanlibrary=Forest
            aihand=Mountain;Forest
            ailibrary=Grizzly Bears
            """.trimIndent()

        fun cards(board: Board): List<Card> =
            board.ai
                .getZone(ZoneType.Hand)
                .cards
                .toList()

        test("SelectN response completes the exact reveal window without a legacy pending prompt") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val handles = cards(board)
            val journal = board.bridge.promptBridge(SeatId(1)).journal
            journal.record(PromptSideEffect.RevealStarted(handles.map { ForgeCardId(it.id) }, SeatId(2)))
            val entry = checkNotNull(journal.activeRevealEntry())
            val request =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose a card",
                    options = handles.map { it.name },
                    min = 1,
                    max = 1,
                    candidateRefs =
                        handles.mapIndexed { index, card ->
                            PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Hand.name)
                        },
                    route = PromptRouteResolver.resolve(PromptSemantic.RevealChoose),
                )
            val result = AtomicReference<RevealChoiceInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.revealChoices.awaitSelection(request, handles, entry, false, 3_000))
                finished.countDown()
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.revealChoices.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.revealChoices.current()
            }
            val exact = checkNotNull(published)
            val selectedId =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .selectNReq.idsList[1]
            RevealChoiceInteractionHandler(SessionContext(checkNotNull(board.bridge.getGame()), board.bridge))
                .tryHandleSelectN(
                    selectNResp(listOf(selectedId)).toBuilder().setGameStateId(exact.gameStateId).build(),
                ).shouldBeTrue()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().optionIndices shouldBe listOf(1)
                (result.get().handles.single() === handles[1]) shouldBe true
                coordinator.revealChoices
                    .current()
                    .shouldBeNull()
            }
        }
    })
