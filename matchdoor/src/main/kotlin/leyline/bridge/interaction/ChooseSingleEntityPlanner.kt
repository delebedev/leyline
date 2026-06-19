package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptSemantic

enum class ChooseSingleEntityRoutePolicy {
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
)

data class ChooseSingleEntityPlan(
    val routePolicy: ChooseSingleEntityRoutePolicy,
    val semantic: PromptSemantic,
    val min: Int,
    val max: Int,
    val candidateRefsPolicy: CandidateRefsPolicy,
    val sourceIdPolicy: SourceIdPolicy,
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
                context.sa?.isMutate == true -> ChooseSingleEntityRoutePolicy.MutateTopCard
                context.activeReveal && context.allOptionsAreCards -> ChooseSingleEntityRoutePolicy.ActiveReveal
                context.optionCount == 1 && !context.isOptional -> ChooseSingleEntityRoutePolicy.AutoReturnFirst
                else -> ChooseSingleEntityRoutePolicy.Prompt
            }

        return ChooseSingleEntityPlan(
            routePolicy = route,
            semantic = semantic,
            min = if (context.isOptional) 0 else 1,
            max = 1,
            candidateRefsPolicy =
                if (route == ChooseSingleEntityRoutePolicy.Prompt) {
                    CandidateRefsPolicy.Selectable
                } else {
                    CandidateRefsPolicy.None
                },
            sourceIdPolicy = if (isLearn) SourceIdPolicy.HostCard else SourceIdPolicy.None,
            isSearch = isSearch,
            isLearn = isLearn,
            isLegendRule = isLegendRule,
        )
    }
}
