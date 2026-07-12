package leyline.acceptance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.AcceptanceTag
import leyline.IntegrationTag
import java.nio.file.Files
import java.nio.file.Paths

class AcceptanceSuitesTest :
    FunSpec({
        tags(AcceptanceTag, IntegrationTag)

        val executor = MatchdoorAcceptanceExecutor()
        val suiteFilter = csvProperty("acceptance.suites")
        val scenarioFilter = csvProperty("acceptance.scenarios")
        val suiteNames = discoverSuiteNames().filter { suiteFilter == null || it in suiteFilter }

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

private fun discoverSuiteNames(): List<String> {
    val candidates =
        listOf(
            Paths.get("puzzles/sets"),
            Paths.get("../puzzles/sets"),
            Paths.get("../../puzzles/sets"),
        )
    val dir =
        candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("puzzles/sets not found in $candidates (cwd=${Paths.get("").toAbsolutePath()})")
    return Files.newDirectoryStream(dir, "*.yaml").use { stream ->
        stream.map { it.fileName.toString().removeSuffix(".yaml") }.sorted()
    }
}

private fun csvProperty(name: String): Set<String>? =
    System
        .getProperty(name)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?.takeIf { it.isNotEmpty() }
