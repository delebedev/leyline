package leyline.bridge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GameActionBridgeTest :
    FunSpec({

        tags(UnitTag)

        test("getPending returns null when future is already completed") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)

            // Simulate engine thread blocking on awaitAction
            val engineThread = Thread {
                ready.countDown()
                bridge.awaitAction(
                    PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                )
            }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            // Wait for pending to appear
            var pending: GameActionBridge.PendingAction? = null
            repeat(50) {
                pending = bridge.getPending()
                if (pending != null) return@repeat
                Thread.sleep(10)
            }
            pending.shouldNotBeNull()

            // Submit action — future completes, but engine thread hasn't cleared pending yet
            bridge.submitAction(pending!!.actionId, PlayerAction.PassPriority)

            // getPending should filter out the completed future
            bridge.getPending().shouldBeNull()

            engineThread.join(2000)
        }

        test("getPending returns action when future is not completed") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)

            val engineThread = Thread {
                ready.countDown()
                bridge.awaitAction(
                    PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                )
            }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            // Wait for pending to appear
            var pending: GameActionBridge.PendingAction? = null
            repeat(50) {
                pending = bridge.getPending()
                if (pending != null) return@repeat
                Thread.sleep(10)
            }
            pending.shouldNotBeNull()

            // Future not yet completed — should be visible
            bridge.getPending().shouldNotBeNull()
            bridge.getPending()!!.state.phase shouldBe "Main1"

            // Clean up: complete so engine thread unblocks
            bridge.submitAction(pending!!.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
        }
    })
