package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.spellability.AlternativeCost
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolutionAbilityShape
import leyline.bridge.handoff.ResolutionRouteInput
import leyline.bridge.types.PromptCandidateRefDto

data class ChooseEntitiesContext(
    val sa: SpellAbility?,
    val min: Int,
    val max: Int,
    val optionCount: Int,
    val candidateRefs: List<PromptCandidateRefDto>,
    val allCandidatesProjectable: Boolean,
)

data class ChooseEntitiesPlan(
    val effectiveMin: Int,
    val effectiveMax: Int,
    val autoReturnPolicy: AutoReturnPolicy,
    val semantic: PromptSemantic,
    val candidateRefsPolicy: CandidateRefsPolicy,
    val sourceIdPolicy: SourceIdPolicy,
    val resolutionRouteInput: ResolutionRouteInput?,
)

object ChooseEntitiesPlanner {
    fun plan(context: ChooseEntitiesContext): ChooseEntitiesPlan {
        val effectiveMax = context.max.coerceAtMost(context.optionCount)
        val effectiveMin = context.min.coerceAtLeast(0).coerceAtMost(effectiveMax)
        val resolutionInput =
            resolutionRouteInput(
                context.candidateRefs,
                context.optionCount,
                if (context.sa?.api == ApiType.Dig) ResolutionAbilityShape.Dig else ResolutionAbilityShape.Other,
                context.allCandidatesProjectable,
            )
        val semantic = semanticFor(context.sa, resolutionInput)
        val prompted = context.optionCount > effectiveMin
        return ChooseEntitiesPlan(
            effectiveMin = effectiveMin,
            effectiveMax = effectiveMax,
            autoReturnPolicy = if (prompted) AutoReturnPolicy.Prompt else AutoReturnPolicy.ReturnAllWhenSelectionSatisfied,
            semantic = semantic,
            candidateRefsPolicy =
                when {
                    !prompted -> CandidateRefsPolicy.None
                    semantic == PromptSemantic.Search -> CandidateRefsPolicy.Selectable
                    semantic == PromptSemantic.SelectNResolution && resolutionInput.isHiddenLibraryCardChoice ->
                        CandidateRefsPolicy.Selectable
                    else -> CandidateRefsPolicy.SelectableAndUnfilteredForResolution
                },
            sourceIdPolicy = SourceIdPolicy.HostCard,
            resolutionRouteInput = resolutionInput.takeIf { semantic == PromptSemantic.SelectNResolution },
        )
    }

    private fun semanticFor(
        sa: SpellAbility?,
        resolutionInput: ResolutionRouteInput,
    ): PromptSemantic =
        when {
            sa?.alternativeCost == AlternativeCost.Escape -> PromptSemantic.SelectNCostExileFromGrave
            SpellAbilityShapes.isHandToLibraryReorder(sa) -> PromptSemantic.SelectNLibraryPutback
            sa?.api == ApiType.ChangeZone && resolutionInput.isCompleteLibraryCardChoice -> PromptSemantic.Search
            else -> PromptSemantic.SelectNResolution
        }
}
