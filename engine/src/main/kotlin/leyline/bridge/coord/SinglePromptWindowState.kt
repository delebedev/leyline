package leyline.bridge.coord

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Shared single-window correlation, timeout arbitration, and waiter retirement. */
internal interface SinglePromptWindow<R> {
    val interactionId: String
    val gameStateId: Int
    val future: CompletableFuture<R>
}

internal class SinglePromptWindowState<W : SinglePromptWindow<R>, C, R>(
    private val owner: MatchCutCoordinator,
    private val cut: (W) -> C,
) {
    private var window: W? = null

    fun current(): W? = synchronized(owner.feedLock) { window?.takeUnless { it.future.isDone } }

    fun ensureEmptyLocked(message: String) {
        check(window == null) { message }
    }

    fun installLocked(created: W) {
        check(window == null) { "A prompt interaction is already pending" }
        window = created
    }

    fun matchingLocked(
        interactionId: String,
        gameStateId: Int,
    ): W? =
        window?.takeUnless { it.future.isDone }?.takeIf {
            it.interactionId == interactionId && it.gameStateId == gameStateId
        }

    fun completeLocked(
        pending: W,
        result: R,
    ): Boolean {
        if (window !== pending || pending.future.isDone) return false
        window = null
        return pending.future.complete(result)
    }

    fun pendingCutLocked(): C? = window?.takeUnless { it.future.isDone }?.let(cut)

    fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            window?.future?.completeExceptionally(cause)
            window = null
        }
    }

    fun reset() {
        synchronized(owner.feedLock) { window = null }
    }

    fun await(
        pending: W,
        timeoutMs: Long?,
        timeoutException: () -> Throwable,
        beforeTimeoutClaim: (() -> Unit)? = null,
        timeoutClaim: ((() -> Unit) -> Unit) = { claim -> synchronized(owner.feedLock) { claim() } },
        beforeTimeoutCompleteLocked: (() -> Unit)? = null,
    ): R =
        try {
            if (timeoutMs == null) pending.future.get() else pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            beforeTimeoutClaim?.invoke()
            timeoutClaim {
                if (window === pending && !pending.future.isDone) {
                    beforeTimeoutCompleteLocked?.invoke()
                    window = null
                    pending.future.completeExceptionally(timeoutException())
                }
            }
            completedValue(pending)
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun completedValue(pending: W): R =
        try {
            pending.future.get()
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }
}
