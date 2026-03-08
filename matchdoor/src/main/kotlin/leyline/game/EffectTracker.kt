package leyline.game

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks synthetic effect IDs and active P/T modifier state for the
 * LayeredEffect annotation lifecycle.
 *
 * Each continuous effect (Prowess buff, Giant Growth, lord anthem) gets a
 * synthetic ID in the 7000+ range. The client tracks effects by this ID
 * across GSMs and expects Created→Persistent→Destroyed lifecycle.
 *
 * Fingerprint: (cardInstanceId, timestamp, staticAbilityId) from Forge's
 * boost tables uniquely identifies an effect across GSMs.
 *
 * Not thread-safe — callers synchronize externally (MatchSession.sessionLock).
 */
class EffectTracker {

    companion object {
        /** Real server starts effect IDs at 7002 (7000-7001 possibly reserved). */
        const val INITIAL_EFFECT_ID = 7002
    }

    data class BoostEntry(val timestamp: Long, val staticId: Long, val power: Int, val toughness: Int)

    data class EffectFingerprint(val cardInstanceId: Int, val timestamp: Long, val staticId: Long)

    data class TrackedEffect(
        val syntheticId: Int,
        val fingerprint: EffectFingerprint,
        val powerDelta: Int,
        val toughnessDelta: Int,
    ) {
        val cardInstanceId: Int get() = fingerprint.cardInstanceId
    }

    data class DiffResult(val created: List<TrackedEffect>, val destroyed: List<TrackedEffect>)

    private val nextId = AtomicInteger(INITIAL_EFFECT_ID)
    private val activeEffects = mutableMapOf<EffectFingerprint, TrackedEffect>()

    /** Allocate the next monotonic synthetic effect ID. */
    fun nextEffectId(): Int = nextId.getAndIncrement()

    /**
     * Diff current boost table against previously tracked state.
     * Returns created/destroyed effects — callers use this to emit
     * LayeredEffectCreated / LayeredEffectDestroyed annotations.
     */
    fun diffBoosts(currentBoosts: Map<Int, List<BoostEntry>>): DiffResult {
        val currentFingerprints = mutableMapOf<EffectFingerprint, BoostEntry>()
        for ((cardIid, entries) in currentBoosts) {
            for (entry in entries) {
                currentFingerprints[EffectFingerprint(cardIid, entry.timestamp, entry.staticId)] = entry
            }
        }

        val destroyed = mutableListOf<TrackedEffect>()
        val toRemove = mutableListOf<EffectFingerprint>()
        for ((fp, tracked) in activeEffects) {
            if (fp !in currentFingerprints) {
                destroyed.add(tracked)
                toRemove.add(fp)
            }
        }
        for (fp in toRemove) activeEffects.remove(fp)

        val created = mutableListOf<TrackedEffect>()
        for ((fp, entry) in currentFingerprints) {
            if (fp !in activeEffects) {
                val tracked = TrackedEffect(nextEffectId(), fp, entry.power, entry.toughness)
                activeEffects[fp] = tracked
                created.add(tracked)
            }
        }

        return DiffResult(created, destroyed)
    }

    /** Full reset — puzzle hot-swap. */
    fun resetAll() {
        nextId.set(INITIAL_EFFECT_ID)
        activeEffects.clear()
    }
}
