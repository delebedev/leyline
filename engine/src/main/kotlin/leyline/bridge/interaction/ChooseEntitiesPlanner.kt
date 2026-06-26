package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.spellability.AlternativeCost
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptSemantic

data class ChooseEntitiesContext(
    val sa: SpellAbility?,
    val min: Int,
    val max: Int,
    val optionCount: Int,
    val hiddenLibrarySelection: Boolean,
)

data class ChooseEntitiesPlan(
    val effectiveMin: Int,
    val effectiveMax: Int,
    val autoReturnPolicy: AutoReturnPolicy,
    val semantic: PromptSemantic,
    val candidateRefsPolicy: CandidateRefsPolicy,
    val sourceIdPolicy: SourceIdPolicy,
)

object ChooseEntitiesPlanner {
    fun plan(context: ChooseEntitiesContext): ChooseEntitiesPlan {
        val effectiveMax = context.max.coerceAtMost(context.optionCount)
        val effectiveMin = context.min.coerceAtLeast(0).coerceAtMost(effectiveMax)
        val semantic = semanticFor(context.sa, context.hiddenLibrarySelection)
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
                    else -> CandidateRefsPolicy.SelectableAndUnfilteredForResolution
                },
            sourceIdPolicy = SourceIdPolicy.HostCard,
        )
    }

    private fun semanticFor(
        sa: SpellAbility?,
        hiddenLibrarySelection: Boolean,
    ): PromptSemantic =
        when {
            sa?.alternativeCost == AlternativeCost.Escape -> PromptSemantic.SelectNCostExileFromGrave
            SpellAbilityShapes.isHandToLibraryReorder(sa) -> PromptSemantic.SelectNLibraryPutback
            sa?.api == ApiType.ChangeZone && hiddenLibrarySelection -> PromptSemantic.Search
            else -> PromptSemantic.SelectNResolution
        }
}
