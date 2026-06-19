package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptSemantic

data class ChooseCardsForEffectContext(
    val sa: SpellAbility?,
    val hiddenLibrarySelection: Boolean,
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

            context.sa?.api == ApiType.ChangeZone && context.hiddenLibrarySelection ->
                ChooseCardsForEffectPlan(
                    semantic = PromptSemantic.Search,
                    candidateRefsPolicy = CandidateRefsPolicy.Selectable,
                    sourceIdPolicy = SourceIdPolicy.HostCard,
                )

            context.sa?.api == ApiType.ChangeZone ->
                ChooseCardsForEffectPlan(
                    semantic = PromptSemantic.SelectNResolution,
                    candidateRefsPolicy = CandidateRefsPolicy.SelectableAndUnfilteredForResolution,
                    sourceIdPolicy = SourceIdPolicy.HostCard,
                )

            else -> ChooseCardsForEffectPlan(semantic = PromptSemantic.Generic)
        }
}
