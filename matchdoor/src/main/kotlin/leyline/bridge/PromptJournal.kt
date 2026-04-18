package leyline.bridge

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Per-seat typed journal of prompt side-effects. Engine thread writes via
 * [record]; engine or annotation-build thread drains via `consume*` / peek.
 * Storage is split by lifetime:
 *
 * - `drains` — [PromptSideEffect.SearchedToHand] / [PromptSideEffect.LegendVictim]
 *   entries. Per-entry drain semantics: each record is one event, each consume
 *   removes at most one matching entry (first-match wins). Writers should not
 *   record duplicates for the same id within a single prompt resolution.
 * - `currentReveal` — ambient singleton: at most one reveal-choose is active
 *   at a time. [record] of [PromptSideEffect.RevealStarted] replaces the slot;
 *   [endActiveReveal] and [record] of [PromptSideEffect.RevealEnded] clear it.
 * - `currentStash` — ambient singleton: last-writer-wins for the
 *   [PromptSideEffect.OptionalCostStash] decision. [consumeOptionalCostStash]
 *   drains.
 *
 * [resetForPuzzle] is called during quiescent puzzle hot-swap; it is not
 * serialized against concurrent consumers.
 */
class PromptJournal {
    private val drains = ConcurrentLinkedDeque<PromptSideEffect>()

    @Volatile
    private var currentReveal: PromptSideEffect.RevealStarted? = null

    @Volatile
    private var currentStash: List<Int>? = null

    fun record(effect: PromptSideEffect) {
        when (effect) {
            is PromptSideEffect.SearchedToHand,
            is PromptSideEffect.LegendVictim,
            -> drains.add(effect)
            is PromptSideEffect.RevealStarted -> currentReveal = effect
            PromptSideEffect.RevealEnded -> currentReveal = null
            is PromptSideEffect.OptionalCostStash -> currentStash = effect.indices
        }
    }

    /** Remove + return `true` iff a [PromptSideEffect.SearchedToHand] for [id] was present. */
    fun consumeSearched(id: ForgeCardId): Boolean =
        drainFirstMatching { it is PromptSideEffect.SearchedToHand && it.forgeCardId == id }

    /** Remove + return `true` iff a [PromptSideEffect.LegendVictim] for [id] was present. */
    fun consumeLegendVictim(id: ForgeCardId): Boolean =
        drainFirstMatching { it is PromptSideEffect.LegendVictim && it.forgeCardId == id }

    private inline fun drainFirstMatching(predicate: (PromptSideEffect) -> Boolean): Boolean {
        val iter = drains.iterator()
        while (iter.hasNext()) {
            if (predicate(iter.next())) {
                iter.remove()
                return true
            }
        }
        return false
    }

    /** Peek the active reveal (non-draining), or null. O(1). */
    fun activeReveal(): PromptSideEffect.RevealStarted? = currentReveal

    /** Force-end any active reveal (stale-clear path). Idempotent. */
    fun endActiveReveal() {
        currentReveal = null
    }

    /** Consume the stashed optional cost indices, or null. */
    fun consumeOptionalCostStash(): List<Int>? {
        val out = currentStash
        currentStash = null
        return out
    }

    fun resetForPuzzle() {
        drains.clear()
        currentReveal = null
        currentStash = null
    }
}
