package leyline.game.annotations

import leyline.game.mapping.ProjectionCardReferences

/** Single projection decision for the selective TriggeringObject annotation. */
internal object TriggeringObjectProjection {
    fun shouldEmit(
        abilityGrpId: Int,
        isActivatedAbility: Boolean,
        voidTrigger: Boolean,
        cardReferences: ProjectionCardReferences?,
    ): Boolean = !isActivatedAbility && !voidTrigger && cardReferences?.isCaseSolveTrigger(abilityGrpId) != true
}
