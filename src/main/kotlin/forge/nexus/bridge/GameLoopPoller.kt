package forge.nexus.bridge

/**
 * Reusable deadline-based polling for game loop state changes.
 *
 * Both the WebSocket handler and test fixtures need to block until the engine
 * posts a new pending action, game ends, or an interactive prompt arrives.
 * This consolidates the shared poll-sleep loop.
 */
object GameLoopPoller {

    /**
     * Block until [condition] returns true, polling at [pollIntervalMs].
     *
     * @throws AssertionError if [timeoutMs] elapses without the condition being met
     */
    @JvmStatic
    fun awaitCondition(
        timeoutMs: Long = 5_000,
        pollIntervalMs: Long = 10,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(pollIntervalMs)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }
}
