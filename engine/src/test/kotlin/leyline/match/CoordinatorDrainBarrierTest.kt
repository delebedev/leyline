package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.handoff.SynchronizationContinuation
import leyline.game.bundle.BundleBuilder
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CoordinatorDrainBarrierTest :
    FunSpec({
        tags(UnitTag)

        test("two caller invocations release S1 then S2 without a hidden repeat loop") {
            val sink = BarrierSink()
            val committed = mutableListOf(batch(1))
            val completed = mutableListOf<String>()
            var awaited = 0

            fun drain(): List<List<GREToClientMessage>> = committed.toList().also { committed.clear() }

            fun awaitNext() {
                awaited++
                committed += batch(awaited + 1)
            }

            val first =
                drainOneCoordinatorBarrier(
                    sink,
                    synchronizationActionId = "S1",
                    drainCommitted = ::drain,
                    completeSynchronization = {
                        completed += it
                        true
                    },
                    awaitNext = ::awaitNext,
                    failDelivery = { throw it },
                )
            val second =
                drainOneCoordinatorBarrier(
                    sink,
                    synchronizationActionId = "S2",
                    drainCommitted = ::drain,
                    completeSynchronization = {
                        completed += it
                        true
                    },
                    awaitNext = ::awaitNext,
                    failDelivery = { throw it },
                )

            assertSoftly {
                first shouldBe DrainOutcome(true, SynchronizationDrain.Completed, "S1")
                second shouldBe DrainOutcome(true, SynchronizationDrain.Completed, "S2")
                completed shouldBe listOf("S1", "S2")
                awaited shouldBe 2
                sink.gameStateIds shouldBe listOf(1, 2, 3)
            }
        }

        test("stale exact action id delivers prior feed but does not await or release another stop") {
            val sink = BarrierSink()
            val committed = mutableListOf(batch(7))
            var awaited = false

            val outcome =
                drainOneCoordinatorBarrier(
                    sink,
                    synchronizationActionId = "stale",
                    drainCommitted = { committed.toList().also { committed.clear() } },
                    completeSynchronization = { false },
                    awaitNext = { awaited = true },
                    failDelivery = { throw it },
                )

            assertSoftly {
                outcome shouldBe DrainOutcome(true, SynchronizationDrain.Stale, "stale")
                awaited shouldBe false
                sink.gameStateIds shouldBe listOf(7)
            }
        }

        test("delivery failure aborts before synchronization completion") {
            val deliveryFailure = IllegalStateException("delivery failed")
            val terminalFailure = IllegalStateException("terminal", deliveryFailure)
            val bridge = GameActionBridge(timeoutMs = null)
            val engine =
                Thread {
                    runCatching {
                        bridge.awaitAction(
                            PendingActionState(
                                phase = "Main1",
                                turn = 1,
                                activePlayerId = 1,
                                priorityPlayerId = 1,
                                kind = PendingActionKind.SYNC_ONLY,
                                synchronizationContinuation = SynchronizationContinuation.RequireVisible,
                            ),
                        )
                    }
                }.also { it.start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var pending = bridge.getPending()
            while (pending == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                pending = bridge.getPending()
            }
            val exact = checkNotNull(pending)
            var awaited = false
            var failedWith: Exception? = null

            val thrown =
                shouldThrow<IllegalStateException> {
                    drainOneCoordinatorBarrier(
                        sink = BarrierSink(deliveryFailure),
                        synchronizationActionId = exact.actionId,
                        drainCommitted = { listOf(batch(1)) },
                        completeSynchronization = bridge::completeSyncPass,
                        awaitNext = { awaited = true },
                        failDelivery = {
                            failedWith = it
                            throw terminalFailure
                        },
                    )
                }

            assertSoftly {
                thrown shouldBe terminalFailure
                failedWith shouldBe deliveryFailure
                awaited shouldBe false
                bridge.exactPending(exact.actionId) shouldBe exact
                bridge.consumeSynchronizationContinuation() shouldBe SynchronizationContinuation.Reevaluate
            }
            bridge.cancelPending()
            engine.join(2_000)
        }

        test("engine arms the frozen continuation only after S1 returns successfully") {
            val bridge = GameActionBridge(timeoutMs = null)
            val result = AtomicReference<PlayerAction?>()
            val returned = java.util.concurrent.CountDownLatch(1)
            val allowArm = java.util.concurrent.CountDownLatch(1)
            val engine =
                Thread {
                    val action =
                        bridge.awaitAction(
                            PendingActionState(
                                phase = "Main1",
                                turn = 1,
                                activePlayerId = 1,
                                priorityPlayerId = 1,
                                kind = PendingActionKind.SYNC_ONLY,
                                synchronizationContinuation = SynchronizationContinuation.RequireVisible,
                            ),
                        )
                    result.set(action)
                    returned.countDown()
                    allowArm.await()
                    bridge.armSynchronizationContinuation(SynchronizationContinuation.RequireVisible)
                }.also { it.start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var pending = bridge.getPending()
            while (pending == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                pending = bridge.getPending()
            }
            val exact = checkNotNull(pending)

            assertSoftly {
                bridge.completeSyncPass("wrong") shouldBe false
                bridge.consumeSynchronizationContinuation() shouldBe SynchronizationContinuation.Reevaluate
                bridge.completeSyncPass(exact.actionId) shouldBe true
            }
            check(returned.await(2, TimeUnit.SECONDS)) { "Engine wait did not return" }
            check(bridge.consumeSynchronizationContinuation() == SynchronizationContinuation.Reevaluate) {
                "Session completion armed the continuation before the engine resumed"
            }
            allowArm.countDown()
            engine.join(2_000)

            assertSoftly {
                result.get() shouldBe PlayerAction.PassPriority
                bridge.consumeSynchronizationContinuation() shouldBe SynchronizationContinuation.RequireVisible
                bridge.consumeSynchronizationContinuation() shouldBe SynchronizationContinuation.Reevaluate
            }
        }
    })

private class BarrierSink(
    private val failure: Exception? = null,
) : GreMessageSink {
    val gameStateIds = mutableListOf<Int>()

    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        failure?.let { throw it }
        gameStateIds += messages.map { it.gameStateId }
    }

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) = Unit

    override fun sendBundle(result: BundleBuilder.BundleResult) = Unit

    override fun sendGameOver(reason: ResultReason) = Unit

    override fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage = GREToClientMessage.getDefaultInstance()
}

private fun batch(gameStateId: Int): List<GREToClientMessage> =
    listOf(
        GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateId(gameStateId)
            .build(),
    )
