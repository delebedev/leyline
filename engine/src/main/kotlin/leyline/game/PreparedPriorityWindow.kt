package leyline.game

import leyline.bridge.handoff.ActionToken
import leyline.bridge.types.ForgeCardId

/**
 * Immutable presentation and execution tokens for one blocked priority window.
 *
 * Exact engine commands remain in the action bridge's private token table.
 * Protocol identity is assigned later by the match owner.
 */
internal data class PreparedPriorityWindow(
    val actionId: String,
    val actions: PriorityActionSet,
    val offers: List<PreparedPriorityOffer>,
) {
    init {
        check(actions.actions == offers.map(PreparedPriorityOffer::value)) {
            "Prepared priority offers must match active actions in order"
        }
    }
}

/** Value-only executable metadata paired with one active priority action. */
internal data class PreparedPriorityOffer(
    val value: PriorityActionValue,
    val token: ActionToken,
    val cardId: ForgeCardId? = null,
    val abilityId: Int? = null,
    val stackAbilityGrpId: Int? = null,
    val forgeAbilityId: Int? = null,
    val spellGrpId: Int? = null,
)
