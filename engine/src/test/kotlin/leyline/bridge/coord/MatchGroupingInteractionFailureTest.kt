package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.GroupingInteractionResult
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchGroupingInteractionFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:grouping failures
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanlibrary=Mountain;Forest
            ailibrary=Forest
            """.trimIndent()

        fun options(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Library)
                .cards
                .toList()
                .take(2)

        fun request(board: Board): PromptRequest {
            val cards = options(board)
            return PromptRequest(
                promptType = "choose_cards",
                message = "Arrange cards",
                options = cards.map { it.name },
                min = 0,
                max = cards.size,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Library.name)
                    },
                route = ResolvedPromptRoute.Grouping(PromptSemantic.GroupingScry, GroupingContext.Scry_a0f6),
            )
        }

        fun singleRequest(board: Board): PromptRequest {
            val card = options(board).first()
            return request(board).copy(
                promptType = "confirm",
                options = listOf("Top of library", "Bottom of library"),
                min = 1,
                max = 1,
                candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, card.id, ZoneType.Library.name)),
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedGroupingInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var current = coordinator.grouping.current()
            while (current == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                current = coordinator.grouping.current()
            }
            return checkNotNull(current)
        }

        test("timeout winner returns the legacy default partition and rejects a late response") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<GroupingInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.grouping.awaitGrouping(request(board), options(board), 25))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val ids =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasGroupReq() }
                    .groupReq.instanceIdsList
            finished.await(3, TimeUnit.SECONDS) shouldBe true
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()

            assertSoftly {
                result.get().timedOut shouldBe true
                result.get().awayHandles.single() shouldBe options(board).first()
                coordinator.acceptSettled(groupResp(ids, emptyList()), published.gameStateId) shouldBe false
                coordinator.grouping
                    .current()
                    .shouldBeNull()
                coordinator.failure().shouldBeNull()
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
            }
            coordinator.grouping.finalizeArrangement(result.get(), result.get().topHandles, result.get().awayHandles)
        }

        test("single-card timeout keeps the default card on top and rejects a late response") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val card = options(board).first()
            val result = AtomicReference<GroupingInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.grouping.awaitGrouping(singleRequest(board), listOf(card), 25))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val id =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasGroupReq() }
                    .groupReq.instanceIdsList
                    .single()
            finished.await(3, TimeUnit.SECONDS) shouldBe true

            assertSoftly {
                result.get().timedOut shouldBe true
                result.get().topHandles.single() shouldBe card
                result.get().awayHandles shouldBe emptyList()
                coordinator.acceptSettled(groupResp(listOf(id), emptyList()), published.gameStateId) shouldBe false
                coordinator.grouping
                    .current()
                    .shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
            coordinator.grouping.finalizeArrangement(result.get(), result.get().topHandles, emptyList())
        }

        test("shutdown clears a completed result awaiting arrangement finalization") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<GroupingInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.grouping.awaitGrouping(request(board), options(board), null))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val ids =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasGroupReq() }
                    .groupReq.instanceIdsList
            coordinator.acceptSettled(groupResp(ids, emptyList()), published.gameStateId) shouldBe true
            finished.await(3, TimeUnit.SECONDS) shouldBe true

            val promptBridge = board.bridge.promptBridge(SeatId(1))
            board.bridge.teardownResources()
            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    promptBridge.finalizeGroupingArrangement(result.get(), result.get().topHandles, emptyList())
                }

            assertSoftly {
                terminal shouldBe coordinator.failure()
                coordinator.grouping
                    .pollArrangement(SeatId(1), GroupingContext.Scry_a0f6)
                    .shouldBeNull()
            }
        }
    })
