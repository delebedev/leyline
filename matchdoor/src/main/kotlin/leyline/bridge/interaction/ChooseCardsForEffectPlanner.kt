package leyline.bridge.interaction

import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptSemantic

data class ChooseCardsForEffectContext(
    val sa: SpellAbility?,
    val activeReveal: Boolean,
)

data class ChooseCardsForEffectPlan(
    val semantic: PromptSemantic,
    val forcePrompt: Boolean = false,
    val includeCandidateRefs: Boolean = false,
    val includeSourceEntityId: Boolean = false,
    val autoResolveSingleMandatory: Boolean = true,
)

object ChooseCardsForEffectPlanner {
    fun plan(context: ChooseCardsForEffectContext): ChooseCardsForEffectPlan =
        when {
            context.activeReveal ->
                ChooseCardsForEffectPlan(
                    semantic = PromptSemantic.RevealChoose,
                    includeCandidateRefs = true,
                    includeSourceEntityId = true,
                )

            SpellAbilityShapes.isSuspectChoice(context.sa) ->
                ChooseCardsForEffectPlan(
                    semantic = PromptSemantic.SuspectChoice,
                    forcePrompt = true,
                    includeCandidateRefs = true,
                    includeSourceEntityId = true,
                    autoResolveSingleMandatory = false,
                )

            else -> ChooseCardsForEffectPlan(semantic = PromptSemantic.Generic)
        }
}
