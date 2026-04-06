package leyline.match

import leyline.bridge.PlayerAction

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
    ) : PendingClientInteraction

    data class OptionalCost(
        val pendingActionId: String,
        val action: PlayerAction.CastSpell,
        val costCtoIds: List<Int>,
    ) : PendingClientInteraction

    data class Search(
        val promptId: String,
    ) : PendingClientInteraction
}
