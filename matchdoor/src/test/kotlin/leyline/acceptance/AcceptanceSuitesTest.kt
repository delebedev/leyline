package leyline.acceptance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.AcceptanceTag
import leyline.IntegrationTag

class AcceptanceSuitesTest :
    FunSpec({
        tags(AcceptanceTag, IntegrationTag)

        val executor = MatchdoorAcceptanceExecutor()
        val suites =
            listOf(
                "warmup",
                "mechanics-warmup",
                "combat-warmup",
                "graveyard",
                "interactions-warmup",
                "cost-selection-warmup",
                "modal-warmup",
                "mechanics-protocol",
            )

        suites.forEach { suiteName ->
            val suite = AcceptanceSuiteLoader.load(suiteName)
            suite.scenarios.forEach { scenario ->
                test("${suite.name} — ${scenario.id}") {
                    executor.runScenario(scenario) shouldBe scenario.steps.size
                }
            }
        }
    })
