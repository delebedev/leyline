package leyline.acceptance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.AcceptanceTag
import leyline.IntegrationTag

class AcceptanceSuitesTest :
    FunSpec({
        tags(AcceptanceTag, IntegrationTag)

        val executor = MatchdoorAcceptanceExecutor()
        val suiteFilter = csvProperty("acceptance.suites")
        val scenarioFilter = csvProperty("acceptance.scenarios")
        val suiteNames =
            listOf(
                "warmup",
                "mechanics-warmup",
                "combat-warmup",
                "graveyard",
                "interactions-warmup",
                "cost-selection-warmup",
                "modal-warmup",
                "hybrid-mana",
                "mechanics-protocol",
            )
                .filter { suiteFilter == null || it in suiteFilter }

        require(suiteNames.isNotEmpty()) {
            "No acceptance suites matched acceptance.suites=${suiteFilter.orEmpty()}"
        }

        val suites =
            suiteNames.map { suiteName ->
                val suite = AcceptanceSuiteLoader.load(suiteName)
                suite.copy(scenarios = suite.scenarios.filter { scenarioFilter == null || it.id in scenarioFilter })
            }

        require(suites.any { it.scenarios.isNotEmpty() }) {
            "No acceptance scenarios matched acceptance.scenarios=${scenarioFilter.orEmpty()}"
        }

        suites.forEach { suite ->
            suite.scenarios.forEach { scenario ->
                test("${suite.name} — ${scenario.id}") {
                    executor.runScenario(scenario) shouldBe scenario.steps.size
                }
            }
        }
    })

private fun csvProperty(name: String): Set<String>? =
    System.getProperty(name)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?.takeIf { it.isNotEmpty() }
