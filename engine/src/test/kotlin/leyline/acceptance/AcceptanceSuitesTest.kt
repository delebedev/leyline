package leyline.acceptance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.AcceptanceTag
import leyline.IntegrationTag
import leyline.tooling.artifact.SyntheticArtifactIdentity
import leyline.tooling.artifact.openSyntheticArtifactRun
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

class AcceptanceSuitesTest :
    FunSpec({
        tags(AcceptanceTag, IntegrationTag)

        val executor = MatchdoorAcceptanceExecutor()
        val suiteFilter = csvProperty("acceptance.suites")
        val scenarioFilter = csvProperty("acceptance.scenarios")
        val emitScry = System.getProperty("acceptance.scry").toBoolean()
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
                    val onComplete =
                        if (emitScry) {
                            { messages: List<GREToClientMessage> -> writeScryRun(suite.name, scenario.id, messages) }
                        } else {
                            {}
                        }
                    executor.runScenario(scenario, onComplete) shouldBe scenario.steps.size
                }
            }
        }
    })

private fun writeScryRun(
    suite: String,
    scenario: String,
    messages: List<GREToClientMessage>,
) {
    val seed = 42L
    val runLabel = "$suite:$scenario"
    val matchId = "acceptance-$suite-$scenario-s$seed"
    val outDir = AcceptancePaths.resolve("engine", exists = Files::isDirectory).resolve("build/acceptance-scry").toFile()
    outDir.mkdirs()
    val logFile = File(outDir, "$matchId.log")
    val artifactRun =
        openSyntheticArtifactRun(
            logFile = logFile,
            identity =
                SyntheticArtifactIdentity(
                    matchId = matchId,
                    runLabel = runLabel,
                    seed = seed,
                    generatedAt = LocalDateTime.now(),
                    runKind = "acceptance",
                ),
        )
    try {
        artifactRun.writeBundle(messages)
    } finally {
        artifactRun.finish(ingestTo = Path.of(System.getProperty("user.home"), ".scry", "games"))
    }
}

private fun discoverSuiteNames(): List<String> {
    val dir = AcceptancePaths.resolve("puzzles/sets", notFoundMessage = "puzzles/sets not found", exists = Files::isDirectory)
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
