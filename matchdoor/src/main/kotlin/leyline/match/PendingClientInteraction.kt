package leyline.match

import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId

/**
 * Session-layer interaction awaiting a specific client response.
 *
 * Only one such interaction should be pending at a time for a seat.
 * Keeping it typed avoids dispatch based on multiple nullable fields.
 */
sealed interface PendingClientInteraction {
    data class ModalChoice(
        val promptId: String,
        val childGrpIds: List<Int>,
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
    ) : PendingClientInteraction

    data class OptionalCost(
        val pendingActionId: String,
        val action: PlayerAction.CastSpell,
        val costCtoIds: List<Int>,
    ) : PendingClientInteraction

    data class AlternateCostChoice(
        val pendingActionId: String,
        val cardId: ForgeCardId,
        val abilityIndicesByCtoId: Map<Int, Int>,
    ) : PendingClientInteraction

    data class Search(
        val promptId: String,
    ) : PendingClientInteraction
}
