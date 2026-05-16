package leyline.bridge.handoff

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.handoff.PlayerAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Deadlined poll for an engine-thread pending — sleep is poll interval, not a race. */
@Suppress("NoThreadSleepInTests")
private fun pollForPending(bridge: GameActionBridge): GameActionBridge.PendingAction? {
    repeat(50) {
        bridge.getPending()?.let { return it }
        Thread.sleep(10)
    }
    return null
}

class GameActionBridgeTest :
    FunSpec({

        tags(UnitTag)

        test("getPending returns null when future is already completed") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)

            // Simulate engine thread blocking on awaitAction
            val engineThread =
                Thread {
                    ready.countDown()
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()

            // Submit action — future completes, but engine thread hasn't cleared pending yet
            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)

            // getPending should filter out the completed future
            bridge.getPending().shouldBeNull()

            engineThread.join(2000)
        }

        test("getPending returns action when future is not completed") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)

            val engineThread =
                Thread {
                    ready.countDown()
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()

            // Future not yet completed — should be visible
            bridge.getPending().shouldNotBeNull()
            bridge.getPending()!!.state.phase shouldBe "Main1"

            // Clean up: complete so engine thread unblocks
            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
        }

        test("null timeout waits until explicit response") {
            val bridge = GameActionBridge(timeoutMs = null)
            val result = AtomicReference<PlayerAction?>()
            val ready = CountDownLatch(1)

            val engineThread =
                Thread {
                    ready.countDown()
                    result.set(
                        bridge.awaitAction(
                            PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                        ),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()
            result.get().shouldBeNull()

            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
            result.get() shouldBe PlayerAction.PassPriority
        }

        test("response gsId must match emitted prompt gsId") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)

            val engineThread =
                Thread {
                    ready.countDown()
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()
            assertSoftly {
                bridge.acceptsResponse(pending, 12) shouldBe false
                bridge.markPromptEmitted(pending.actionId, 12) shouldBe true
                bridge.acceptsResponse(pending, 11) shouldBe false
                bridge.acceptsResponse(pending, 12) shouldBe true
            }

            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
        }
    })
