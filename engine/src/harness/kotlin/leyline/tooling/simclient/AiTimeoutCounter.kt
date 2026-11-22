package leyline.tooling.simclient

import forge.util.AITimeoutTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts Forge AI searches that ran past their time limit during one simclient
 * row, keyed by decision (attack, block, spell).
 *
 * The tracker's listener is process-global and simclient rows run one at a
 * time, so one counter is active at a time. [stop] clears the listener only
 * while this counter is still the active one: a row abandoned after a wall
 * timeout cannot clear the listener of the row that follows it. Counts stay
 * readable after [stop].
 */
class AiTimeoutCounter {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun start() {
        counts.clear()
        synchronized(lock) {
            active = this
            AITimeoutTracker.setExpiryListener { _, _, decision, _, _ ->
                counts.computeIfAbsent(decision.name.lowercase()) { AtomicInteger() }.incrementAndGet()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (active === this) {
                active = null
                AITimeoutTracker.setExpiryListener(null)
            }
        }
    }

    fun counts(): Map<String, Int> = counts.mapValues { it.value.get() }.toSortedMap()

    private companion object {
        val lock = Any()
        var active: AiTimeoutCounter? = null
    }
}
