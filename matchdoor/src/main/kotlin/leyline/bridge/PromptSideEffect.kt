package leyline.bridge

/**
 * A typed side-effect produced while resolving an interactive prompt.
 *
 * Recorded by coordinators on the engine thread via [PromptJournal.record].
 * Consumed by GameEventCollector / StateMapper / CostPaymentCoordinator.
 * Each variant carries the minimum data the consumer needs.
 */
sealed interface PromptSideEffect {
    /** Card moved Library→Hand via a search effect (tutor). Consumer emits CardSearchedToHand. */
    data class SearchedToHand(val forgeCardId: ForgeCardId) : PromptSideEffect

    /** Legendary about to die to the legend rule SBA. Consumer emits LegendRuleDeath. */
    data class LegendVictim(val forgeCardId: ForgeCardId) : PromptSideEffect

    /** Reveal-choose effect in progress; consumers synthesize RevealedCard proxies. */
    data class RevealStarted(val allHandCardIds: List<ForgeCardId>, val ownerSeatId: SeatId) : PromptSideEffect

    /** Paired with RevealStarted — reveal-choose completed. */
    data object RevealEnded : PromptSideEffect

    /** Stashed optional cost decision (kicker, buyback, etc.). Indices into OptionalCostValue list. */
    data class OptionalCostStash(val indices: List<Int>) : PromptSideEffect
}
