package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.PlaybackCutReason
import leyline.game.PlaybackCutRequest
import leyline.game.PlaybackTerminalFailure
import leyline.game.awaitFreshPending
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchCutCoordinatorTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:coordinator binding
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Forest
            humanbattlefield=Forest
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        test("puzzle binding removes only the unpublished presentation and retains its catalog") {
            val board = startPuzzleAtMain1(puzzle)
            val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())

            val actions = board.bridge.cutCoordinator.bindInitialActionWindow(pending.actionId, 77)

            assertSoftly {
                pending.promptGameStateId shouldBe 77
                actions.actionsList.any { it.actionType == ActionType.Pass } shouldBe true
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .none { it.hasActionsAvailableReq() } shouldBe true
            }
        }

        test("runtime token resolves one live action and closes the old window") {
            val board = startPuzzleAtMain1(puzzle)
            val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
            val messages =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
            val pass =
                messages
                    .first { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .first { it.actionType == ActionType.Pass }
            val promptGsId = checkNotNull(pending.promptGameStateId)

            val claim =
                board.bridge.cutCoordinator
                    .claimPriorityResponse(pending.actionId, promptGsId, pass, defer = false)
                    .shouldNotBeNull()
            board.bridge.actionBridge(SeatId(1)).getPending() shouldBe null
            val timerStops =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .filter { it.hasTimerStateMessage() }
                    .flatMap { it.timerStateMessage.timersList }
                    .count { !it.running }
            timerStops shouldBe 1
            board.bridge.cutCoordinator.completeActionClaim(claim.actionClaim) shouldBe true
            val next = awaitFreshPending(board.bridge, pending.actionId, timeoutMs = 3_000)

            next.shouldNotBeNull()
            board.bridge.cutCoordinator.claimPriorityResponse(pending.actionId, promptGsId, pass, defer = false) shouldBe null
        }

        test("deferred continuation cannot resume a superseded action window") {
            val board = startPuzzleAtMain1(puzzle)
            val old = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
            val pass =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .first { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .first { it.actionType == ActionType.Pass }
            val claim =
                board.bridge.cutCoordinator
                    .claimPriorityResponse(old.actionId, checkNotNull(old.promptGameStateId), pass, defer = false)
                    .shouldNotBeNull()
            board.bridge.cutCoordinator.completeActionClaim(claim.actionClaim) shouldBe true
            val next = awaitFreshPending(board.bridge, old.actionId, timeoutMs = 3_000)

            board.bridge.cutCoordinator.claimPriorityResponse(old.actionId, 0, pass, defer = true) shouldBe null
            board.bridge
                .actionBridge(SeatId(1))
                .getPending()
                ?.actionId shouldBe next?.actionId
        }

        test("visible presentation rejection leaves projection and counters unchanged") {
            val board = startPuzzleAtMain1(puzzle)
            val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
            board.bridge.cutCoordinator
                .drain(SeatId(1))
                .shouldNotBeEmpty()
            val priorProjection = board.bridge.projectionStateSnapshot()
            val priorCounter = board.counter.snapshot()
            val priorPromptGsId = pending.promptGameStateId

            shouldThrow<IllegalStateException> {
                board.bridge.cutCoordinator.replaceWithPhaseTransition(pending.actionId)
            }.message shouldBe "Action window ${pending.actionId} is already visible"

            assertSoftly {
                board.bridge.projectionStateSnapshot() shouldBe priorProjection
                board.counter.snapshot() shouldBe priorCounter
                pending.promptGameStateId shouldBe priorPromptGsId
            }
        }

        test("ambiguous action catalog is rejected before publication") {
            val wire = Action.newBuilder().setActionType(ActionType.Pass).build()

            hasAmbiguousActionCatalog(
                listOf(
                    GameActionBridge.ActionOffer(wire, PlayerAction.PassPriority),
                    GameActionBridge.ActionOffer(wire, PlayerAction.EndTurn),
                ),
            ) shouldBe true

            hasAmbiguousActionCatalog(
                listOf(
                    GameActionBridge.ActionOffer(wire, PlayerAction.PassPriority, spellGrpId = 1),
                    GameActionBridge.ActionOffer(wire, PlayerAction.PassPriority, spellGrpId = 2),
                ),
            ) shouldBe true
        }

        test("phase replacement enqueue failure terminalizes without replaying the prior window") {
            val board = startPuzzleAtMain1(puzzle)
            val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
            val priorProjection = board.bridge.projectionStateSnapshot()
            val priorCounter = board.counter.snapshot()
            val residualMsgId = AtomicReference<Int>()
            board.bridge.cutCoordinator.beforeActionEnqueue = {
                Thread {
                    val id = board.counter.nextMsgId()
                    board.counter.markPromptMsgId(id)
                    residualMsgId.set(id)
                }.also {
                    it.start()
                    it.join()
                }
                error("delivery unavailable")
            }

            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    board.bridge.cutCoordinator.replaceWithPhaseTransition(pending.actionId)
                }
            board.bridge.cutCoordinator.beforeActionEnqueue = null

            assertSoftly {
                failure.cause?.message shouldBe "delivery unavailable"
                board.bridge.projectionStateSnapshot() shouldBe priorProjection
                board.counter.currentMsgId() shouldBeGreaterThan priorCounter.currentMsgId
                board.counter.currentMsgId() shouldBeGreaterThan residualMsgId.get() - 1
                board.counter.lastPromptMsgId() shouldBe residualMsgId.get()
                board.bridge.cutCoordinator.failure() shouldBe failure
                board.bridge.actionBridge(SeatId(1)).getPending() shouldBe null
                pending.promptGameStateId.shouldBeNull()
                board.bridge.cutCoordinator.hasCommittedBatches(SeatId(1)) shouldBe true
            }
        }

        test("action window becomes visible before the committed feed can drain") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            board.bridge.cutCoordinator.beforeActionPublished = {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            val actionBridge =
                GameActionBridge(
                    timeoutMs = 3_000,
                    windowRuntime = board.bridge.cutCoordinator.actionWindowRuntime(SeatId(1)),
                )
            val engine = Thread { actionBridge.awaitAction(PendingActionState("Main1", 1, 1, 1)) }.also { it.start() }
            check(entered.await(3, TimeUnit.SECONDS))

            actionBridge.getPending() shouldBe null
            val drained = AtomicReference<List<List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>>>()
            val drainFinished = CountDownLatch(1)
            Thread {
                drained.set(board.bridge.cutCoordinator.drain(SeatId(1)))
                drainFinished.countDown()
            }.start()
            drainFinished.await(100, TimeUnit.MILLISECONDS) shouldBe false

            release.countDown()
            check(drainFinished.await(3, TimeUnit.SECONDS))
            val pending = checkNotNull(actionBridge.getPending())
            drained.get().flatten().any { it.hasActionsAvailableReq() } shouldBe true
            actionBridge.submitTestRuntimeAction(pending.actionId, PlayerAction.PassPriority) shouldBe true
            engine.join(3_000)
            board.bridge.cutCoordinator.beforeActionPublished = null
        }

        test("timed-out action window rejects a stale response without changing committed state") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val timeoutRelease = CountDownLatch(1)
            board.bridge.cutCoordinator.beforeActionTimeoutClaim = {
                timeoutEntered.countDown()
                check(timeoutRelease.await(3, TimeUnit.SECONDS))
            }
            val actionBridge =
                GameActionBridge(
                    timeoutMs = 20,
                    windowRuntime = board.bridge.cutCoordinator.actionWindowRuntime(SeatId(1)),
                )
            val result = AtomicReference<PlayerAction>()
            val engine = Thread { result.set(actionBridge.awaitAction(PendingActionState("Main1", 1, 1, 1))) }.also { it.start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var pending = actionBridge.getPending()
            while (pending == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                pending = actionBridge.getPending()
            }
            val exact = checkNotNull(pending)
            val published =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
            val pass =
                published
                    .first { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .first { it.actionType == ActionType.Pass }
            check(timeoutEntered.await(3, TimeUnit.SECONDS))
            // The client learns the correlation while the window is open; the timeout
            // then retires the window before this stale answer arrives.
            val promptGsId = checkNotNull(exact.promptGameStateId)

            timeoutRelease.countDown()
            engine.join(3_000)
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()

            assertSoftly {
                result.get() shouldBe PlayerAction.PassPriority
                exact.promptGameStateId.shouldBeNull()
                board.bridge.cutCoordinator.claimPriorityResponse(
                    exact.actionId,
                    promptGsId,
                    pass,
                    defer = false,
                ) shouldBe null
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                board.bridge.cutCoordinator.drain(SeatId(1)) shouldBe emptyList()
            }
            board.bridge.cutCoordinator.beforeActionTimeoutClaim = null
        }

        test("timed-out synchronization barrier retains its committed batch and fails the engine wait") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val actionBridge =
                GameActionBridge(
                    timeoutMs = 100,
                    windowRuntime = board.bridge.cutCoordinator.actionWindowRuntime(SeatId(1)),
                )
            val result = AtomicReference<PlayerAction?>()
            val failure = AtomicReference<Throwable?>()
            val engine =
                Thread {
                    runCatching {
                        actionBridge.awaitAction(
                            PendingActionState(
                                phase = "Main1",
                                turn = 1,
                                activePlayerId = 1,
                                priorityPlayerId = 1,
                                kind = leyline.bridge.handoff.PendingActionKind.SYNC_ONLY,
                                synchronizationContinuation =
                                    leyline.bridge.handoff.SynchronizationContinuation.RequireVisible,
                            ),
                        )
                    }.onSuccess(result::set).onFailure(failure::set)
                }.also { it.start() }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (actionBridge.getPending() == null && System.nanoTime() < deadline) Thread.onSpinWait()
            checkNotNull(actionBridge.getPending())
            board.bridge.cutCoordinator.hasCommittedBatches(SeatId(1)) shouldBe true

            engine.join(3_000)
            val terminal = failure.get().shouldBeInstanceOf<PlaybackTerminalFailure>()
            assertSoftly {
                terminal.cause.shouldBeInstanceOf<java.util.concurrent.TimeoutException>()
                board.bridge.cutCoordinator.failure() shouldBe terminal
                board.bridge.cutCoordinator.hasCommittedBatches(SeatId(1)) shouldBe true
                result.get() shouldBe null
                actionBridge.getPending() shouldBe null
                actionBridge.consumeSynchronizationContinuation() shouldBe
                    leyline.bridge.handoff.SynchronizationContinuation.Reevaluate
            }
        }

        test("delivered synchronization pass wins against a paused timeout claim") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val timeoutRelease = CountDownLatch(1)
            board.bridge.cutCoordinator.beforeSynchronizationTimeoutClaim = {
                timeoutEntered.countDown()
                check(timeoutRelease.await(3, TimeUnit.SECONDS))
            }
            val actionBridge =
                GameActionBridge(
                    timeoutMs = 20,
                    windowRuntime = board.bridge.cutCoordinator.actionWindowRuntime(SeatId(1)),
                )
            val result = AtomicReference<PlayerAction?>()
            val failure = AtomicReference<Throwable?>()
            val engine =
                Thread {
                    runCatching {
                        actionBridge.awaitAction(
                            PendingActionState(
                                phase = "Main1",
                                turn = 1,
                                activePlayerId = 1,
                                priorityPlayerId = 1,
                                kind = leyline.bridge.handoff.PendingActionKind.SYNC_ONLY,
                            ),
                        )
                    }.onSuccess(result::set).onFailure(failure::set)
                }.also { it.start() }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var pending = actionBridge.getPending()
            while (pending == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                pending = actionBridge.getPending()
            }
            val exact = checkNotNull(pending)
            check(timeoutEntered.await(3, TimeUnit.SECONDS))

            actionBridge.completeSyncPass(exact.actionId) shouldBe true
            timeoutRelease.countDown()
            engine.join(3_000)
            board.bridge.cutCoordinator.beforeSynchronizationTimeoutClaim = null

            assertSoftly {
                result.get() shouldBe PlayerAction.PassPriority
                failure.get() shouldBe null
                board.bridge.cutCoordinator.failure() shouldBe null
                actionBridge.getPending() shouldBe null
            }
        }

        test("phase replacement stale install rolls back new output and terminalizes") {
            val board = startPuzzleAtMain1(puzzle)
            val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
            val priorCounter = board.counter.snapshot()
            val competing =
                board.bridge
                    .projectionStateSnapshot()
                    .editor()
                    .freeze()
            board.bridge.cutCoordinator.beforeActionInstall = {
                board.bridge.replaceProjectionStateForTest(competing)
            }

            shouldThrow<PlaybackTerminalFailure> {
                board.bridge.cutCoordinator.replaceWithPhaseTransition(pending.actionId)
            }
            board.bridge.cutCoordinator.beforeActionInstall = null

            assertSoftly {
                board.bridge.projectionStateSnapshot() shouldBe competing
                board.counter.currentMsgId() shouldBeGreaterThan priorCounter.currentMsgId
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .count { it.hasActionsAvailableReq() } shouldBe 1
            }
        }

        test("post-install phase replacement failure retains committed output and ids") {
            val board = startPuzzleAtMain1(puzzle)
            val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
            val priorProjection = board.bridge.projectionStateSnapshot()
            val priorCounter = board.counter.snapshot()
            board.bridge.cutCoordinator.afterActionInstall = { error("acknowledgement unavailable") }

            shouldThrow<PlaybackTerminalFailure> {
                board.bridge.cutCoordinator.replaceWithPhaseTransition(pending.actionId)
            }
            board.bridge.cutCoordinator.afterActionInstall = null

            assertSoftly {
                board.bridge.cutCoordinator
                    .failure()
                    .shouldNotBeNull()
                board.bridge.projectionStateSnapshot().revision shouldBeGreaterThan priorProjection.revision
                board.counter.snapshot().currentMsgId shouldBeGreaterThan priorCounter.currentMsgId
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .count { it.hasActionsAvailableReq() } shouldBe 1
            }
        }

        test("teardown linearizes before action publication lock") {
            val board = startPuzzleAtMain1(puzzle)
            val bridge =
                GameActionBridge(
                    timeoutMs = 3_000,
                    windowRuntime = board.bridge.cutCoordinator.actionWindowRuntime(SeatId(1)),
                )

            shutdownWhilePublicationWaits(board.bridge.cutCoordinator) {
                bridge.awaitAction(PendingActionState("Main1", 1, 1, 1))
            }.shouldBeInstanceOf<PlaybackTerminalFailure>()
            bridge.getPending() shouldBe null
        }

        test("teardown linearizes before blocking interaction publication lock") {
            val board = startPuzzleAtMain1(puzzle)
            val sourceId =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
                    .id
            val interaction = BlockingInteraction.Optional(ForgeCardId(sourceId), false, null, null)

            shutdownWhilePublicationWaits(board.bridge.cutCoordinator) {
                board.bridge.cutCoordinator.awaitOptional(interaction, 3_000, false)
            }.shouldBeInstanceOf<PlaybackTerminalFailure>()
            board.bridge.cutCoordinator.currentBlockingInteraction() shouldBe null
        }

        test("teardown linearizes before playback request lock") {
            val board = startPuzzleAtMain1(puzzle)

            shutdownWhilePublicationWaits(board.bridge.cutCoordinator) {
                board.bridge.cutCoordinator.requestPlaybackCut(
                    SeatId(1),
                    PlaybackCutRequest(PlaybackCutReason.PhaseChanged, 0, false),
                )
            }.shouldBeInstanceOf<PlaybackTerminalFailure>()
            board.bridge.cutCoordinator.requestedPlaybackCut(SeatId(1)) shouldBe null
        }
    })
