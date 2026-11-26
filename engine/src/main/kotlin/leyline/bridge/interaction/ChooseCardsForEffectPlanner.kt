package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolutionAbilityShape
import leyline.bridge.handoff.ResolutionRouteInput
import leyline.bridge.types.PromptCandidateRefDto

data class ChooseCardsForEffectContext(
    val sa: SpellAbility?,
    val optionCount: Int,
    val candidateRefs: List<PromptCandidateRefDto>,
    val activeReveal: Boolean,
    val allCandidatesProjectable: Boolean,
)

data class ChooseCardsForEffectPlan(
    val semantic: PromptSemantic,
    val forcePrompt: Boolean = false,
    val candidateRefsPolicy: CandidateRefsPolicy = CandidateRefsPolicy.None,
    val sourceIdPolicy: SourceIdPolicy = SourceIdPolicy.None,
    val mandatoryChoicePolicy: MandatoryChoicePolicy = MandatoryChoicePolicy.AutoResolveWhenSatisfied,
    val resolutionRouteInput: ResolutionRouteInput? = null,
)

object ChooseCardsForEffectPlanner {
    fun plan(context: ChooseCardsForEffectContext): ChooseCardsForEffectPlan {
        val resolutionInput =
            resolutionRouteInput(
                context.candidateRefs,
                context.optionCount,
                lookAndPickShape(context.sa),
                context.allCandidatesProjectable,
            )
        return when {
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

            resolutionInput.isHiddenLibraryCardChoice ->
                ChooseCardsForEffectPlan(
                    semantic = PromptSemantic.SelectNResolution,
                    candidateRefsPolicy = CandidateRefsPolicy.Selectable,
                    sourceIdPolicy = SourceIdPolicy.HostCard,
                    resolutionRouteInput = resolutionInput,
                )

            context.sa?.api == ApiType.ChangeZone && resolutionInput.isCompleteLibraryCardChoice ->
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
                    resolutionRouteInput = resolutionInput,
                )

            resolutionInput.isMappedCardChoice ->
                ChooseCardsForEffectPlan(
                    semantic = PromptSemantic.SelectNResolution,
                    candidateRefsPolicy = CandidateRefsPolicy.SelectableAndUnfilteredForResolution,
                    sourceIdPolicy = SourceIdPolicy.HostCard,
                    resolutionRouteInput = resolutionInput,
                )

            else -> ChooseCardsForEffectPlan(semantic = PromptSemantic.Generic)
        }
    }

    /** ChooseCard over library cards picks among cards its effect shows the chooser. */
    private fun lookAndPickShape(sa: SpellAbility?): ResolutionAbilityShape =
        if (sa?.api == ApiType.ChooseCard) ResolutionAbilityShape.LibraryChoice else ResolutionAbilityShape.Other
}
