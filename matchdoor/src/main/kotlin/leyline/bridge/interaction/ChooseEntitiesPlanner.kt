package leyline.bridge.interaction

import forge.game.spellability.AlternativeCost
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptSemantic

data class ChooseEntitiesContext(
    val sa: SpellAbility?,
    val min: Int,
    val max: Int,
    val optionCount: Int,
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
        val semantic = semanticFor(context.sa)
        val prompted = context.optionCount > effectiveMin
        return ChooseEntitiesPlan(
            effectiveMin = effectiveMin,
            effectiveMax = effectiveMax,
            autoReturnPolicy = if (prompted) AutoReturnPolicy.Prompt else AutoReturnPolicy.ReturnAllWhenSelectionSatisfied,
            semantic = semantic,
            candidateRefsPolicy = if (prompted) CandidateRefsPolicy.SelectableAndUnfilteredForResolution else CandidateRefsPolicy.None,
            sourceIdPolicy = SourceIdPolicy.HostCard,
        )
    }

    private fun semanticFor(sa: SpellAbility?): PromptSemantic =
        when {
            sa?.alternativeCost == AlternativeCost.Escape -> PromptSemantic.SelectNCostExileFromGrave
            SpellAbilityShapes.isHandToLibraryReorder(sa) -> PromptSemantic.SelectNLibraryPutback
            else -> PromptSemantic.SelectNResolution
        }
}
