package leyline.behavior.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.IntegrationTag
import leyline.acceptance.AcceptanceSuiteLoader
import leyline.acceptance.MatchdoorAcceptanceExecutor
import leyline.testkit.ProtocolContract

/**
 * Executes every authored protocol contract against its deterministic acceptance scenario.
 * Scenario steps own gameplay intent; YAML owns the emitted protocol obligations.
 * Checker regressions run separately, so adding a contract requires no Kotlin dispatch.
 */
class ProtocolConformanceTest :
    FunSpec({
        tags(IntegrationTag, ConformanceTag)
        val paths = ProtocolContract.files()
        require(paths.isNotEmpty()) { "no protocol contracts" }
        val scenarios = paths.map(ProtocolContract::load).groupBy { it.suite to it.scenario }
        for ((identity, contracts) in scenarios) {
            test("${identity.first}/${identity.second}") {
                val scenario = AcceptanceSuiteLoader.load(identity.first).scenarios.single { it.id == identity.second }
                MatchdoorAcceptanceExecutor().runScenario(scenario) { messages ->
                    ProtocolContract.verifyAll(contracts, messages)
                } shouldBe scenario.steps.size
            }
        }
    })
