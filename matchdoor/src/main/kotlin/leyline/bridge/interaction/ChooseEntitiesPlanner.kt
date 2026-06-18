package leyline.bridge.interaction

import forge.game.spellability.AlternativeCost
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateRefDto

data class ChooseEntitiesContext(
    val sa: SpellAbility?,
    val min: Int,
    val max: Int,
    val optionCount: Int,
    val candidateRefs: List<PromptCandidateRefDto>,
)

data class ChooseEntitiesPlan(
    val effectiveMin: Int,
    val effectiveMax: Int,
    val autoReturnAll: Boolean,
    val semantic: PromptSemantic,
    val candidateRefs: List<PromptCandidateRefDto>,
    val unfilteredRefs: List<PromptCandidateRefDto>,
    val sourceEntityId: Int?,
)

object ChooseEntitiesPlanner {
    fun plan(context: ChooseEntitiesContext): ChooseEntitiesPlan {
        val effectiveMax = context.max.coerceAtMost(context.optionCount)
        val effectiveMin = context.min.coerceAtLeast(0).coerceAtMost(effectiveMax)
        val semantic = semanticFor(context.sa)
        val prompted = context.optionCount > effectiveMin
        val candidateRefs = if (prompted) context.candidateRefs else emptyList()
        return ChooseEntitiesPlan(
            effectiveMin = effectiveMin,
            effectiveMax = effectiveMax,
            autoReturnAll = !prompted,
            semantic = semantic,
            candidateRefs = candidateRefs,
            unfilteredRefs = if (semantic == PromptSemantic.SelectNResolution) candidateRefs else emptyList(),
            sourceEntityId = context.sa?.hostCard?.id,
        )
    }

    private fun semanticFor(sa: SpellAbility?): PromptSemantic =
        when {
            sa?.alternativeCost == AlternativeCost.Escape -> PromptSemantic.SelectNCostExileFromGrave
            SpellAbilityShapes.isHandToLibraryReorder(sa) -> PromptSemantic.SelectNLibraryPutback
            else -> PromptSemantic.SelectNResolution
        }
}
