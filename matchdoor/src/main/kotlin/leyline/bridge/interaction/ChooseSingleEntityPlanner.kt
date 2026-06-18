package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateRefDto

enum class ChooseSingleEntityRoute {
    MutateTopCard,
    ActiveReveal,
    AutoReturnFirst,
    Prompt,
}

data class ChooseSingleEntityContext(
    val sa: SpellAbility?,
    val isOptional: Boolean,
    val hasDelayedReveal: Boolean,
    val optionCount: Int,
    val allOptionsAreCards: Boolean,
    val activeReveal: Boolean,
    val candidateRefs: List<PromptCandidateRefDto>,
)

data class ChooseSingleEntityPlan(
    val route: ChooseSingleEntityRoute,
    val semantic: PromptSemantic,
    val min: Int,
    val max: Int,
    val candidateRefs: List<PromptCandidateRefDto>,
    val sourceEntityId: Int?,
    val isSearch: Boolean,
    val isLearn: Boolean,
    val isLegendRule: Boolean,
)

object ChooseSingleEntityPlanner {
    fun plan(context: ChooseSingleEntityContext): ChooseSingleEntityPlan {
        val isLegendRule = context.sa?.api == ApiType.InternalLegendaryRule
        val isLearn = context.sa?.api == ApiType.Learn
        val isSearch = context.sa?.api == ApiType.ChangeZone || context.hasDelayedReveal
        val semantic =
            when {
                isLegendRule -> PromptSemantic.SelectNLegendRule
                isLearn -> PromptSemantic.LearnLesson
                isSearch -> PromptSemantic.Search
                else -> PromptSemantic.SelectNResolution
            }
        val route =
            when {
                context.sa?.isMutate == true -> ChooseSingleEntityRoute.MutateTopCard
                context.activeReveal && context.allOptionsAreCards -> ChooseSingleEntityRoute.ActiveReveal
                context.optionCount == 1 && !context.isOptional -> ChooseSingleEntityRoute.AutoReturnFirst
                else -> ChooseSingleEntityRoute.Prompt
            }

        return ChooseSingleEntityPlan(
            route = route,
            semantic = semantic,
            min = if (context.isOptional) 0 else 1,
            max = 1,
            candidateRefs = if (route == ChooseSingleEntityRoute.Prompt) context.candidateRefs else emptyList(),
            sourceEntityId = if (isLearn) context.sa?.hostCard?.id else null,
            isSearch = isSearch,
            isLearn = isLearn,
            isLegendRule = isLegendRule,
        )
    }
}
