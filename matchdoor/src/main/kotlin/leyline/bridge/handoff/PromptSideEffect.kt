package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

/**
 * A typed side-effect produced while resolving an interactive prompt.
 *
 * Recorded by coordinators on the engine thread via [PromptJournal.record].
 * Consumed by GameEventCollector / StateMapper / CostPaymentCoordinator.
 * Each variant carries the minimum data the consumer needs.
 */
sealed interface PromptSideEffect {
    /** Card moved Library→Hand via a search effect (tutor). Consumer emits CardSearchedToHand. */
    data class SearchedToHand(
        val forgeCardId: ForgeCardId,
    ) : PromptSideEffect

    /** Card chosen for a source-linked exile-under-source effect. */
    data class ExiledUnderSource(
        val forgeCardId: ForgeCardId,
        val sourceForgeCardId: ForgeCardId,
    ) : PromptSideEffect

    /** Legendary about to die to the legend rule SBA. Consumer emits LegendRuleDeath. */
    data class LegendVictim(
        val forgeCardId: ForgeCardId,
    ) : PromptSideEffect

    /** Enlist cost tap; consumer emits tap annotation with the attacker as affector. */
    data class EnlistTapAffector(
        val tappedForgeCardId: ForgeCardId,
        val attackerForgeCardId: ForgeCardId,
    ) : PromptSideEffect

    /** Reveal-choose effect in progress; consumers synthesize RevealedCard proxies. */
    data class RevealStarted(
        val allHandCardIds: List<ForgeCardId>,
        val ownerSeatId: SeatId,
    ) : PromptSideEffect

    /** Paired with RevealStarted — reveal-choose completed. */
    data object RevealEnded : PromptSideEffect

    /** Stashed optional cost decision (kicker, buyback, etc.). Indices into OptionalCostValue list. */
    data class OptionalCostStash(
        val indices: List<Int>,
    ) : PromptSideEffect

    /**
     * Stashed keyword-cost decisions (Offspring, Replicate, etc.) keyed by
     * canonical keyword name (`KeywordInterface.getKeyword().toString()`).
     * `true` = pay it, `false` = decline. Consumed by
     * `CostPaymentCoordinator.chooseKeywordCostBinary` when Forge calls
     * `addKeywordCost` post-action-submit.
     */
    data class KeywordCostStash(
        val decisionsByKeyword: Map<String, Boolean>,
    ) : PromptSideEffect

    /** Active Collect Evidence cost-payment context for AbilityWordActive emission. */
    data class CollectEvidenceCost(
        val sourceForgeCardId: ForgeCardId,
        val threshold: Int,
    ) : PromptSideEffect

    /** Cards tapped for Convoke while paying a spell cost. */
    data class ConvokePayments(
        val sourceForgeCardId: ForgeCardId,
        val payments: List<ConvokePayment>,
    ) : PromptSideEffect

    data class ConvokePayment(
        val paymentForgeCardId: ForgeCardId,
        /** Client ManaColor enum number used by the ManaPaid annotation. */
        val color: Int,
    )

    /** Completed SelectN choice. Consumer emits ChoiceResult transient annotation. */
    data class ChoiceResult(
        val sourceForgeCardId: ForgeCardId,
        val chooserSeatId: SeatId,
        val choiceValue: Int,
        val choiceDomain: Int? = null,
        val sentiment: Int = 2,
    ) : PromptSideEffect
}
