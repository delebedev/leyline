package forge.nexus.game

/**
 * Tracks instanceIds retired to Limbo — a protocol-only zone with no Forge equivalent.
 *
 * When a card changes zones, the protocol assigns it a new instanceId and retires the old one
 * to Limbo. The real server never clears Limbo: it grows monotonically across the match.
 * Every GameStateMessage includes the full retirement history so the client can reconcile
 * disappeared objects.
 *
 * Not thread-safe by itself; callers synchronize externally if needed.
 */
class LimboTracker {
    private val retired = mutableListOf<Int>()

    /** Add an instanceId to the persistent Limbo set. Idempotent. */
    fun retire(instanceId: Int) {
        if (instanceId !in retired) retired.add(instanceId)
    }

    /** Current ordered list of all retired instanceIds. */
    fun all(): List<Int> = retired
}
