package leyline.tooling.simclient

import forge.util.AITimeoutTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts Forge AI searches that ran past their time limit during one simclient
 * game, keyed by decision (attack, block, spell).
 *
 * The tracker's listener is process-global. Simclient rows run serially, so
 * holding it for the duration of one game attributes every expiry to that row.
 */
class AiTimeoutCounter {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun start() {
        counts.clear()
        AITimeoutTracker.setExpiryListener { _, _, decision, _, _ ->
            counts.computeIfAbsent(decision.name.lowercase()) { AtomicInteger() }.incrementAndGet()
        }
    }

    fun stopAndDrain(): Map<String, Int> {
        AITimeoutTracker.setExpiryListener(null)
        return counts.mapValues { it.value.get() }.toSortedMap()
    }
}
