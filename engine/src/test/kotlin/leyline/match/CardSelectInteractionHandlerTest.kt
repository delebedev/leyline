package leyline.match

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.cardSelectRuntime
import leyline.bridge.handoff.CardSelectInteractionResult
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.effectCostResp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CardSelectInteractionHandlerTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:card select session adapter
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

        fun request(board: Board): PromptRequest {
            val cards = options(board)
            return PromptRequest(
                promptType = "choose_cards",
                message = "Choose a permanent",
                options = cards.map { it.name },
                min = 1,
                max = 1,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Hand.name)
                    },
                route = PromptRouteResolver.resolve(PromptSemantic.SelectNSacrificeEffect),
                sourceEntityId =
                    board.human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .single()
                        .id,
            )
        }

        test("EffectCostResp completes the exact coordinator window without a legacy pending prompt") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val handles = options(board)
            val result = AtomicReference<CardSelectInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(request(board), handles, 3_000))
                finished.countDown()
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.cardSelect.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.cardSelect.current()
            }
            val exact = checkNotNull(published)
            val selectedId =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .selectNReq.idsList[1]
            var autoPassed = false

            CardSelectInteractionHandler(SessionContext(checkNotNull(board.bridge.getGame()), board.bridge))
                .tryHandleEffectCost(
                    effectCostResp(listOf(selectedId)).toBuilder().setGameStateId(exact.gameStateId).build(),
                ) { autoPassed = true }
                .shouldBeTrue()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().optionIndices shouldBe listOf(1)
                (result.get().handles.single() === handles[1]) shouldBe true
                autoPassed shouldBe true
                board.bridge
                    .promptBridge(SeatId(1))
                    .getPendingPrompt()
                    .shouldBeNull()
                coordinator.cardSelect.current().shouldBeNull()
            }
        }
    })
