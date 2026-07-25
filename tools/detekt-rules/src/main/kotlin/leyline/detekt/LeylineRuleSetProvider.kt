package leyline.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class LeylineRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("leyline")

    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                ::TrivialKDoc,
                ::BooleanAssertion,
                ::VacuousTestSkip,
                ::EmptyAssertion,
                ::MissingAssertSoftly,
                ::FunSpecMissingTags,
                ::NoGameInMappers,
                ::NoThreadSleepInTests,
                ::NoTimingAssertsInTests,
                ::TierPlacementCheck,
                ::TestLayoutCheck,
                ::WeakAssertionOnly,
            ),
        )
}
