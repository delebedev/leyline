package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.testkit.Board
import leyline.testkit.BoardTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MatchCardSelectInteractionFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:card select failures
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

        test("invalid response shapes leave the exact window and projection unchanged") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val finished = CountDownLatch(1)
            Thread {
                coordinator.cardSelect.awaitSelection(request(board), options(board), 3_000)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val ids =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .selectNReq.idsList
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()

            assertSoftly {
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId + 1,
                    listOf(ids[0]),
                ) shouldBe
                    false
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    emptyList(),
                ) shouldBe
                    false
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    listOf(ids[0], ids[0]),
                ) shouldBe
                    false
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    listOf(Int.MAX_VALUE),
                ) shouldBe
                    false
                coordinator.cardSelect.current() shouldBe published
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    listOf(ids[1]),
                ) shouldBe
                    true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    listOf(ids[1]),
                ) shouldBe
                    false
            }
        }
    })
