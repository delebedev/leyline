package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class LeylineRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "leyline"

    override fun instance(config: Config): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                TrivialKDoc(config),
                BooleanAssertion(config),
                VacuousTestSkip(config),
                EmptyAssertion(config),
                MissingAssertSoftly(config),
                FunSpecMissingTags(config),
                NoGameInMappers(config),
                NoThreadSleepInTests(config),
                NoTimingAssertsInTests(config),
                TierPlacementCheck(config),
                WeakAssertionOnly(config),
            ),
        )
}
