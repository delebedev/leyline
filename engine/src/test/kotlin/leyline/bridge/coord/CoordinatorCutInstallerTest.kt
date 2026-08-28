package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.CardSelectWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.GamePlayback
import leyline.game.PlaybackCutReason
import leyline.game.PlaybackCutRequest
import leyline.game.PlaybackTerminalFailure
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.bundle.LogicalSequenceState
import leyline.game.state.ProjectionState
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Coordinator cut transaction owned by [CoordinatorCutInstaller].
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

        test("preparation carries tentative sequence and output order without changing its prior") {
            val prior =
                ProjectionState.initial(
                    sequence = LogicalSequenceState(currentGsId = 4, currentMsgId = 8, committedOutputOrdinal = 12),
                )
            val planner = LogicalSequencePlanner(prior.sequence)
            val message =
                GREToClientMessage
                    .newBuilder()
                    .setGameStateId(planner.nextGsId())
                    .setMsgId(planner.nextMsgId())
                    .build()

            val prepared = PreparedCut.prepare(prior, planner, listOf(message), projection = null, closesPlaybackFrame = false)

            assertSoftly {
                prepared.outputOrdinal shouldBe 13
                prepared.transition.expectedRevision shouldBe prior.revision
                prepared.transition.nextState.sequence shouldBe planner.snapshot()
                prepared.transition.nextState.sequence.currentGsId shouldBe 5
                prepared.transition.nextState.sequence.currentMsgId shouldBe 9
                prepared.transition.nextState.sequence.committedOutputOrdinal shouldBe 13
                prior.sequence shouldBe LogicalSequenceState(currentGsId = 4, currentMsgId = 8, committedOutputOrdinal = 12)
            }
        }

        test("preparation rejects identity rewind without changing committed state") {
            val prior = ProjectionState.initial(sequence = LogicalSequenceState(currentGsId = 6, currentMsgId = 11))
            val planner = LogicalSequencePlanner(prior.sequence)
            planner.setGsId(2)
            planner.setMsgId(3)

            shouldThrow<IllegalStateException> {
                PreparedCut.prepare(prior, planner, emptyList(), projection = null, closesPlaybackFrame = false)
            }

            prior.sequence shouldBe LogicalSequenceState(currentGsId = 6, currentMsgId = 11)
        }

        test("projection-only install changes no feed or output sequence") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            val transition = board.bundleBuilder().prepareSearchBaselineReset(prior)

            synchronized(coordinator.feedLock) {
                coordinator.cutInstaller.installProjectionOnly(transition, onFailure = { throw it })
            }

            val installed = board.bridge.projectionStateSnapshot()
            shouldThrow<IllegalArgumentException> {
                synchronized(coordinator.feedLock) {
                    coordinator.cutInstaller.installProjectionOnly(transition, onFailure = { throw it })
                }
            }
            assertSoftly {
                installed.revision shouldBe prior.revision + 1
                installed.sequence shouldBe prior.sequence
                board.bridge.projectionStateSnapshot() shouldBe installed
                coordinator.drain(SeatId(1)).shouldBeEmpty()
            }
        }

        test("all-view install rolls back a first feed when the second feed enqueue fails") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            coordinator.registerViewer(SeatId(2))
            val prior = board.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            val msgId = planner.nextMsgId()
            val player =
                GREToClientMessage
                    .newBuilder()
                    .setMsgId(msgId)
                    .addSystemSeatIds(1)
                    .build()
            val observer =
                GREToClientMessage
                    .newBuilder()
                    .setMsgId(msgId)
                    .addSystemSeatIds(2)
                    .build()
            val cut =
                PreparedCut.prepareForViewers(
                    prior,
                    planner,
                    listOf(
                        PreparedViewerOutput(SeatId(1), listOf(listOf(player))),
                        PreparedViewerOutput(SeatId(2), listOf(listOf(observer))),
                    ),
                    projection = null,
                    closesPlaybackFrame = false,
                )
            coordinator.setBeforeBatchEnqueue(SeatId(2)) { _, _ -> error("observer feed unavailable") }

            shouldThrow<IllegalStateException> {
                synchronized(coordinator.feedLock) {
                    coordinator.cutInstaller.install(cut, onFailure = { throw it })
                }
            }

            assertSoftly {
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.drain(SeatId(2)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe prior
            }
        }

        test("multi-batch install commits one ordinal and acknowledges once in stable order") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            GamePlayback(board.bridge, 1)
            val feed = coordinator.feed(SeatId(1))
            feed.requestedCut = PlaybackCutRequest(PlaybackCutReason.PhaseChanged, 0, false)
            val prior = board.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            val first =
                GREToClientMessage
                    .newBuilder()
                    .setGameStateId(planner.nextGsId())
                    .setMsgId(planner.nextMsgId())
                    .build()
            val second =
                GREToClientMessage
                    .newBuilder()
                    .setGameStateId(planner.nextGsId())
                    .setMsgId(planner.nextMsgId())
                    .build()
            val batches = listOf(listOf(first), listOf(second))
            val cut = PreparedCut.prepare(prior, planner, batches.flatten(), projection = null, closesPlaybackFrame = true)
            val indexes = mutableListOf<Int>()
            var installedCallbacks = 0
            feed.beforeBatchEnqueue = { index, _ -> indexes += index }

            synchronized(coordinator.feedLock) {
                coordinator.cutInstaller.install(
                    feed = feed,
                    cut = cut,
                    batches = batches,
                    onInstalled = { installedCallbacks += 1 },
                    onFailure = { throw it },
                )
            }

            val owned = synchronized(coordinator.feedLock) { feed.queue.toList() }
            assertSoftly {
                indexes shouldContainExactly listOf(0, 1)
                owned.map { it.batchIndex } shouldContainExactly listOf(0, 1)
                owned.map { it.ordinal } shouldContainExactly listOf(cut.outputOrdinal, cut.outputOrdinal)
                owned.map { it.messages } shouldContainExactly batches
                board.bridge.projectionStateSnapshot().revision shouldBe prior.revision + 1
                board.bridge.committedSequence().committedOutputOrdinal shouldBe cut.outputOrdinal
                feed.requestedCut.shouldBeNull()
                installedCallbacks shouldBe 1
            }
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
                materialization.promptMaterializationDiagnostic
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<CardSelectWindowValue>()
                materialization.pendingPromptCut.shouldBeNull()
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe prior
            }

            val enqueueBoard = startPuzzleAtMain1(puzzle)
            val enqueueCoordinator = enqueueBoard.bridge.cutCoordinator
            enqueueCoordinator.drain(SeatId(1))
            val enqueuePrior = enqueueBoard.bridge.projectionStateSnapshot()
            val existing = listOf(GREToClientMessage.getDefaultInstance())
            enqueueCoordinator.enqueueCommittedBatchForTest(SeatId(1), existing)
            enqueueCoordinator.setBeforeBatchEnqueue(SeatId(1)) { _, _ -> error("feed unavailable") }
            val enqueue =
                shouldThrow<PlaybackTerminalFailure> {
                    enqueueCoordinator.cardSelect.awaitSelection(request(enqueueBoard), options(enqueueBoard), 3_000)
                }
            assertSoftly {
                enqueue.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<CardSelectWindowValue>()
                enqueueCoordinator.drain(SeatId(1)) shouldContainExactly listOf(existing)
                enqueueBoard.bridge.projectionStateSnapshot() shouldBe enqueuePrior
            }
        }

        test("stale install rolls back only the owned batch and keeps the competing projection") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val existing = listOf(GREToClientMessage.getDefaultInstance())
            coordinator.enqueueCommittedBatchForTest(SeatId(1), existing)
            val priorSequence = board.bridge.committedSequence()
            val competing =
                board.bridge
                    .projectionStateSnapshot()
                    .editor()
                    .freeze()
            coordinator.prompts.settled.beforeInstall = { board.bridge.replaceProjectionStateForTest(competing) }
            val stale =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.cardSelect.awaitSelection(request(board), options(board), 3_000)
                }
            assertSoftly {
                stale.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<CardSelectWindowValue>()
                coordinator.drain(SeatId(1)) shouldContainExactly listOf(existing)
                board.bridge.projectionStateSnapshot() shouldBe competing
                board.bridge.committedSequence() shouldBe priorSequence
            }
        }

        test("post-install failure retains the committed transition and the exact output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.prompts.settled.afterInstall = { error("ack unavailable") }
            val committed =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.cardSelect.awaitSelection(request(board), options(board), 3_000)
                }
            val retained = coordinator.drain(SeatId(1)).single()
            val installed = board.bridge.projectionStateSnapshot()
            assertSoftly {
                committed.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<CardSelectWindowValue>()
                committed.pendingPromptCut.shouldNotBeNull().messages shouldBe retained
                installed.revision shouldBe prior.revision + 1
                installed.sequence.currentGsId shouldBeGreaterThan prior.sequence.currentGsId
                installed.sequence.currentMsgId shouldBeGreaterThan prior.sequence.currentMsgId
                installed.sequence.committedOutputOrdinal shouldBe prior.sequence.committedOutputOrdinal + 1
                coordinator.cardSelect.current().shouldBeNull()
            }
        }
    })
