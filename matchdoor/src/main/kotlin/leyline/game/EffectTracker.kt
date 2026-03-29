package leyline.game

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

    // --- Keyword tracking ---

    data class KeywordEntry(val timestamp: Long, val staticId: Long, val keyword: String)

    data class KeywordFingerprint(val cardInstanceId: Int, val timestamp: Long, val staticId: Long)

    data class TrackedKeywordEffect(
        val syntheticId: Int,
        val fingerprint: KeywordFingerprint,
        val keyword: String,
    ) {
        val cardInstanceId: Int get() = fingerprint.cardInstanceId
    }

    data class KeywordDiffResult(
        val created: List<TrackedKeywordEffect>,
        val destroyed: List<TrackedKeywordEffect>,
    )

    /** Whether init effects have been emitted for this game. */
    private var initEmitted = false

    private var nextId = INITIAL_EFFECT_ID
    private val activeEffects = mutableMapOf<EffectFingerprint, TrackedEffect>()
    private val activeKeywordEffects = mutableMapOf<KeywordFingerprint, TrackedKeywordEffect>()

    /** Allocate the next monotonic synthetic effect ID. */
    fun nextEffectId(): Int = nextId++

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

    /**
     * Diff current keyword grants against previously tracked state.
     * Returns created/destroyed keyword effects for LayeredEffectCreated /
     * LayeredEffectDestroyed + AddAbility pAnn emission.
     */
    fun diffKeywords(currentKeywords: Map<Int, List<KeywordEntry>>): KeywordDiffResult {
        val currentFps = mutableMapOf<KeywordFingerprint, KeywordEntry>()
        for ((cardIid, entries) in currentKeywords) {
            for (entry in entries) {
                currentFps[KeywordFingerprint(cardIid, entry.timestamp, entry.staticId)] = entry
            }
        }

        val destroyed = mutableListOf<TrackedKeywordEffect>()
        val toRemove = mutableListOf<KeywordFingerprint>()
        for ((fp, tracked) in activeKeywordEffects) {
            if (fp !in currentFps) {
                destroyed.add(tracked)
                toRemove.add(fp)
            }
        }
        for (fp in toRemove) activeKeywordEffects.remove(fp)

        val created = mutableListOf<TrackedKeywordEffect>()
        for ((fp, entry) in currentFps) {
            if (fp !in activeKeywordEffects) {
                val tracked = TrackedKeywordEffect(nextEffectId(), fp, entry.keyword)
                activeKeywordEffects[fp] = tracked
                created.add(tracked)
            }
        }

        return KeywordDiffResult(created, destroyed)
    }

    /**
     * Emit the 3 game-initialization effects (7002-7004) that the real server
     * creates and immediately destroys at gsId=1.
     *
     * Purpose unclear — possibly game-rule initialization bookkeeping. The client
     * may use the starting counter value (7004) as a baseline. We replicate to
     * stay safe.
     *
     * Call once during the first Full GSM build.
     */
    fun emitInitEffects(): DiffResult {
        val effects = (0 until 3).map { i ->
            TrackedEffect(
                syntheticId = nextEffectId(),
                fingerprint = EffectFingerprint(cardInstanceId = 0, timestamp = 0L, staticId = i.toLong()),
                powerDelta = 0,
                toughnessDelta = 0,
            )
        }
        // Created and immediately destroyed — not tracked in activeEffects
        return DiffResult(created = effects, destroyed = effects)
    }

    /** Emit init effects if not yet emitted. Returns empty diff if already done. */
    fun emitInitEffectsOnce(): DiffResult {
        if (initEmitted) return DiffResult(emptyList(), emptyList())
        initEmitted = true
        return emitInitEffects()
    }

    /** Full reset — puzzle hot-swap. */
    fun resetAll() {
        nextId = INITIAL_EFFECT_ID
        activeEffects.clear()
        activeKeywordEffects.clear()
        initEmitted = false
    }
}
