package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.handoff.RuntimeHorizonMode
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.generator.PuzzleSource
import leyline.game.state.GameBridge
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.testkit.BoardTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.util.concurrent.CompletableFuture

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

        fun syncPuzzleBridge(): GameBridge =
            GameBridge(
                runtimeHorizonMode = RuntimeHorizonMode.Observed,
                initialSequence = LogicalSequencePlanner(initialGsId = 20, initialMsgId = 0).snapshot(),
                cardRepository = TestCardRegistry.repo,
            ).also { bridge ->
                useBridge(bridge)
                bridge.startPuzzle(PuzzleSource.loadFromText(puzzle.replace("ActivePlayer=Human", "ActivePlayer=AI")))
                TestCardRegistry.registerPuzzleCards(checkNotNull(bridge.getGame()))
            }

        test("startup lifecycle batches commit in one coordinator order") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val coordinator = bridge.cutCoordinator
            coordinator.registerViewers(
                listOf(
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                ),
            )
            coordinator.drain(SeatId(1))
            val priorRevision = bridge.projectionStateSnapshot().revision

            coordinator.lifecycle.publishInitial(
                SeatId(1),
                includeStartingPlayerPrompt = true,
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

        test("repeated startup delivery reuses the installed viewer batch") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val coordinator = bridge.cutCoordinator
            coordinator.registerViewer(SeatId(1))

            val gameStateId = coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            val installed = coordinator.drain(SeatId(1)).single()
            val committed = bridge.projectionStateSnapshot()

            coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true) shouldBe gameStateId

            assertSoftly {
                coordinator.drain(SeatId(1)).single() shouldBe installed
                bridge.projectionStateSnapshot() shouldBe committed
            }
        }

        test("reconnect after gameplay publishes only the current viewer state and action horizon") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                }
            val coordinator = board.bridge.cutCoordinator
            coordinator.registerViewers(
                listOf(
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                ),
            )
            val initialGameStateId = coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            coordinator.drain(SeatId(1))
            coordinator.drain(SeatId(2))

            board.human.setLife(13, null)
            val pending =
                GameActionBridge.PendingAction(
                    actionId = "reconnect-priority",
                    state = PendingActionState("Main1", 1, 1, 1),
                    future = CompletableFuture(),
                    priorityCandidates = PriorityActionCandidates.query(board.game, board.human),
                    windowRuntime = coordinator.actionWindowRuntime(SeatId(1)),
                )
            coordinator.actions.publish(SeatId(1), pending)
            val priorActionRequest = coordinator.drain(SeatId(1)).single().single { it.hasActionsAvailableReq() }
            coordinator.drain(SeatId(2))
            val priorSequence = board.bridge.committedSequence()
            val priorObserverCursor = board.bridge.projectionStateSnapshot().viewerCursors[SeatId(2)]

            val reconnectGameStateId = coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            val reconnect = coordinator.drain(SeatId(1)).single()

            assertSoftly {
                reconnect.map { it.type } shouldBe
                    listOf(
                        GREMessageType.ConnectResp_695e,
                        GREMessageType.GameStateMessage_695e,
                        GREMessageType.ActionsAvailableReq_695e,
                    )
                reconnectGameStateId shouldBeGreaterThan initialGameStateId
                reconnectGameStateId shouldBeGreaterThan priorSequence.currentGsId
                reconnect.first().msgId shouldBeGreaterThan priorSequence.currentMsgId
                reconnect.map { it.msgId } shouldBe reconnect.map { it.msgId }.sorted()
                reconnect
                    .first { it.hasGameStateMessage() }
                    .gameStateMessage.playersList
                    .single { it.systemSeatNumber == 1 }
                    .lifeTotal shouldBe 13
                reconnect.last().actionsAvailableReq shouldBe priorActionRequest.actionsAvailableReq
                reconnect.last().gameStateId shouldBe reconnectGameStateId
                pending.promptGameStateId shouldBe reconnectGameStateId
                board.bridge.committedSequence().committedOutputOrdinal shouldBe priorSequence.committedOutputOrdinal + 1
                board.bridge.projectionStateSnapshot().viewerCursors[SeatId(2)] shouldBe priorObserverCursor
                coordinator.drain(SeatId(2)).shouldBeEmpty()
            }
        }

        test("reconnect replaces an undelivered action horizon") {
            val board = startWithBoard { _, human, _ -> addCard("Forest", human, ZoneType.Hand) }
            val coordinator = board.bridge.cutCoordinator
            coordinator.registerViewer(SeatId(1))
            coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            coordinator.drain(SeatId(1))

            val pending =
                GameActionBridge.PendingAction(
                    actionId = "queued-reconnect-priority",
                    state = PendingActionState("Main1", 1, 1, 1),
                    future = CompletableFuture(),
                    priorityCandidates = PriorityActionCandidates.query(board.game, board.human),
                    windowRuntime = coordinator.actionWindowRuntime(SeatId(1)),
                )
            coordinator.actions.publish(SeatId(1), pending)
            val stale =
                coordinator
                    .feed(SeatId(1))
                    .queue
                    .single()
                    .messages
            val staleRequest = stale.single { it.hasActionsAvailableReq() }

            val reconnectGameStateId = coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            val delivered = coordinator.drain(SeatId(1)).single()

            assertSoftly {
                delivered.map { it.type } shouldBe
                    listOf(
                        GREMessageType.ConnectResp_695e,
                        GREMessageType.GameStateMessage_695e,
                        GREMessageType.ActionsAvailableReq_695e,
                    )
                delivered.none { it.msgId == staleRequest.msgId } shouldBe true
                delivered.last().actionsAvailableReq shouldBe staleRequest.actionsAvailableReq
                delivered.last().gameStateId shouldBe reconnectGameStateId
                pending.promptGameStateId shouldBe reconnectGameStateId
                coordinator.drain(SeatId(1)).shouldBeEmpty()
            }
        }

        test("reconnect after progress without an action horizon publishes the current full state") {
            val board = startWithBoard { _, _, _ -> }
            val coordinator = board.bridge.cutCoordinator
            coordinator.registerViewers(
                listOf(
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                ),
            )
            val initialGameStateId = coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            coordinator.drain(SeatId(1))
            coordinator.drain(SeatId(2))

            board.human.setLife(13, null)
            coordinator.lifecycle.publishDealHand(SeatId(1))
            coordinator.drain(SeatId(1))
            coordinator.drain(SeatId(2))
            val priorSequence = board.bridge.committedSequence()
            val priorObserverCursor = board.bridge.projectionStateSnapshot().viewerCursors[SeatId(2)]

            val reconnectGameStateId = coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            val reconnect = coordinator.drain(SeatId(1)).single()

            assertSoftly {
                reconnect.map { it.type } shouldBe
                    listOf(
                        GREMessageType.ConnectResp_695e,
                        GREMessageType.GameStateMessage_695e,
                    )
                reconnectGameStateId shouldBeGreaterThan initialGameStateId
                reconnect.first().msgId shouldBeGreaterThan priorSequence.currentMsgId
                reconnect.last().gameStateMessage.pendingMessageCount shouldBe 0
                reconnect
                    .last()
                    .gameStateMessage.playersList
                    .single { it.systemSeatNumber == 1 }
                    .lifeTotal shouldBe 13
                board.bridge.committedSequence().committedOutputOrdinal shouldBe priorSequence.committedOutputOrdinal + 1
                board.bridge.projectionStateSnapshot().viewerCursors[SeatId(2)] shouldBe priorObserverCursor
                coordinator.drain(SeatId(2)).shouldBeEmpty()
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
                )
            }

            assertSoftly {
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                bridge.projectionStateSnapshot() shouldBe prior
            }
        }

        test("redraw installs reset identities and lifecycle output as one cut") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val coordinator = bridge.cutCoordinator
            coordinator.registerViewer(SeatId(1))
            coordinator.drain(SeatId(1))
            val retired = bridge.getOrAllocInstanceId(ForgeCardId(900_001))
            val prior = bridge.projectionStateSnapshot()

            coordinator.lifecycle.publishMulliganRedraw(SeatId(1), MulliganRedrawFacts(0, 7))

            val batch = coordinator.drain(SeatId(1)).single()
            val committed = bridge.projectionStateSnapshot()
            assertSoftly {
                batch.map { it.type } shouldBe
                    listOf(
                        GREMessageType.GameStateMessage_695e,
                        GREMessageType.GameStateMessage_695e,
                        GREMessageType.PromptReq,
                        GREMessageType.MulliganReq_aa0d,
                    )
                batch.first().gameStateMessage.diffDeletedInstanceIdsList shouldBe listOf(retired.value)
                batch.map { it.msgId } shouldBe batch.map { it.msgId }.sorted()
                batch.map { it.gameStateId }.distinct().size shouldBe 2
                committed.identities.forgeIdToInstanceId.containsKey(ForgeCardId(900_001)) shouldBe false
                committed.sequence.committedOutputOrdinal shouldBe prior.sequence.committedOutputOrdinal + 1
                committed.revision shouldBe prior.revision + 1
            }
        }

        test("redraw preparation failure preserves identities sequence and feed") {
            val bridge =
                GameBridge(
                    initialSequence = LogicalSequencePlanner(initialGsId = 20, initialMsgId = 0).snapshot(),
                    cardRepository = TestCardRegistry.repo,
                )
            useBridge(bridge)
            bridge.getOrAllocInstanceId(ForgeCardId(900_002))
            val coordinator = bridge.cutCoordinator
            coordinator.registerViewer(SeatId(1))
            val prior = bridge.projectionStateSnapshot()
            val priorFeed = coordinator.feed(SeatId(1)).queue.toList()

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.lifecycle.publishMulliganRedraw(SeatId(1), MulliganRedrawFacts(0, 7))
            }

            assertSoftly {
                bridge.projectionStateSnapshot() shouldBe prior
                coordinator.feed(SeatId(1)).queue.toList() shouldBe priorFeed
            }
        }

        test("redraw enqueue failure preserves identities sequence and feed") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val coordinator = bridge.cutCoordinator
            coordinator.registerViewer(SeatId(1))
            coordinator.drain(SeatId(1))
            bridge.getOrAllocInstanceId(ForgeCardId(900_010))
            val prior = bridge.projectionStateSnapshot()
            val priorFeed = coordinator.feed(SeatId(1)).queue.toList()
            coordinator.feed(SeatId(1)).beforeBatchEnqueue = { _, _ -> error("redraw enqueue unavailable") }

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.lifecycle.publishMulliganRedraw(SeatId(1), MulliganRedrawFacts(0, 7))
            }

            assertSoftly {
                bridge.projectionStateSnapshot() shouldBe prior
                coordinator.feed(SeatId(1)).queue.toList() shouldBe priorFeed
            }
        }

        test("stale redraw install preserves the competing projection and prior feed") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val coordinator = bridge.cutCoordinator
            coordinator.registerViewer(SeatId(1))
            coordinator.drain(SeatId(1))
            bridge.getOrAllocInstanceId(ForgeCardId(900_011))
            val prior = bridge.projectionStateSnapshot()
            val priorFeed = coordinator.feed(SeatId(1)).queue.toList()
            val competing = prior.copy(revision = prior.revision + 1)
            coordinator.lifecycle.beforeRedrawInstall = { bridge.replaceProjectionStateForTest(competing) }

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.lifecycle.publishMulliganRedraw(SeatId(1), MulliganRedrawFacts(0, 7))
            }

            assertSoftly {
                bridge.projectionStateSnapshot() shouldBe competing
                coordinator.feed(SeatId(1)).queue.toList() shouldBe priorFeed
            }
        }

        test("redraw post-install failure retains identities and output together") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val coordinator = bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            bridge.getOrAllocInstanceId(ForgeCardId(900_020))
            val prior = bridge.projectionStateSnapshot()
            coordinator.lifecycle.afterRedrawInstall = { error("redraw acknowledgement unavailable") }

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.lifecycle.publishMulliganRedraw(SeatId(1), MulliganRedrawFacts(0, 7))
            }

            val committed = bridge.projectionStateSnapshot()
            assertSoftly {
                committed.revision shouldBe prior.revision + 1
                committed.identities.forgeIdToInstanceId.containsKey(ForgeCardId(900_020)) shouldBe false
                committed.sequence.committedOutputOrdinal shouldBe prior.sequence.committedOutputOrdinal + 1
                coordinator
                    .feed(SeatId(1))
                    .queue
                    .single()
                    .messages.size shouldBe 4
            }
        }

        test("startup preparation failure terminalizes without publication") {
            val bridge =
                GameBridge(
                    initialSequence = LogicalSequencePlanner(initialGsId = 20, initialMsgId = 0).snapshot(),
                    cardRepository = TestCardRegistry.repo,
                )
            useBridge(bridge)
            val coordinator = bridge.cutCoordinator
            val prior = bridge.projectionStateSnapshot()
            coordinator.registerViewers(listOf(ProjectionViewer(SeatId(1), ProjectionViewerRole.Player)))

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.lifecycle.publishInitial(
                    SeatId(1),
                    includeStartingPlayerPrompt = true,
                )
            }

            assertSoftly {
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                bridge.projectionStateSnapshot() shouldBe prior
                shouldThrow<PlaybackTerminalFailure> { coordinator.lifecycle.publishDealHand(SeatId(1)) }
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
            val bridge = syncPuzzleBridge()
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

            val batch = coordinator.drain(SeatId(1)).single()
            assertSoftly {
                batch.first().type shouldBe GREMessageType.GameStateMessage_695e
                batch.count { it.hasGameStateMessage() } shouldBe 4
                batch.none { it.hasActionsAvailableReq() } shouldBe true
                bridge
                    .seat(SeatId(1))
                    .action
                    .getPending()
                    ?.state
                    ?.kind shouldBe PendingActionKind.SYNC_ONLY
            }
        }

        test("puzzle startup publishes full state and synchronization transition as one cut") {
            val bridge = syncPuzzleBridge()
            val coordinator = bridge.cutCoordinator
            val pending = checkNotNull(bridge.seat(SeatId(1)).action.getPending())
            val priorRevision = bridge.projectionStateSnapshot().revision

            coordinator.lifecycle.publishPuzzleInitial(SeatId(1), pending.actionId)

            val batch = coordinator.drain(SeatId(1)).single()
            assertSoftly {
                batch.first().type shouldBe GREMessageType.ConnectResp_695e
                batch.count { it.hasGameStateMessage() } shouldBe 4
                batch.none { it.hasActionsAvailableReq() } shouldBe true
                bridge.projectionStateSnapshot().revision shouldBe priorRevision + 1
            }
        }

        test("manual full state commits its sequence and owned output through lifecycle publication") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.committedSequence()

            val publication = coordinator.lifecycle.publishFullState(SeatId(1))

            val owned = coordinator.feed(SeatId(1)).queue.single()
            assertSoftly {
                owned.ordinal shouldBe prior.committedOutputOrdinal + 1
                owned.messages.map { it.type } shouldBe
                    listOf(GREMessageType.GameStateMessage_695e, GREMessageType.ActionsAvailableReq_695e)
                publication.gameStateId shouldBe owned.messages.first().gameStateId
                publication.objectCount shouldBe
                    owned.messages
                        .first()
                        .gameStateMessage.gameObjectsCount
                publication.zoneCount shouldBe
                    owned.messages
                        .first()
                        .gameStateMessage.zonesCount
                board.bridge.committedSequence().committedOutputOrdinal shouldBe owned.ordinal
            }
        }

        test("stale synchronization replacement restores its prior batch") {
            val bridge = syncPuzzleBridge()
            val coordinator = bridge.cutCoordinator
            val pending = checkNotNull(bridge.seat(SeatId(1)).action.getPending())
            val feed = coordinator.feed(SeatId(1))
            val priorBatches = feed.queue.toList()
            val priorProjection = bridge.projectionStateSnapshot()
            val competingProjection = priorProjection.copy(revision = priorProjection.revision + 1)
            feed.beforeBatchEnqueue = { _, _ -> bridge.replaceProjectionStateForTest(competingProjection) }

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.lifecycle.publishPuzzleReplacement(SeatId(1), emptyList(), pending.actionId)
            }

            assertSoftly {
                feed.queue.toList() shouldBe priorBatches
                bridge.projectionStateSnapshot() shouldBe competingProjection
                feed.queue.flatMap { it.messages }.none { it.hasActionsAvailableReq() } shouldBe true
            }
        }
    })
