package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-seat typed journal of prompt side-effects. Engine thread writes via
 * [record]; shell materialization reads versioned entries and accepted frame
 * commits consume or clear only those exact entries.
 * Storage is split by lifetime:
 *
 * - `drains` — [PromptSideEffect.LegendVictim] and other one-shot effects
 *   entries. Per-entry drain semantics: each record is one event, each consume
 *   removes at most one matching entry (first-match wins). Writers should not
 *   record duplicates for the same id within a single prompt resolution.
 * - `currentReveal` — ambient singleton: at most one reveal-choose is active
 *   at a time. [record] of [PromptSideEffect.RevealStarted] replaces the slot;
 *   [clearActiveReveal] compare-clears the exact version after completion.
 * - `currentStash` — ambient singleton: last-writer-wins for the
 *   [PromptSideEffect.OptionalCostStash] decision. [consumeOptionalCostStash]
 *   drains.
 * - `currentKeywordStash` — ambient singleton: last-writer-wins for the
 *   [PromptSideEffect.KeywordCostStash] decision (per-keyword pay/decline map
 *   recorded after the player answers the combined CTO modal). Read with [peekKeywordCostDecision]
 *   (peek-only — Forge may call `chooseNumberForKeywordCost` more than once
 *   during cost-prep retries). Cleared at the start of every
 *   `checkOptionalCosts` so a previous cast's stash never leaks into the
 *   next, and on [resetForPuzzle].
 * - `currentCollectEvidenceCost` — active payment context, recorded when Forge
 *   asks for the graveyard-card payment and cleared after the matching cast
 *   frame emits its helper annotation.
 * - `currentConvokePayments` — active cast-payment context keyed by source
 *   spell. It stays readable while the spell is on the stack so both the
 *   payment bracket and on-stack count annotation can see the same choices.
 *
 * [resetForPuzzle] is called during quiescent puzzle hot-swap; it is not
 * serialized against concurrent consumers.
 */
class PromptJournal {
    private data class DrainEntry(
        val version: Long,
        val effect: PromptSideEffect,
    )

    data class ChoiceResultEntry(
        val version: Long,
        val result: PromptSideEffect.ChoiceResult,
    )

    data class RevealEntry(
        val version: Long,
        val reveal: PromptSideEffect.RevealStarted,
    )

    data class ConvokePaymentsEntry(
        val version: Long,
        val sourceForgeCardId: ForgeCardId,
        val payments: List<PromptSideEffect.ConvokePayment>,
    )

    data class CollectEvidenceEntry(
        val version: Long,
        val context: PromptSideEffect.CollectEvidenceCost,
    )

    private val nextVersion = AtomicLong()
    private val drains = ConcurrentLinkedDeque<DrainEntry>()

    private val currentReveal = AtomicReference<RevealEntry?>()

    @Volatile
    private var currentStash: List<Int>? = null

    @Volatile
    private var currentKeywordStash: Map<String, Boolean>? = null

    @Volatile
    private var currentHybridManaStash: List<ManaColor>? = null

    @Volatile
    private var currentCollectEvidenceCost: CollectEvidenceEntry? = null

    @Volatile
    private var currentConvokePayments: Map<ForgeCardId, ConvokePaymentsEntry> = emptyMap()

    fun record(effect: PromptSideEffect) {
        val version = nextVersion.incrementAndGet()
        when (effect) {
            is PromptSideEffect.ExiledUnderSource,
            is PromptSideEffect.LegendVictim,
            is PromptSideEffect.EnlistTapAffector,
            is PromptSideEffect.OpeningHandAction,
            is PromptSideEffect.ChoiceResult,
            -> drains.add(DrainEntry(version, effect))
            is PromptSideEffect.RevealStarted ->
                currentReveal.set(RevealEntry(version, effect.copy(allHandCardIds = effect.allHandCardIds.toList())))
            is PromptSideEffect.OptionalCostStash -> currentStash = effect.indices
            is PromptSideEffect.KeywordCostStash -> currentKeywordStash = effect.decisionsByKeyword
            is PromptSideEffect.HybridManaStash -> currentHybridManaStash = effect.choices
            is PromptSideEffect.CollectEvidenceCost -> currentCollectEvidenceCost = CollectEvidenceEntry(version, effect)
            is PromptSideEffect.ConvokePayments -> {
                val payments = effect.payments.toList()
                currentConvokePayments =
                    currentConvokePayments +
                    (effect.sourceForgeCardId to ConvokePaymentsEntry(version, effect.sourceForgeCardId, payments))
            }
        }
    }

    /** Remove + return source iff an [PromptSideEffect.ExiledUnderSource] for [id] was present. */
    fun consumeExiledUnderSource(id: ForgeCardId): ForgeCardId? {
        val iter = drains.iterator()
        while (iter.hasNext()) {
            val effect = iter.next().effect
            if (effect is PromptSideEffect.ExiledUnderSource && effect.forgeCardId == id) {
                iter.remove()
                return effect.sourceForgeCardId
            }
        }
        return null
    }

    /** Remove + return `true` iff a [PromptSideEffect.LegendVictim] for [id] was present. */
    fun consumeLegendVictim(id: ForgeCardId): Boolean = drainFirstMatching { it is PromptSideEffect.LegendVictim && it.forgeCardId == id }

    fun consumeOpeningHandAction(id: ForgeCardId): PromptSideEffect.OpeningHandAction? {
        val iter = drains.iterator()
        while (iter.hasNext()) {
            val effect = iter.next().effect
            if (effect is PromptSideEffect.OpeningHandAction && effect.forgeCardId == id) {
                iter.remove()
                return effect
            }
        }
        return null
    }

    /** Remove + return attacker iff an Enlist cost tap for [id] was present. */
    fun consumeEnlistTapAffector(id: ForgeCardId): ForgeCardId? {
        val iter = drains.iterator()
        while (iter.hasNext()) {
            val effect = iter.next().effect
            if (effect is PromptSideEffect.EnlistTapAffector && effect.tappedForgeCardId == id) {
                iter.remove()
                return effect.attackerForgeCardId
            }
        }
        return null
    }

    /** Return the enlisted creature for an Enlist attacker without consuming the tap-affector entry. */
    fun peekEnlistedByAttacker(id: ForgeCardId): ForgeCardId? {
        for ((_, effect) in drains) {
            if (effect is PromptSideEffect.EnlistTapAffector && effect.attackerForgeCardId == id) {
                return effect.tappedForgeCardId
            }
        }
        return null
    }

    fun drainChoiceResults(): List<PromptSideEffect.ChoiceResult> {
        val out = mutableListOf<PromptSideEffect.ChoiceResult>()
        val iter = drains.iterator()
        while (iter.hasNext()) {
            val effect = iter.next().effect
            if (effect is PromptSideEffect.ChoiceResult) {
                iter.remove()
                out.add(effect)
            }
        }
        return out
    }

    private inline fun drainFirstMatching(predicate: (PromptSideEffect) -> Boolean): Boolean {
        val iter = drains.iterator()
        while (iter.hasNext()) {
            if (predicate(iter.next().effect)) {
                iter.remove()
                return true
            }
        }
        return false
    }

    /** Peek the active reveal (non-draining), or null. O(1). */
    fun activeReveal(): PromptSideEffect.RevealStarted? = currentReveal.get()?.reveal

    fun activeRevealEntry(): RevealEntry? = currentReveal.get()

    fun snapshotChoiceResults(): List<ChoiceResultEntry> =
        drains.mapNotNull { entry ->
            (entry.effect as? PromptSideEffect.ChoiceResult)?.let { ChoiceResultEntry(entry.version, it) }
        }

    fun consumeChoiceResults(entries: List<ChoiceResultEntry>) {
        val versions = entries.mapTo(mutableSetOf()) { it.version }
        if (versions.isNotEmpty()) drains.removeIf { it.version in versions && it.effect is PromptSideEffect.ChoiceResult }
    }

    fun clearActiveReveal(entry: RevealEntry) {
        currentReveal.compareAndSet(entry, null)
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

    fun consumeHybridManaStash(): List<ManaColor>? {
        val out = currentHybridManaStash
        currentHybridManaStash = null
        return out
    }

    fun clearHybridManaStash() {
        currentHybridManaStash = null
    }

    fun activeCollectEvidenceCost(): PromptSideEffect.CollectEvidenceCost? = currentCollectEvidenceCost?.context

    fun activeCollectEvidenceEntry(): CollectEvidenceEntry? = currentCollectEvidenceCost

    fun clearCollectEvidenceCost() {
        currentCollectEvidenceCost = null
    }

    fun clearCollectEvidenceCost(entry: CollectEvidenceEntry) {
        if (currentCollectEvidenceCost?.version == entry.version) currentCollectEvidenceCost = null
    }

    fun activeConvokePayments(sourceForgeCardId: ForgeCardId): List<PromptSideEffect.ConvokePayment> =
        currentConvokePayments[sourceForgeCardId]?.payments.orEmpty()

    fun activeConvokePayments(): Map<ForgeCardId, List<PromptSideEffect.ConvokePayment>> =
        currentConvokePayments.mapValues { (_, entry) -> entry.payments }

    fun activeConvokePaymentEntries(): List<ConvokePaymentsEntry> = currentConvokePayments.values.sortedBy { it.sourceForgeCardId.value }

    fun clearConvokePayments(sourceForgeCardId: ForgeCardId) {
        currentConvokePayments = currentConvokePayments - sourceForgeCardId
    }

    fun clearConvokePayments(entry: ConvokePaymentsEntry) {
        if (currentConvokePayments[entry.sourceForgeCardId]?.version == entry.version) {
            currentConvokePayments = currentConvokePayments - entry.sourceForgeCardId
        }
    }

    fun resetForPuzzle() {
        drains.clear()
        currentReveal.set(null)
        currentStash = null
        currentKeywordStash = null
        currentHybridManaStash = null
        currentCollectEvidenceCost = null
        currentConvokePayments = emptyMap()
    }
}
