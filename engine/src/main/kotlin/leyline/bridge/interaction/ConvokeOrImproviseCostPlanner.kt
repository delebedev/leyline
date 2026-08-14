package leyline.bridge.interaction

import leyline.bridge.handoff.PromptSemantic

data class ConvokeOrImproviseCostPlan(
    val keyword: String,
    val semantic: PromptSemantic,
    val maxSelection: Int,
    val candidateRefsPolicy: CandidateRefsPolicy,
    val manaFieldsPolicy: CostManaFieldsPolicy,
)

enum class CostManaFieldsPolicy {
    None,
    IncludeNativePaymentCost,
}

val CostManaFieldsPolicy.shouldInclude: Boolean
    get() = this == CostManaFieldsPolicy.IncludeNativePaymentCost

object ConvokeOrImproviseCostPlanner {
    fun plan(
        optionCount: Int,
        maxReduction: Int?,
        artifacts: Boolean,
        creatures: Boolean,
    ): ConvokeOrImproviseCostPlan {
        val maxSelection = optionCount.coerceAtMost(maxReduction ?: optionCount)
        return when {
            artifacts && creatures ->
                ConvokeOrImproviseCostPlan(
                    keyword = "waterbend",
                    semantic = PromptSemantic.WaterbendCost,
                    maxSelection = maxSelection,
                    candidateRefsPolicy = CandidateRefsPolicy.Selectable,
                    manaFieldsPolicy = CostManaFieldsPolicy.IncludeNativePaymentCost,
                )

            creatures ->
                ConvokeOrImproviseCostPlan(
                    keyword = "convoke",
                    semantic = PromptSemantic.ConvokeCost,
                    maxSelection = maxSelection,
                    candidateRefsPolicy = CandidateRefsPolicy.Selectable,
                    manaFieldsPolicy = CostManaFieldsPolicy.IncludeNativePaymentCost,
                )

            artifacts ->
                ConvokeOrImproviseCostPlan(
                    keyword = "improvise",
                    semantic = PromptSemantic.ImproviseCost,
                    maxSelection = maxSelection,
                    candidateRefsPolicy = CandidateRefsPolicy.Selectable,
                    manaFieldsPolicy = CostManaFieldsPolicy.IncludeNativePaymentCost,
                )

            else ->
                ConvokeOrImproviseCostPlan(
                    keyword = "convoke",
                    semantic = PromptSemantic.Generic,
                    maxSelection = maxSelection,
                    candidateRefsPolicy = CandidateRefsPolicy.None,
                    manaFieldsPolicy = CostManaFieldsPolicy.None,
                )
        }
    }
}
