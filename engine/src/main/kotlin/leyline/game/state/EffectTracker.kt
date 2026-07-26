package leyline.game.state

import kotlin.collections.iterator

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
 * Not thread-safe — interactive callers run on the match owner.
 */
class EffectTracker {
    companion object {
        /** Protocol starts effect IDs at 7002 (7000-7001 possibly reserved). */
        const val INITIAL_EFFECT_ID = 7002
    }

    data class BoostEntry(
        val timestamp: Long,
        val staticId: Long,
        val power: Int,
        val toughness: Int,
    )

    data class EffectFingerprint(
        val cardInstanceId: Int,
        val timestamp: Long,
        val staticId: Long,
    )

    data class TrackedEffect(
        val syntheticId: Int,
        val fingerprint: EffectFingerprint,
        val powerDelta: Int,
        val toughnessDelta: Int,
    ) {
        val cardInstanceId: Int get() = fingerprint.cardInstanceId
    }

    data class DiffResult(
        val created: List<TrackedEffect>,
        val destroyed: List<TrackedEffect>,
    )

    // --- Keyword tracking ---

    data class KeywordEntry(
        val timestamp: Long,
        val staticId: Long,
        val keyword: String,
    )

    data class KeywordFingerprint(
        val cardInstanceId: Int,
        val timestamp: Long,
        val staticId: Long,
        val keyword: String,
    )

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

    private data class LifecycleDiff<T>(
        val created: List<T>,
        val destroyed: List<T>,
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
        val diff =
            diffLifecycle(
                currentByCard = currentBoosts,
                active = activeEffects,
                fingerprintOf = { cardIid, entry -> EffectFingerprint(cardIid, entry.timestamp, entry.staticId) },
                createTracked = { fp, entry -> TrackedEffect(nextEffectId(), fp, entry.power, entry.toughness) },
            )
        return DiffResult(diff.created, diff.destroyed)
    }

    /**
     * Diff current keyword grants against previously tracked state.
     * Returns created/destroyed keyword effects for LayeredEffectCreated /
     * LayeredEffectDestroyed + AddAbility pAnn emission.
     */
    fun diffKeywords(currentKeywords: Map<Int, List<KeywordEntry>>): KeywordDiffResult {
        val diff =
            diffLifecycle(
                currentByCard = currentKeywords,
                active = activeKeywordEffects,
                fingerprintOf = { cardIid, entry ->
                    KeywordFingerprint(cardIid, entry.timestamp, entry.staticId, entry.keyword)
                },
                createTracked = { fp, entry -> TrackedKeywordEffect(nextEffectId(), fp, entry.keyword) },
            )
        return KeywordDiffResult(diff.created, diff.destroyed)
    }

    private fun <Entry, Fingerprint, Tracked> diffLifecycle(
        currentByCard: Map<Int, List<Entry>>,
        active: MutableMap<Fingerprint, Tracked>,
        fingerprintOf: (cardInstanceId: Int, entry: Entry) -> Fingerprint,
        createTracked: (Fingerprint, Entry) -> Tracked,
    ): LifecycleDiff<Tracked> {
        val current = mutableMapOf<Fingerprint, Entry>()
        for ((cardIid, entries) in currentByCard) {
            for (entry in entries) {
                current[fingerprintOf(cardIid, entry)] = entry
            }
        }

        val destroyed = mutableListOf<Tracked>()
        val toRemove = mutableListOf<Fingerprint>()
        for ((fp, tracked) in active) {
            if (fp !in current) {
                destroyed.add(tracked)
                toRemove.add(fp)
            }
        }
        for (fp in toRemove) active.remove(fp)

        val created = mutableListOf<Tracked>()
        for ((fp, entry) in current) {
            if (fp !in active) {
                val tracked = createTracked(fp, entry)
                active[fp] = tracked
                created.add(tracked)
            }
        }

        return LifecycleDiff(created, destroyed)
    }

    /**
     * Emit the 3 game-initialization effects (7002-7004) expected by the client
     * — created and immediately destroyed at gsId=1.
     *
     * Purpose unclear — possibly game-rule initialization bookkeeping. The client
     * may use the starting counter value (7004) as a baseline. We replicate to
     * stay safe.
     *
     * Call once during the first Full GSM build.
     */
    private fun emitInitEffects(): DiffResult {
        val effects =
            (0 until 3).map { i ->
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
