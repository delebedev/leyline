package leyline.bridge.handoff

import forge.game.card.Card
import leyline.bridge.forge.PlayerController
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Owns the [PlayerController.pendingNumericInput] future lifecycle for the three
 * `chooseNumber` override sites that share it (range, range+params, list-overload).
 *
 * Mirrors [OptionalActionGate] — a `Cost$ X` triggered/activated ability lands in
 * Forge's `PlayerController.chooseNumber`, the override builds the prompt and blocks
 * the engine thread until `NumericInputHandler.onNumericInputResp` completes the future.
 *
 * Threading: [await] runs on the Forge engine thread. It blocks until the Netty
 * session thread completes the future via the response handler. On timeout, returns
 * [defaultOnTimeout] (`0` is the conventional default — payment of 0 is always safe).
 */
class NumericInputGate(
    private val owner: OwnerContext,
    private val actionBridge: GameActionBridge?,
) {
    private val log = LoggerFactory.getLogger(NumericInputGate::class.java)

    fun await(
        sourceCard: Card?,
        min: Int,
        max: Int,
        defaultOnTimeout: Int,
        logContext: String,
    ): Int {
        val future = CompletableFuture<Int>()
        owner.pendingNumericInput =
            PlayerController.NumericInputPrompt(
                sourceCard = sourceCard,
                min = min,
                max = max,
                future = future,
            )
        actionBridge?.prioritySignal?.signal()

        return try {
            val timeoutMs = actionBridge?.getTimeoutMs() ?: DEFAULT_TIMEOUT_MS
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            log.warn("{}: timeout/error for {} — defaulting to {}", logContext, sourceCard?.name, defaultOnTimeout, e)
            defaultOnTimeout
        } finally {
            owner.pendingNumericInput = null
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 45_000L
    }
}
