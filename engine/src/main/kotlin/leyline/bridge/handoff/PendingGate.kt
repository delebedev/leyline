package leyline.bridge.handoff

import org.slf4j.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object PendingGate {
    const val DEFAULT_TIMEOUT_MS = 45_000L

    fun <T, P> await(
        publish: (P?) -> Unit,
        prompt: (CompletableFuture<T>) -> P,
        signal: () -> Unit,
        timeoutMs: () -> Long?,
        defaultOnTimeout: () -> T,
        log: Logger,
        logContext: String,
        subject: String?,
        timeoutDetail: String,
        onClear: () -> Unit = {},
    ): T {
        val future = CompletableFuture<T>()
        publish(prompt(future))
        signal()

        return try {
            future.get(timeoutMs() ?: DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            log.warn("{}: timeout/error for {} — {}", logContext, subject, timeoutDetail, e)
            defaultOnTimeout()
        } finally {
            publish(null)
            onClear()
        }
    }
}
