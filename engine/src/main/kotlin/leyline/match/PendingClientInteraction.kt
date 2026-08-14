package leyline.match

import leyline.bridge.coord.MatchActionWindowRuntime
import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Session-layer interaction awaiting a specific client response.
 *
 * Only one such interaction should be pending at a time for a seat.
 * Keeping it typed avoids dispatch based on multiple nullable fields.
 */
internal sealed interface PendingClientInteraction {
    data class ModalChoice(
        val promptId: String,
        val childGrpIds: List<Int>,
        val stackAbilityInstanceId: Int? = null,
        val sourceForgeCardId: ForgeCardId? = null,
    ) : PendingClientInteraction

    /** Transitional response state for candidate-backed Generic prompts. */
    data class UnclassifiedCandidateSelection(
        val promptId: String,
        val selectedIndices: List<Int>,
        val selectedInstanceIds: List<Int>,
    ) : PendingClientInteraction

    data class OptionalCost(
        val actionClaim: MatchActionWindowRuntime.ActionClaim,
        val costCtoIds: List<Int>,
        /**
         * Subset of [costCtoIds] that correspond to keyword-cost keywords
         * (Offspring, Casualty, Conspire — see
         * `TargetingHandler.binaryKeywordCostNames`). Maps `ctoId → keyword
         * name` so the response handler can stash a per-keyword decision for
         * `CostPaymentCoordinator.chooseKeywordCostBinary` to consume later
         * when Forge calls `addKeywordCost` during cost prep.
         */
        val keywordCostsByCtoId: Map<Int, String> = emptyMap(),
    ) : PendingClientInteraction

    data class HybridManaType(
        val actionClaim: MatchActionWindowRuntime.ActionClaim,
        val ctoIds: List<Int>,
        val promptColors: List<ManaColor>,
        val paymentColors: List<ManaColor>,
    ) : PendingClientInteraction

    data class AlternateCostChoice(
        val actionClaim: MatchActionWindowRuntime.ActionClaim,
        val runtimeTokensByCtoId: Map<Int, Long>,
    ) : PendingClientInteraction

    data class Search(
        val promptId: String,
    ) : PendingClientInteraction
}
