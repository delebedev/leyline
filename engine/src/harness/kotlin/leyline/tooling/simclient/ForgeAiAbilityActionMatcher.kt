package leyline.tooling.simclient

import forge.game.spellability.SpellAbility
import leyline.game.state.AbilityRegistry
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

internal fun chooseActivatedAction(
    ability: SpellAbility,
    registry: AbilityRegistry,
    mappedInstanceId: Int,
    promptActions: List<Action>,
    skipFingerprints: Set<String>,
): Action? {
    val abilityGrpId = registry.forSpellAbility(ability) ?: return null
    return promptActions.firstOrNull {
        it.actionType == ActionType.Activate_add3 &&
            it.instanceId == mappedInstanceId &&
            it.abilityGrpId == abilityGrpId &&
            !it.isSkippedBy(skipFingerprints)
    }
}
