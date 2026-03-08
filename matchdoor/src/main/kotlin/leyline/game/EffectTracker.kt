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

    private val nextId = AtomicInteger(INITIAL_EFFECT_ID)

    /** Allocate the next monotonic synthetic effect ID. */
    fun nextEffectId(): Int = nextId.getAndIncrement()

    /** Full reset — puzzle hot-swap. */
    fun resetAll() {
        nextId.set(INITIAL_EFFECT_ID)
    }
}
