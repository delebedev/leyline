package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.RuntimeHorizonMode
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.bundle.MessageCounter
import leyline.game.generator.PuzzleSource
import leyline.game.state.GameBridge
import leyline.testkit.BoardTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

class MatchLifecycleRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:lifecycle replacement
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Mountain
            humanbattlefield=Forest
            ailibrary=Island
            """.trimIndent()

        test("startup lifecycle batches commit in one coordinator order") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val coordinator = bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val priorRevision = bridge.projectionStateSnapshot().revision

            coordinator.lifecycle.publishInitial(
                SeatId(1),
                includeStartingPlayerPrompt = true,
                seedProjectionCursor = false,
            )
            coordinator.lifecycle.publishDealHand(SeatId(1))

            val batches = coordinator.drain(SeatId(1))
            assertSoftly {
                batches.size shouldBe 2
                batches[0].map { it.type } shouldBe
                    listOf(
                        GREMessageType.ConnectResp_695e,
                        GREMessageType.DieRollResultsResp_695e,
                        GREMessageType.GameStateMessage_695e,
                    )
                batches[1].map { it.type } shouldBe listOf(GREMessageType.GameStateMessage_695e)
                batches.flatten().map { it.msgId } shouldBe batches.flatten().map { it.msgId }.sorted()
                bridge.projectionStateSnapshot().revision shouldBe priorRevision + 2
            }
        }

        test("failed startup install publishes no batch or projection baseline") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val coordinator = bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = bridge.projectionStateSnapshot()
            coordinator.registerViewer(SeatId(1))
            coordinator.feed(SeatId(1)).beforeBatchEnqueue = { _, _ -> error("projection unavailable") }

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.lifecycle.publishInitial(
                    SeatId(1),
                    includeStartingPlayerPrompt = true,
                    seedProjectionCursor = false,
                )
            }

            assertSoftly {
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                bridge.projectionStateSnapshot() shouldBe prior
            }
        }

        test("puzzle replacement publishes state and actions as one ordered batch") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            val pending =
                checkNotNull(
                    board.bridge
                        .seat(SeatId(1))
                        .action
                        .getPending(),
                )
            val priorRevision = board.bridge.projectionStateSnapshot().revision

            coordinator.lifecycle.publishPuzzleReplacement(SeatId(1), emptyList(), pending.actionId)

            val batch = coordinator.drain(SeatId(1)).single()
            assertSoftly {
                batch.map { it.type } shouldBe
                    listOf(
                        GREMessageType.GameStateMessage_695e,
                        GREMessageType.ActionsAvailableReq_695e,
                    )
                batch.map { it.msgId } shouldBe batch.map { it.msgId }.sorted()
                batch.map { it.gameStateId }.distinct().size shouldBe 1
                board.bridge.projectionStateSnapshot().revision shouldBe priorRevision + 1
            }
        }

        test("failed puzzle replacement publishes no batch or projection baseline") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            val pending =
                checkNotNull(
                    board.bridge
                        .seat(SeatId(1))
                        .action
                        .getPending(),
                )
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.feed(SeatId(1)).beforeBatchEnqueue = { _, _ -> error("projection unavailable") }

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.lifecycle.publishPuzzleReplacement(SeatId(1), emptyList(), pending.actionId)
            }

            assertSoftly {
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe prior
            }
        }

        test("puzzle replacement preserves a synchronization horizon after full state") {
            val bridge =
                GameBridge(
                    runtimeHorizonMode = RuntimeHorizonMode.Observed,
                    messageCounter = MessageCounter(initialGsId = 20, initialMsgId = 0),
                    cardRepository = TestCardRegistry.repo,
                )
            useBridge(bridge)
            bridge.startPuzzle(PuzzleSource.loadFromText(puzzle.replace("ActivePlayer=Human", "ActivePlayer=AI")))
            TestCardRegistry.registerPuzzleCards(checkNotNull(bridge.getGame()))
            val coordinator = bridge.cutCoordinator
            val pending =
                checkNotNull(
                    bridge
                        .seat(SeatId(1))
                        .action
                        .getPending(),
                )
            pending.state.kind shouldBe PendingActionKind.SYNC_ONLY

            coordinator.lifecycle.publishPuzzleReplacement(SeatId(1), emptyList(), pending.actionId)

            val batches = coordinator.drain(SeatId(1))
            assertSoftly {
                batches.first().single().type shouldBe GREMessageType.GameStateMessage_695e
                batches.drop(1).flatten().none { it.hasActionsAvailableReq() } shouldBe true
                bridge
                    .seat(SeatId(1))
                    .action
                    .getPending()
                    ?.state
                    ?.kind shouldBe PendingActionKind.SYNC_ONLY
            }
        }
    })
