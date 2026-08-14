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

    data class TargetSelection(
        val promptId: String,
        val selectedIndices: List<Int>,
        /**
         * Accumulated client-facing instanceIds for the current targeting round.
         * Each SelectTargetsResp is a single tap (Select or Unselect); the session
         * accumulates here across taps until SubmitTargetsReq. Feeds the echo-back
         * re-prompt so the client sees both already-picked and still-legal candidates.
         */
        val selectedInstanceIds: List<Int> = emptyList(),
        /**
         * Forge entity id of the spell on the stack — read from
         * `prompt.request.sourceEntityId` at SelectTargetsResp time so PSuT
         * emission at SubmitTargetsReq time can resolve the same iid PST used,
         * even if the bridge prompt has cleared (timeout / shutdown race).
         * Zero when the prompt did not carry a source (defensive — should not
         * happen for a real targeting prompt).
         */
        val sourceEntityId: Int = 0,
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
