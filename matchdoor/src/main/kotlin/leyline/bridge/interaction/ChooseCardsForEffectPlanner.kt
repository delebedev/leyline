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
    val candidateRefsPolicy: CandidateRefsPolicy = CandidateRefsPolicy.None,
    val sourceIdPolicy: SourceIdPolicy = SourceIdPolicy.None,
    val mandatoryChoicePolicy: MandatoryChoicePolicy = MandatoryChoicePolicy.AutoResolveWhenSatisfied,
)

object ChooseCardsForEffectPlanner {
    fun plan(context: ChooseCardsForEffectContext): ChooseCardsForEffectPlan =
        when {
            context.activeReveal ->
                ChooseCardsForEffectPlan(
                    semantic = PromptSemantic.RevealChoose,
                    candidateRefsPolicy = CandidateRefsPolicy.Selectable,
                    sourceIdPolicy = SourceIdPolicy.HostCard,
                )

            SpellAbilityShapes.isSuspectChoice(context.sa) ->
                ChooseCardsForEffectPlan(
                    semantic = PromptSemantic.SuspectChoice,
                    forcePrompt = true,
                    candidateRefsPolicy = CandidateRefsPolicy.Selectable,
                    sourceIdPolicy = SourceIdPolicy.HostCard,
                    mandatoryChoicePolicy = MandatoryChoicePolicy.PromptWhenSatisfied,
                )

            else -> ChooseCardsForEffectPlan(semantic = PromptSemantic.Generic)
        }
}
