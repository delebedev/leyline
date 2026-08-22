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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Stands in for the coordinator-owned window record: it becomes the visibility
 * and correlation authority once [publish] returns, exactly as the real runtime
 * does. The bridge under test keeps no lifecycle state of its own.
 */
private class FakeActionWindowRuntime(
    private val boundPromptGameStateId: Int? = null,
    private val onPublish: (PendingAction) -> Unit = {},
    private val afterVisible: (PendingAction) -> Unit = {},
    private val onResolve: (PendingAction) -> PlayerAction = { error("unused") },
    private val onClaimTimeout: ((PendingAction, TimeoutException) -> Boolean)? = null,
) : GameActionBridge.ActionWindowRuntime {
    val closes = mutableListOf<WindowCloseReason>()

    @Volatile private var visibleActionId: String? = null

    override fun publish(pending: PendingAction) {
        onPublish(pending)
        visibleActionId = pending.actionId
        afterVisible(pending)
        if (pending.future.isDone) visibleActionId = null
    }

    override fun isVisible(actionId: String): Boolean = visibleActionId == actionId

    override fun promptGameStateId(actionId: String): Int? = boundPromptGameStateId

    override fun resolve(
        pending: PendingAction,
        submission: ActionSubmission.RuntimeToken,
    ): PlayerAction =
        if (submission.token == GameActionBridge.ENGINE_PASS_TOKEN) {
            PlayerAction.PassPriority
        } else {
            onResolve(pending)
        }

    override fun close(
        pending: PendingAction,
        reason: WindowCloseReason,
    ) {
        visibleActionId = null
        closes += reason
    }

    override fun claimTimeout(
        pending: PendingAction,
        cause: TimeoutException,
    ): Boolean = onClaimTimeout?.invoke(pending, cause) ?: super.claimTimeout(pending, cause)
}

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
                FakeActionWindowRuntime(
                    onPublish = {
                        entered.countDown()
                        check(release.await(2, TimeUnit.SECONDS))
                    },
                    onResolve = { PlayerAction.PassPriority },
                )
            val bridge = GameActionBridge(timeoutMs = 5_000, windowRuntime = runtime)
            val engine = Thread { bridge.awaitAction(state) }.also { it.start() }

            entered.await(2, TimeUnit.SECONDS) shouldBe true
            bridge.getPending().shouldBeNull()
            release.countDown()
            val pending = pollForPending(bridge)
            bridge.submitRuntimeToken(pending.actionId, 1) shouldBe true
            engine.join(2_000)
        }

        test("prompt correlation reads through to the window runtime") {
            val published = CountDownLatch(1)
            val runtime =
                FakeActionWindowRuntime(
                    boundPromptGameStateId = 12,
                    onPublish = { published.countDown() },
                    onResolve = { PlayerAction.PassPriority },
                )
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
                runtime.closes.shouldContainExactly(WindowCloseReason.Answered)
                bridge.getPending().shouldBeNull()
            }
        }

        test("publish failure escapes without signalling or leaving a pending window") {
            val runtime = FakeActionWindowRuntime(onPublish = { error("compile failed") })
            val bridge = GameActionBridge(timeoutMs = 5_000, windowRuntime = runtime)

            assertSoftly {
                shouldThrow<IllegalStateException> { bridge.awaitAction(state) }.message shouldBe "compile failed"
                bridge.getPending().shouldBeNull()
                runtime.closes.shouldContainExactly(WindowCloseReason.Failed)
            }
        }

        test("response completed during publication wins the action window") {
            lateinit var bridge: GameActionBridge
            val runtime =
                FakeActionWindowRuntime(
                    afterVisible = { pending -> bridge.submitRuntimeToken(pending.actionId, 1) shouldBe true },
                    onResolve = { PlayerAction.EndTurn },
                )
            bridge = GameActionBridge(timeoutMs = 5_000, windowRuntime = runtime)

            bridge.awaitAction(state) shouldBe PlayerAction.EndTurn
            assertSoftly {
                runtime.closes.shouldContainExactly(WindowCloseReason.Answered)
                bridge.getPending().shouldBeNull()
            }
        }

        test("runtime resolution failure escapes and clears exact window") {
            val published = CountDownLatch(1)
            val runtime =
                FakeActionWindowRuntime(
                    onPublish = { published.countDown() },
                    onResolve = { error("resolve failed") },
                )
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
                runtime.closes.shouldContainExactly(WindowCloseReason.Failed)
                bridge.getPending().shouldBeNull()
            }
        }

        test("cancelled wait escapes instead of degrading to pass") {
            val runtime = FakeActionWindowRuntime()
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
                runtime.closes.shouldContainExactly(WindowCloseReason.Failed)
                bridge.getPending().shouldBeNull()
            }
        }

        test("response completed before timeout claim wins the action window") {
            val timeoutEntered = CountDownLatch(1)
            val timeoutRelease = CountDownLatch(1)
            val runtime =
                FakeActionWindowRuntime(
                    onResolve = { PlayerAction.EndTurn },
                    onClaimTimeout = { pending, cause ->
                        timeoutEntered.countDown()
                        check(timeoutRelease.await(2, TimeUnit.SECONDS))
                        pending.future.completeExceptionally(cause)
                    },
                )
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
            val runtime = FakeActionWindowRuntime()
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
            val runtime =
                FakeActionWindowRuntime(
                    onClaimTimeout = { pending, cause ->
                        timeoutEntered.countDown()
                        check(timeoutRelease.await(2, TimeUnit.SECONDS))
                        pending.future.completeExceptionally(cause)
                    },
                )
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
                runtime.closes shouldContainExactly listOf(WindowCloseReason.Failed)
                bridge.getPending().shouldBeNull()
            }
        }

        test("blocking waits require a window runtime") {
            val bridge = GameActionBridge(timeoutMs = 5_000)
            shouldThrow<IllegalStateException> { bridge.awaitAction(state) }
            bridge.getPending().shouldBeNull()
        }

        test("a test runtime resolves an exact command without a bridge bypass") {
            val runtime = FakeActionWindowRuntime(onResolve = { PlayerAction.EndTurn })
            val bridge = GameActionBridge(timeoutMs = 5_000, windowRuntime = runtime)
            val result = AtomicReference<PlayerAction>()
            val engine = Thread { result.set(bridge.awaitAction(state)) }.also { it.start() }

            val pending = pollForPending(bridge)
            assertSoftly {
                pending.promptGameStateId.shouldBeNull()
                bridge.submitRuntimeToken(pending.actionId, 1) shouldBe true
            }
            engine.join(2_000)

            assertSoftly {
                result.get() shouldBe PlayerAction.EndTurn
                bridge.getPending().shouldBeNull()
            }
        }
    })
