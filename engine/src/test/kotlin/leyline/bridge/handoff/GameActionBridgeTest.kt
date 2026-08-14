package leyline.bridge.handoff

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.GameActionBridge.ActionSubmission
import leyline.bridge.handoff.GameActionBridge.PendingAction
import leyline.bridge.handoff.GameActionBridge.WindowCloseReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun pollForPending(bridge: GameActionBridge): PendingAction {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (System.nanoTime() < deadline) {
        bridge.getPending()?.let { return it }
        Thread.onSpinWait()
    }
    return bridge.getPending().shouldNotBeNull()
}

class GameActionBridgeTest :
    FunSpec({
        tags(UnitTag)

        val state = PendingActionState("Main1", 1, 1, 1)

        test("pending window is hidden until runtime publication completes") {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val runtime =
                object : GameActionBridge.ActionWindowRuntime {
                    override fun publish(pending: PendingAction) {
                        entered.countDown()
                        check(release.await(2, TimeUnit.SECONDS))
                        pending.published = true
                    }

                    override fun resolve(
                        pending: PendingAction,
                        submission: ActionSubmission.RuntimeToken,
                    ): PlayerAction = PlayerAction.PassPriority

                    override fun close(
                        pending: PendingAction,
                        reason: WindowCloseReason,
                    ) = Unit
                }
            val bridge = GameActionBridge(timeoutMs = 5_000, windowRuntime = runtime)
            val engine = Thread { bridge.awaitAction(state) }.also { it.start() }

            entered.await(2, TimeUnit.SECONDS) shouldBe true
            bridge.getPending().shouldBeNull()
            release.countDown()
            val pending = pollForPending(bridge)
            bridge.submitRuntimeToken(pending.actionId, 1) shouldBe true
            engine.join(2_000)
        }

        test("publishes before exposing pending window and resolves runtime token on engine thread") {
            val published = CountDownLatch(1)
            val closes = mutableListOf<WindowCloseReason>()
            val runtime =
                object : GameActionBridge.ActionWindowRuntime {
                    override fun publish(pending: PendingAction) {
                        pending.promptGameStateId = 12
                        pending.published = true
                        published.countDown()
                    }

                    override fun resolve(
                        pending: PendingAction,
                        submission: ActionSubmission.RuntimeToken,
                    ): PlayerAction = PlayerAction.PassPriority

                    override fun close(
                        pending: PendingAction,
                        reason: WindowCloseReason,
                    ) {
                        closes += reason
                    }
                }
            val bridge = GameActionBridge(timeoutMs = 5_000, windowRuntime = runtime)
            val result = AtomicReference<PlayerAction?>()
            val engine = Thread { result.set(bridge.awaitAction(state)) }.also { it.start() }

            published.await(2, TimeUnit.SECONDS) shouldBe true
            val pending = pollForPending(bridge)
            pending.promptGameStateId shouldBe 12
            bridge.submitRuntimeToken(pending.actionId, 7) shouldBe true
            engine.join(2_000)

            assertSoftly {
                result.get() shouldBe PlayerAction.PassPriority
                closes.shouldContainExactly(WindowCloseReason.Answered)
                bridge.getPending().shouldBeNull()
            }
        }

        test("publish failure escapes without signalling or leaving a pending window") {
            val closed = AtomicReference<WindowCloseReason?>()
            val runtime =
                object : GameActionBridge.ActionWindowRuntime {
                    override fun publish(pending: PendingAction): Nothing = error("compile failed")

                    override fun resolve(
                        pending: PendingAction,
                        submission: ActionSubmission.RuntimeToken,
                    ): PlayerAction = error("unused")

                    override fun close(
                        pending: PendingAction,
                        reason: WindowCloseReason,
                    ) {
                        closed.set(reason)
                    }
                }
            val bridge = GameActionBridge(timeoutMs = 5_000, windowRuntime = runtime)

            assertSoftly {
                shouldThrow<IllegalStateException> { bridge.awaitAction(state) }.message shouldBe "compile failed"
                bridge.getPending().shouldBeNull()
                closed.get() shouldBe WindowCloseReason.Failed
            }
        }

        test("runtime resolution failure escapes and clears exact window") {
            val closes = mutableListOf<WindowCloseReason>()
            val published = CountDownLatch(1)
            val runtime =
                object : GameActionBridge.ActionWindowRuntime {
                    override fun publish(pending: PendingAction) {
                        pending.published = true
                        published.countDown()
                    }

                    override fun resolve(
                        pending: PendingAction,
                        submission: ActionSubmission.RuntimeToken,
                    ): PlayerAction = error("resolve failed")

                    override fun close(
                        pending: PendingAction,
                        reason: WindowCloseReason,
                    ) {
                        closes += reason
                    }
                }
            val bridge = GameActionBridge(timeoutMs = 5_000, windowRuntime = runtime)
            val failure = AtomicReference<Throwable?>()
            val engine =
                Thread {
                    try {
                        bridge.awaitAction(state)
                    } catch (ex: Throwable) {
                        failure.set(ex)
                    }
                }.also { it.start() }

            published.await(2, TimeUnit.SECONDS) shouldBe true
            val pending = pollForPending(bridge)
            bridge.submitRuntimeToken(pending.actionId, 9) shouldBe true
            engine.join(2_000)

            assertSoftly {
                failure.get()?.message shouldBe "resolve failed"
                closes.shouldContainExactly(WindowCloseReason.Failed)
                bridge.getPending().shouldBeNull()
            }
        }

        test("cancelled wait escapes instead of degrading to pass") {
            val closes = mutableListOf<WindowCloseReason>()
            val runtime =
                object : GameActionBridge.ActionWindowRuntime {
                    override fun publish(pending: PendingAction) {
                        pending.published = true
                    }

                    override fun resolve(
                        pending: PendingAction,
                        submission: ActionSubmission.RuntimeToken,
                    ): PlayerAction = error("unused")

                    override fun close(
                        pending: PendingAction,
                        reason: WindowCloseReason,
                    ) {
                        closes += reason
                    }
                }
            val bridge = GameActionBridge(timeoutMs = 5_000, windowRuntime = runtime)
            val failure = AtomicReference<Throwable?>()
            val engine =
                Thread {
                    try {
                        bridge.awaitAction(state)
                    } catch (ex: Throwable) {
                        failure.set(ex)
                    }
                }.also { it.start() }

            pollForPending(bridge)
            bridge.cancelPending()
            engine.join(2_000)

            assertSoftly {
                failure.get().shouldNotBeNull()
                closes.shouldContainExactly(WindowCloseReason.Failed)
                bridge.getPending().shouldBeNull()
            }
        }

        test("response completed before timeout claim wins the action window") {
            val timeoutEntered = CountDownLatch(1)
            val timeoutRelease = CountDownLatch(1)
            val runtime =
                object : GameActionBridge.ActionWindowRuntime {
                    override fun publish(pending: PendingAction) {
                        pending.published = true
                    }

                    override fun resolve(
                        pending: PendingAction,
                        submission: ActionSubmission.RuntimeToken,
                    ): PlayerAction = PlayerAction.EndTurn

                    override fun close(
                        pending: PendingAction,
                        reason: WindowCloseReason,
                    ) = Unit

                    override fun claimTimeout(
                        pending: PendingAction,
                        cause: java.util.concurrent.TimeoutException,
                    ): Boolean {
                        timeoutEntered.countDown()
                        check(timeoutRelease.await(2, TimeUnit.SECONDS))
                        return pending.future.completeExceptionally(cause)
                    }
                }
            val bridge = GameActionBridge(timeoutMs = 20, windowRuntime = runtime)
            val result = AtomicReference<PlayerAction>()
            val engine = Thread { result.set(bridge.awaitAction(state)) }.also { it.start() }
            val pending = pollForPending(bridge)
            check(timeoutEntered.await(2, TimeUnit.SECONDS))

            bridge.submitRuntimeToken(pending.actionId, 1) shouldBe true
            timeoutRelease.countDown()
            engine.join(2_000)

            result.get() shouldBe PlayerAction.EndTurn
        }

        test("timeout claim rejects a late action response") {
            val runtime =
                object : GameActionBridge.ActionWindowRuntime {
                    override fun publish(pending: PendingAction) {
                        pending.published = true
                    }

                    override fun resolve(
                        pending: PendingAction,
                        submission: ActionSubmission.RuntimeToken,
                    ): PlayerAction = error("unused")

                    override fun close(
                        pending: PendingAction,
                        reason: WindowCloseReason,
                    ) = Unit
                }
            val bridge = GameActionBridge(timeoutMs = 20, windowRuntime = runtime)
            val result = AtomicReference<PlayerAction>()
            val actionThread = Thread { result.set(bridge.awaitAction(state)) }.also { it.start() }
            val pending = pollForPending(bridge)
            actionThread.join(2_000)

            result.get() shouldBe PlayerAction.PassPriority
            bridge.submitRuntimeToken(pending.actionId, 1) shouldBe false
        }

        test("terminal failure wins against a paused timeout claim") {
            val timeoutEntered = CountDownLatch(1)
            val timeoutRelease = CountDownLatch(1)
            val closes = mutableListOf<WindowCloseReason>()
            val runtime =
                object : GameActionBridge.ActionWindowRuntime {
                    override fun publish(pending: PendingAction) {
                        pending.published = true
                    }

                    override fun resolve(
                        pending: PendingAction,
                        submission: ActionSubmission.RuntimeToken,
                    ): PlayerAction = error("unused")

                    override fun close(
                        pending: PendingAction,
                        reason: WindowCloseReason,
                    ) {
                        closes += reason
                    }

                    override fun claimTimeout(
                        pending: PendingAction,
                        cause: java.util.concurrent.TimeoutException,
                    ): Boolean {
                        timeoutEntered.countDown()
                        check(timeoutRelease.await(2, TimeUnit.SECONDS))
                        return pending.future.completeExceptionally(cause)
                    }
                }
            val bridge = GameActionBridge(timeoutMs = 20, windowRuntime = runtime)
            val terminal = IllegalStateException("terminal")
            val failure = AtomicReference<Throwable?>()
            val engine = Thread { runCatching { bridge.awaitAction(state) }.onFailure(failure::set) }.also { it.start() }
            pollForPending(bridge)
            check(timeoutEntered.await(2, TimeUnit.SECONDS))

            bridge.failPending(terminal)
            timeoutRelease.countDown()
            engine.join(2_000)

            assertSoftly {
                failure.get() shouldBe terminal
                closes shouldContainExactly listOf(WindowCloseReason.Failed)
                bridge.getPending().shouldBeNull()
            }
        }
    })
