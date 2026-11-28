package leyline.match

import forge.game.GameStage
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.state.GameBridge
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CoordinatorDrainBudgetTest :
    BoardTest({
        test("a continuous synchronization stream terminalizes and releases both waiting threads") {
            val board = startWithBoard { _, _, _ -> }
            val bridge = board.bridge
            bridge.cutCoordinator.registerViewer(SeatId(1))
            bridge.priorityWaitMs = 50
            val engineFailure = AtomicReference<Throwable>()
            val receiverFailure = AtomicReference<Throwable>()
            val engineDone = CountDownLatch(1)
            val receiverDone = CountDownLatch(1)
            val sink = DrainBudgetSink()
            val engine =
                Thread {
                    runCatching {
                        while (true) bridge.actionBridge(SeatId(1)).awaitAction(syncState())
                    }.onFailure(engineFailure::set)
                    engineDone.countDown()
                }.also { it.start() }
            bridge.awaitPriorityWithTimeout(1_000) shouldBe true
            val receiver =
                Thread {
                    runCatching { drainCoordinatorBarrier(sink, bridge, SeatId(1), drainTimeoutMs = 50, drainStopLimit = 5) }
                        .onFailure(receiverFailure::set)
                    receiverDone.countDown()
                }.also { it.start() }
            try {
                assertSoftly {
                    receiverDone.await(500, TimeUnit.MILLISECONDS) shouldBe true
                    engineDone.await(500, TimeUnit.MILLISECONDS) shouldBe true
                    (receiverFailure.get() is PlaybackTerminalFailure) shouldBe true
                    engineFailure.get() shouldBe receiverFailure.get()
                    bridge.cutCoordinator.failure() shouldBe receiverFailure.get()
                    sink.gameStateIds.size shouldBeGreaterThanOrEqual 5
                    sink.gameStateIds.distinct().size shouldBe sink.gameStateIds.size
                }
            } finally {
                runCatching { bridge.cutCoordinator.failDelivery(IllegalStateException("Test teardown")) }
                engine.join(1_000)
                receiver.join(1_000)
            }
        }

        test("a progressing synchronization stream may exceed one horizon timeout") {
            val board = startWithBoard { _, _, _ -> }
            val bridge = board.bridge
            bridge.cutCoordinator.registerViewer(SeatId(1))
            bridge.priorityWaitMs = 200
            val engine =
                Thread {
                    runCatching {
                        repeat(4) {
                            bridge.actionBridge(SeatId(1)).awaitAction(syncState())
                            CountDownLatch(1).await(75, TimeUnit.MILLISECONDS)
                        }
                        bridge.actionBridge(SeatId(1)).awaitAction(syncState().copy(kind = PendingActionKind.PRIORITY))
                    }
                }.also { it.start() }
            try {
                bridge.awaitPriorityWithTimeout(1_000) shouldBe true
                val sink = DrainBudgetSink()
                val started = System.nanoTime()
                assertSoftly {
                    drainCoordinatorBarrier(sink, bridge, SeatId(1), drainTimeoutMs = 2_000).sent shouldBe true
                    System.nanoTime() - started shouldBeGreaterThanOrEqual TimeUnit.MILLISECONDS.toNanos(bridge.priorityWaitMs)
                    bridge
                        .actionBridge(SeatId(1))
                        .getPending()
                        ?.state
                        ?.kind shouldBe PendingActionKind.PRIORITY
                    bridge.cutCoordinator.failure() shouldBe null
                    sink.gameStateIds.shouldNotBeEmpty()
                }
            } finally {
                runCatching { bridge.cutCoordinator.failDelivery(IllegalStateException("Test teardown")) }
                engine.join(1_000)
            }
        }

        // In this test and the ceiling test, fake steps are scaled to seconds so every remaining
        // budget the drain waits on strictly exceeds priorityWaitMs: each real horizon wait stays
        // at priorityWaitMs whatever the fake clock reads.
        test("a slow synchronization chain below the stop limit continues past the total drain time") {
            val board = startWithBoard { _, _, _ -> }
            val bridge = board.bridge
            bridge.cutCoordinator.registerViewer(SeatId(1))
            bridge.priorityWaitMs = 10_000
            val clock = AtomicLong()
            val stepNanos = TimeUnit.SECONDS.toNanos(40)
            val engine =
                Thread {
                    runCatching {
                        repeat(8) {
                            bridge.actionBridge(SeatId(1)).awaitAction(syncState())
                            clock.addAndGet(stepNanos)
                        }
                        bridge.actionBridge(SeatId(1)).awaitAction(syncState().copy(kind = PendingActionKind.PRIORITY))
                    }
                }.also { it.start() }
            try {
                bridge.awaitPriorityWithTimeout(1_000) shouldBe true
                val sink = DrainBudgetSink()
                assertSoftly {
                    drainCoordinatorBarrier(
                        sink,
                        bridge,
                        SeatId(1),
                        drainTimeoutMs = 100_000,
                        drainStopLimit = 20,
                        drainCeilingMs = 1_000_000,
                        nowNanos = clock::get,
                    ).sent shouldBe true
                    clock.get() shouldBe 8 * stepNanos
                    bridge.cutCoordinator.failure() shouldBe null
                    bridge
                        .actionBridge(SeatId(1))
                        .getPending()
                        ?.state
                        ?.kind shouldBe PendingActionKind.PRIORITY
                }
            } finally {
                runCatching { bridge.cutCoordinator.failDelivery(IllegalStateException("Test teardown")) }
                engine.join(1_000)
            }
        }

        test("an endless synchronization stream below the stop limit terminalizes at the drain ceiling") {
            val board = startWithBoard { _, _, _ -> }
            val bridge = board.bridge
            bridge.cutCoordinator.registerViewer(SeatId(1))
            bridge.priorityWaitMs = 5_000
            val clock = AtomicLong()
            val engine =
                Thread {
                    runCatching {
                        while (true) {
                            bridge.actionBridge(SeatId(1)).awaitAction(syncState())
                            clock.addAndGet(TimeUnit.SECONDS.toNanos(30))
                        }
                    }
                }.also { it.start() }
            val receiverFailure = AtomicReference<Throwable>()
            val receiverDone = CountDownLatch(1)
            val sink = DrainBudgetSink()
            try {
                bridge.awaitPriorityWithTimeout(1_000) shouldBe true
                Thread {
                    runCatching {
                        drainCoordinatorBarrier(
                            sink,
                            bridge,
                            SeatId(1),
                            drainTimeoutMs = 50_000,
                            drainStopLimit = 1_000,
                            drainCeilingMs = 100_000,
                            nowNanos = clock::get,
                        )
                    }.onFailure(receiverFailure::set)
                    receiverDone.countDown()
                }.start()
                assertSoftly {
                    receiverDone.await(5_000, TimeUnit.MILLISECONDS) shouldBe true
                    (receiverFailure.get() is PlaybackTerminalFailure) shouldBe true
                    receiverFailure.get()?.cause?.message shouldBe "Synchronization drain exceeded its 100000 ms ceiling"
                    clock.get() shouldBe TimeUnit.SECONDS.toNanos(120)
                }
            } finally {
                runCatching { bridge.cutCoordinator.failDelivery(IllegalStateException("Test teardown")) }
                engine.join(1_000)
            }
        }

        test("a drain whose engine publishes nothing more returns when its priority wait expires") {
            val board = startWithBoard { _, _, _ -> }
            val bridge = board.bridge
            bridge.cutCoordinator.registerViewer(SeatId(1))
            bridge.priorityWaitMs = 50
            val released = CountDownLatch(1)
            val stuck = CountDownLatch(1)
            val engine =
                Thread {
                    runCatching {
                        bridge.actionBridge(SeatId(1)).awaitAction(syncState())
                        released.countDown()
                        stuck.await()
                    }
                }.also { it.start() }
            try {
                bridge.awaitPriorityWithTimeout(1_000) shouldBe true
                val sink = DrainBudgetSink()
                val clock = AtomicLong()
                assertSoftly {
                    drainCoordinatorBarrier(sink, bridge, SeatId(1), nowNanos = clock::get).synchronization shouldBe
                        SynchronizationDrain.Completed
                    released.await(1_000, TimeUnit.MILLISECONDS) shouldBe true
                    bridge.cutCoordinator.failure() shouldBe null
                    bridge.actionBridge(SeatId(1)).getPending() shouldBe null
                }
            } finally {
                stuck.countDown()
                runCatching { bridge.cutCoordinator.failDelivery(IllegalStateException("Test teardown")) }
                engine.join(1_000)
            }
        }

        test("a late horizon retains delivery after its ordinary wait expires") {
            val board = startWithBoard { _, _, _ -> }
            val bridge = board.bridge
            bridge.cutCoordinator.registerViewer(SeatId(1))
            bridge.priorityWaitMs = 25
            val released = CountDownLatch(1)
            val continueEngine = CountDownLatch(1)
            val engine =
                Thread {
                    runCatching {
                        bridge.actionBridge(SeatId(1)).awaitAction(syncState())
                        released.countDown()
                        continueEngine.await()
                        bridge.actionBridge(SeatId(1)).awaitAction(syncState().copy(kind = PendingActionKind.PRIORITY))
                    }
                }.also { it.start() }
            try {
                bridge.awaitPriorityWithTimeout(1_000) shouldBe true
                val sink = DrainBudgetSink()
                assertSoftly {
                    drainCoordinatorBarrier(sink, bridge, SeatId(1), drainTimeoutMs = 2_000).sent shouldBe true
                    released.await(500, TimeUnit.MILLISECONDS) shouldBe true
                    bridge.cutCoordinator.failure() shouldBe null
                    bridge.actionBridge(SeatId(1)).getPending() shouldBe null
                }
                continueEngine.countDown()
                bridge.awaitPriorityWithTimeout(1_000) shouldBe true
                val later = DrainBudgetSink()
                assertSoftly {
                    drainCoordinatorBarrier(later, bridge, SeatId(1), drainTimeoutMs = 2_000).sent shouldBe true
                    bridge.cutCoordinator.failure() shouldBe null
                    bridge
                        .actionBridge(SeatId(1))
                        .getPending()
                        ?.state
                        ?.kind shouldBe PendingActionKind.PRIORITY
                }
            } finally {
                continueEngine.countDown()
                runCatching { bridge.cutCoordinator.failDelivery(IllegalStateException("Test teardown")) }
                engine.join(1_000)
            }
        }

        test("a published priority window keeps its lifetime at an expired drain limit") {
            val board = startWithBoard { _, _, _ -> }
            val bridge = board.bridge
            bridge.cutCoordinator.registerViewer(SeatId(1))
            val engine =
                Thread {
                    runCatching { bridge.actionBridge(SeatId(1)).awaitAction(syncState().copy(kind = PendingActionKind.PRIORITY)) }
                }.also { it.start() }
            try {
                bridge.awaitPriorityWithTimeout(1_000) shouldBe true
                val sink = DrainBudgetSink()
                assertSoftly {
                    drainCoordinatorBarrier(sink, bridge, SeatId(1), drainTimeoutMs = 0, drainStopLimit = 0).sent shouldBe true
                    bridge.cutCoordinator.failure() shouldBe null
                    bridge
                        .actionBridge(SeatId(1))
                        .getPending()
                        ?.state
                        ?.kind shouldBe PendingActionKind.PRIORITY
                }
            } finally {
                runCatching { bridge.cutCoordinator.failDelivery(IllegalStateException("Test teardown")) }
                engine.join(1_000)
            }
        }

        test("a published numeric prompt keeps its lifetime at an expired drain limit") {
            val board = startWithBoard { _, human, _ -> addCard("Forest", human, ZoneType.Battlefield) }
            val bridge = board.bridge
            bridge.cutCoordinator.registerViewer(SeatId(1))
            val source =
                ForgeCardId(
                    board.human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .first()
                        .id,
                )
            val engine =
                Thread {
                    runCatching { bridge.cutCoordinator.awaitNumeric(BlockingInteraction.Numeric(source, 0, 2, 1), 2_000) }
                }.also { it.start() }
            try {
                bridge.awaitPriorityWithTimeout(1_000) shouldBe true
                val pending = checkNotNull(bridge.cutCoordinator.currentBlockingInteraction())
                val sink = DrainBudgetSink()
                assertSoftly {
                    drainCoordinatorBarrier(sink, bridge, SeatId(1), drainTimeoutMs = 0, drainStopLimit = 0).sent shouldBe true
                    bridge.cutCoordinator.failure() shouldBe null
                    bridge.cutCoordinator.currentBlockingInteraction() shouldBe pending
                    bridge.cutCoordinator.submitNumericAnswer(pending.interactionId, pending.gameStateId, 2) shouldBe true
                }
            } finally {
                runCatching { bridge.cutCoordinator.failDelivery(IllegalStateException("Test teardown")) }
                engine.join(1_000)
            }
        }

        test("a synchronization drain preserves priority published across its deadline") {
            val board = startWithBoard { _, _, _ -> }
            val bridge = board.bridge
            bridge.cutCoordinator.registerViewer(SeatId(1))
            val publishing = CountDownLatch(1)
            val engine =
                Thread {
                    runCatching {
                        bridge.actionBridge(SeatId(1)).awaitAction(syncState())
                        bridge.actionBridge(SeatId(1)).awaitAction(syncState().copy(kind = PendingActionKind.PRIORITY))
                    }
                }.also { it.start() }
            try {
                bridge.awaitPriorityWithTimeout(1_000) shouldBe true
                bridge.cutCoordinator.actions.beforePublished = {
                    publishing.countDown()
                    CountDownLatch(1).await(100, TimeUnit.MILLISECONDS)
                }
                val sink = DrainBudgetSink()
                assertSoftly {
                    drainCoordinatorBarrier(sink, bridge, SeatId(1), drainTimeoutMs = 50, drainStopLimit = 0).sent shouldBe true
                    publishing.count shouldBe 0L
                    bridge.cutCoordinator.failure() shouldBe null
                    bridge
                        .actionBridge(SeatId(1))
                        .getPending()
                        ?.state
                        ?.kind shouldBe PendingActionKind.PRIORITY
                }
            } finally {
                bridge.cutCoordinator.actions.beforePublished = null
                runCatching { bridge.cutCoordinator.failDelivery(IllegalStateException("Test teardown")) }
                engine.join(1_000)
            }
        }

        test("a committed terminal feed is delivered even after the drain limit") {
            val board = startWithBoard { _, _, _ -> }
            val bridge = board.bridge
            bridge.cutCoordinator.registerViewer(SeatId(1))
            bridge.cutCoordinator.publishConcession(SeatId(1))
            val sink = DrainBudgetSink()

            assertSoftly {
                drainCoordinatorBarrier(sink, bridge, SeatId(1), drainTimeoutMs = 0, drainStopLimit = 0).sent shouldBe true
                bridge.cutCoordinator.failure() shouldBe null
                bridge.cutCoordinator.hasCommittedBatches(SeatId(1)) shouldBe false
                sink.messages.any { it.hasGameStateMessage() && it.gameStateMessage.gameInfo.resultsCount > 0 } shouldBe true
            }
        }

        test("engine completion after synchronization returns before the terminal cut commits") {
            val board = startWithBoard { _, _, _ -> }
            val bridge = board.bridge
            bridge.cutCoordinator.registerViewer(SeatId(1))
            bridge.priorityWaitMs = 200
            val completed = CountDownLatch(1)
            val publishTerminal = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val engine =
                Thread {
                    runCatching {
                        bridge.actionBridge(SeatId(1)).awaitAction(syncState())
                        board.game.age = GameStage.GameOver
                        completed.countDown()
                        bridge.prioritySignal.signal()
                        publishTerminal.await()
                        bridge.cutCoordinator.publishConcession(SeatId(1))
                    }.onFailure(failure::set)
                }.also { it.start() }
            try {
                bridge.awaitPriorityWithTimeout(1_000) shouldBe true
                val sink = DrainBudgetSink()
                assertSoftly {
                    drainCoordinatorBarrier(sink, bridge, SeatId(1), drainTimeoutMs = 1_000).sent shouldBe true
                    completed.await(500, TimeUnit.MILLISECONDS) shouldBe true
                    bridge.cutCoordinator.committedGameOverOutcome() shouldBe null
                    bridge.cutCoordinator.failure() shouldBe null
                }
                publishTerminal.countDown()
                engine.join(1_000)
                failure.get() shouldBe null
                val terminalSink = DrainBudgetSink()
                drainCoordinatorBarrier(terminalSink, bridge, SeatId(1), drainTimeoutMs = 0, drainStopLimit = 0).sent shouldBe true
                terminalSink.messages.any { it.hasGameStateMessage() && it.gameStateMessage.gameInfo.resultsCount > 0 } shouldBe true
            } finally {
                publishTerminal.countDown()
                runCatching { bridge.cutCoordinator.failDelivery(IllegalStateException("Test teardown")) }
                engine.join(1_000)
            }
        }
    })

private fun syncState() =
    PendingActionState(
        phase = "CLEANUP",
        turn = 15,
        activePlayerId = 1,
        priorityPlayerId = 1,
        kind = PendingActionKind.SYNC_ONLY,
    )

private class DrainBudgetSink : GreMessageSink {
    val gameStateIds = mutableListOf<Int>()
    val messages = mutableListOf<GREToClientMessage>()

    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        this.messages += messages
        gameStateIds += messages.filter { it.hasGameStateMessage() }.map { it.gameStateId }
    }

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) = Unit

    override fun sendGameOver() = Unit
}
