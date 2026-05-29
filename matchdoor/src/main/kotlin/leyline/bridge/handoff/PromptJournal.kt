package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
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
 * - `currentKeywordStash` — ambient singleton: last-writer-wins for the
 *   [PromptSideEffect.KeywordCostStash] decision (per-keyword pay/decline map
 *   recorded by `TargetingHandler.onOptionalCostResponse` after the player
 *   answers the combined CTO modal). Read with [peekKeywordCostDecision]
 *   (peek-only — Forge may call `chooseNumberForKeywordCost` more than once
 *   during cost-prep retries). Cleared at the start of every
 *   `checkOptionalCosts` so a previous cast's stash never leaks into the
 *   next, and on [resetForPuzzle].
 * - `currentCollectEvidenceCost` — active payment context, recorded when Forge
 *   asks for the graveyard-card payment and cleared after the matching cast
 *   frame emits its helper annotation.
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

    @Volatile
    private var currentKeywordStash: Map<String, Boolean>? = null

    @Volatile
    private var currentCollectEvidenceCost: PromptSideEffect.CollectEvidenceCost? = null

    fun record(effect: PromptSideEffect) {
        when (effect) {
            is PromptSideEffect.SearchedToHand,
            is PromptSideEffect.LegendVictim,
            is PromptSideEffect.EnlistTapAffector,
            -> drains.add(effect)
            is PromptSideEffect.RevealStarted -> currentReveal = effect
            PromptSideEffect.RevealEnded -> currentReveal = null
            is PromptSideEffect.OptionalCostStash -> currentStash = effect.indices
            is PromptSideEffect.KeywordCostStash -> currentKeywordStash = effect.decisionsByKeyword
            is PromptSideEffect.CollectEvidenceCost -> currentCollectEvidenceCost = effect
        }
    }

    /** Remove + return `true` iff a [PromptSideEffect.SearchedToHand] for [id] was present. */
    fun consumeSearched(id: ForgeCardId): Boolean = drainFirstMatching { it is PromptSideEffect.SearchedToHand && it.forgeCardId == id }

    /** Remove + return `true` iff a [PromptSideEffect.LegendVictim] for [id] was present. */
    fun consumeLegendVictim(id: ForgeCardId): Boolean = drainFirstMatching { it is PromptSideEffect.LegendVictim && it.forgeCardId == id }

    /** Remove + return attacker iff an Enlist cost tap for [id] was present. */
    fun consumeEnlistTapAffector(id: ForgeCardId): ForgeCardId? {
        val iter = drains.iterator()
        while (iter.hasNext()) {
            val effect = iter.next()
            if (effect is PromptSideEffect.EnlistTapAffector && effect.tappedForgeCardId == id) {
                iter.remove()
                return effect.attackerForgeCardId
            }
        }
        return null
    }

    /** Return the enlisted creature for an Enlist attacker without consuming the tap-affector entry. */
    fun peekEnlistedByAttacker(id: ForgeCardId): ForgeCardId? {
        for (effect in drains) {
            if (effect is PromptSideEffect.EnlistTapAffector && effect.attackerForgeCardId == id) {
                return effect.tappedForgeCardId
            }
        }
        return null
    }

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

    /**
     * Peek the keyword-cost decision for [keywordName] without removing it.
     * Returns null if no decision is stashed (no CTO was emitted for this
     * keyword, or it was already consumed).
     */
    fun peekKeywordCostDecision(keywordName: String): Boolean? = currentKeywordStash?.get(keywordName)

    /** Drop the entire keyword-cost stash (e.g. on cancel / reset). */
    fun clearKeywordCostStash() {
        currentKeywordStash = null
    }

    fun activeCollectEvidenceCost(): PromptSideEffect.CollectEvidenceCost? = currentCollectEvidenceCost

    fun clearCollectEvidenceCost() {
        currentCollectEvidenceCost = null
    }

    fun resetForPuzzle() {
        drains.clear()
        currentReveal = null
        currentStash = null
        currentKeywordStash = null
        currentCollectEvidenceCost = null
    }
}
