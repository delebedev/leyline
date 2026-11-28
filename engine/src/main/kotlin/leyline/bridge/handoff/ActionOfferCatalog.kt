package leyline.bridge.handoff

import forge.game.spellability.SpellAbility

/** What an offer executes; two offers with equal actions and different identities cannot be told apart by the client. */
internal data class ActionExecutionIdentity(
    val command: PlayerAction,
    val stackAbilityGrpId: Int?,
    val forgeAbilityId: Int?,
    val spellGrpId: Int?,
)

internal fun GameActionBridge.ActionOffer.executionIdentity() =
    ActionExecutionIdentity(command, stackAbilityGrpId, forgeAbilityId, spellGrpId)

/** The Forge ability an offer executes, when its command names one. */
internal fun GameActionBridge.ActionOffer.forgeAbility(): SpellAbility? =
    when (val value = command) {
        is PlayerAction.CastSpell -> value.ability
        is PlayerAction.ActivateAbility -> value.ability
        is PlayerAction.ActivateMana -> value.ability
        else -> null
    }

/** An offer removed because the client cannot tell its action from the [kept] offer's. */
internal data class IndistinguishableOffer(
    val kept: GameActionBridge.ActionOffer,
    val dropped: GameActionBridge.ActionOffer,
)

/**
 * The catalog with every offer the client could not tell apart from an earlier one removed.
 *
 * Two offers are indistinguishable when their actions are equal but they execute different Forge abilities: the
 * client's response matches both. The first such offer stays, so a response executes the ability offered first.
 */
internal data class DisambiguatedActionCatalog(
    val offers: List<GameActionBridge.ActionOffer>,
    val dropped: List<IndistinguishableOffer>,
)

internal fun disambiguateActionCatalog(offers: List<GameActionBridge.ActionOffer>): DisambiguatedActionCatalog {
    val firstByAction = mutableMapOf<Any, GameActionBridge.ActionOffer>()
    val retained = mutableListOf<GameActionBridge.ActionOffer>()
    val dropped = mutableListOf<IndistinguishableOffer>()
    for (offer in offers) {
        val first = firstByAction.getOrPut(offer.action) { offer }
        if (first === offer || first.executionIdentity() == offer.executionIdentity()) {
            retained += offer
        } else {
            dropped += IndistinguishableOffer(first, offer)
        }
    }
    return DisambiguatedActionCatalog(if (dropped.isEmpty()) offers else retained, dropped)
}
