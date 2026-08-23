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

/**
 * Generic single-batch cut transaction owned by [CoordinatorCutInstaller].
 *
 * Every runtime family routes its publication through this one implementation,
 * so the enqueue/commit/rollback/acknowledge invariants are proven once here.
 * `RuntimeBoundaryTest` pins that no family reimplements the transaction, and
 * family suites keep only their own diagnostic-cut and semantic proofs.
 */
class CoordinatorCutInstallerTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:coordinator cut installer
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

        test("materialization and enqueue failures publish nothing and preserve unrelated output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            val materialization =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.cardSelect.awaitSelection(request(board, Int.MAX_VALUE), options(board), 3_000)
                }
            assertSoftly {
                materialization.promptMaterializationDiagnostic.shouldNotBeNull()
                materialization.pendingPromptCut.shouldBeNull()
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
                enqueue.pendingPromptCut.shouldNotBeNull()
                enqueueCoordinator.drain(SeatId(1)) shouldContainExactly listOf(existing)
            }
        }

        test("stale install rolls back only the owned batch and keeps the competing projection") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val existing = listOf(GREToClientMessage.getDefaultInstance())
            coordinator.enqueueCommittedBatchForTest(SeatId(1), existing)
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
                stale.pendingPromptCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)) shouldContainExactly listOf(existing)
                board.bridge.projectionStateSnapshot() shouldBe competing
            }
        }

        test("post-install failure retains the committed transition and the exact output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.cardSelect.afterInstall = { error("ack unavailable") }
            val committed =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.cardSelect.awaitSelection(request(board), options(board), 3_000)
                }
            val retained = coordinator.drain(SeatId(1)).single()
            assertSoftly {
                committed.pendingPromptCut.shouldNotBeNull().messages shouldBe retained
                board.bridge.projectionStateSnapshot().revision shouldBe prior.revision + 1
                coordinator.cardSelect.current().shouldBeNull()
            }
        }
    })
