package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import java.io.File
import java.nio.file.Files

class SimDiffReportToolTest :
    FunSpec({
        tags(UnitTag)

        test("ranks aggregate-zero mapped gaps and keeps per-row shortfalls as appendix") {
            val root = tempDir()
            val ref = root.resolve("ref").apply { mkdirs() }
            val cand = root.resolve("cand").apply { mkdirs() }
            val out = root.resolve("out")

            writeRef(ref, "sample-s1", decisions = listOf(decision("chooseSpellAbilityToPlay"), decision("declareBlockers")))
            writeCand(cand, "sample-s1", prompts = mapOf("ActionsAvailableReq" to 2))
            writeRef(ref, "sample-s2", decisions = listOf(decision("chooseSpellAbilityToPlay"), decision("declareBlockers")))
            writeCand(cand, "sample-s2", prompts = mapOf("ActionsAvailableReq" to 2, "DeclareBlockersReq" to 1))

            SimDiffReporter(SimDiffReportConfig(ref, cand, out)).run()

            val report = out.resolve("coverage-report.md").readText()
            val json = out.resolve("coverage-report.json").readText()
            assertSoftly {
                out.resolve("coverage-report.json").isFile shouldBe true
                report shouldContain "## Aggregate-zero mapped coverage gaps\nnone"
                report shouldContain "## Per-row mapped shortfalls"
                report shouldContain "`declareBlockers` missing in 1 row(s): sample-s1"
                json shouldContain "\"aggregateZeroMissing\":{}"
                json shouldContain "\"missingRows\":{\"declareBlockers\":[\"sample-s1\"]}"
            }
        }

        test("annotates aggregate gaps with row health confidence") {
            val root = tempDir()
            val ref = root.resolve("ref").apply { mkdirs() }
            val cand = root.resolve("cand").apply { mkdirs() }
            val out = root.resolve("out")

            writeRef(ref, "healthy-s1", decisions = listOf(decision("chooseCardsForEffect", prompt = "Choose a card")))
            writeCand(cand, "healthy-s1", prompts = emptyMap())
            writeRef(
                ref,
                "timeout-s2",
                decisions = listOf(decision("chooseCardsForEffect", prompt = "Choose a card")),
                completionReason = "wall-timeout",
                errorsByType = mapOf("java.lang.NullPointerException" to 1),
            )
            writeCand(cand, "timeout-s2", prompts = emptyMap())

            SimDiffReporter(SimDiffReportConfig(ref, cand, out)).run()

            val report = out.resolve("coverage-report.md").readText()
            val json = out.resolve("coverage-report.json").readText()
            assertSoftly {
                json.substringAfter("\"healthyRows\":").substringBefore(',') shouldBe "1"
                report shouldContain "`chooseCardsForEffect`: 2 reference callbacks, expected `SelectNReq`, candidate emitted 0"
                report shouldContain "healthyRows=1, issueRows=1"
                report shouldContain "issueRows=timeout-s2:wall-timeout, errors={java.lang.NullPointerException=1}"
                json shouldContain "\"rowHealth\":{\"natural\":1,\"issue\":1}"
                json shouldContain "\"issueSummaries\":[\"timeout-s2:wall-timeout, errors={java.lang.NullPointerException=1}\"]"
            }
        }

        test("treats chooseEntitiesForEffect as covered by SelectN or Group prompts") {
            val root = tempDir()
            val ref = root.resolve("ref").apply { mkdirs() }
            val cand = root.resolve("cand").apply { mkdirs() }
            val out = root.resolve("out")

            writeRef(ref, "group-s1", decisions = listOf(decision("chooseEntitiesForEffect")))
            writeCand(cand, "group-s1", prompts = mapOf("GroupReq" to 1))

            SimDiffReporter(SimDiffReportConfig(ref, cand, out)).run()

            val json = out.resolve("coverage-report.json").readText()
            assertSoftly {
                out.resolve("coverage-report.json").isFile shouldBe true
                json shouldContain "\"aggregateZeroMissing\":{}"
                json shouldContain "\"missingRows\":{}"
            }
        }

        test("treats mana color choices as covered by actions available") {
            val root = tempDir()
            val ref = root.resolve("ref").apply { mkdirs() }
            val cand = root.resolve("cand").apply { mkdirs() }
            val out = root.resolve("out")

            writeRef(
                ref,
                "mana-color-s1",
                decisions =
                    listOf(
                        decision("chooseColor", api = "Mana", prompt = "Select Mana to Produce"),
                    ),
            )
            writeCand(cand, "mana-color-s1", prompts = mapOf("ActionsAvailableReq" to 1))

            SimDiffReporter(SimDiffReportConfig(ref, cand, out)).run()

            val report = out.resolve("coverage-report.md").readText()
            val json = out.resolve("coverage-report.json").readText()
            assertSoftly {
                json.substringAfter("\"unmappedCallbacks\":").substringBefore(",\"callbackDispositions\"") shouldBe "{}"
                report shouldContain "## Aggregate-zero mapped coverage gaps\nnone"
                report shouldContain "## Unmapped callbacks\nnone"
                report shouldContain "`chooseColor`: mapped; count=1; mana production uses ActionsAvailableReq/Activate_Mana"
                json shouldContain "\"aggregateZeroMissing\":{}"
                json shouldContain "\"unmappedCallbacks\":{}"
            }
        }

        test("reports unmapped callbacks and reference row issues") {
            val root = tempDir()
            val ref = root.resolve("ref").apply { mkdirs() }
            val cand = root.resolve("cand").apply { mkdirs() }
            val out = root.resolve("out")

            writeRef(
                ref,
                "timeout-s1",
                decisions = listOf(decision("chooseCounterType", prompt = "Select counter type")),
                completionReason = "wall-timeout",
                errorsByType = mapOf("java.lang.IllegalStateException" to 1),
            )
            writeCand(cand, "timeout-s1", prompts = emptyMap())

            SimDiffReporter(SimDiffReportConfig(ref, cand, out)).run()

            val report = out.resolve("coverage-report.md").readText()
            val json = out.resolve("coverage-report.json").readText()
            assertSoftly {
                out.resolve("coverage-report.json").isFile shouldBe true
                report shouldContain "`timeout-s1`: wall-timeout"
                report shouldContain "`chooseCounterType`: 1"
                report shouldContain
                    "`chooseCounterType`: unmapped; count=1; " +
                    "needs counter-type selection prompt route evidence before mapping"
                json shouldContain "\"refRowIssues\":[{\"tag\":\"timeout-s1\",\"completionReason\":\"wall-timeout\""
                json shouldContain "\"unmappedCallbacks\":{\"chooseCounterType\":1}"
            }
        }

        test("ranks advisor gaps from shadow policy disagreement stats") {
            val root = tempDir()
            val ref = root.resolve("ref").apply { mkdirs() }
            val cand = root.resolve("cand").apply { mkdirs() }
            val out = root.resolve("out")

            writeRef(ref, "shadow-s1", decisions = listOf(decision("chooseSpellAbilityToPlay")))
            writeCand(
                cand,
                "shadow-s1",
                prompts = mapOf("ActionsAvailableReq" to 3),
                aiConsultedByPrompt = mapOf("ActionsAvailableReq" to 3, "SelectTargetsReq" to 2),
                aiChoseByPrompt = mapOf("ActionsAvailableReq" to 2, "SelectTargetsReq" to 2),
                advisorDisagreementsByPrompt = mapOf("SelectTargetsReq" to 2, "ActionsAvailableReq" to 1),
                advisorMatchesByPrompt = mapOf("ActionsAvailableReq" to 1),
                advisorDisagreementSamples = mapOf("SelectTargetsReq" to "greedy=select-targets:1;advisor=select-targets:2"),
            )

            SimDiffReporter(SimDiffReportConfig(ref, cand, out)).run()

            val report = out.resolve("coverage-report.md").readText()
            val json = out.resolve("coverage-report.json").readText()
            assertSoftly {
                json
                    .substringAfter("\"advisorGaps\":[{\"prompt\":\"")
                    .substringBefore('"') shouldBe "SelectTargetsReq"
                report shouldContain "## Advisor-gap ranking"
                report shouldContain "`SelectTargetsReq`: category=target-choice disagreements=2/2 consulted=2 rate=1.00"
                report shouldContain "sample=greedy=target ids 1; advisor=target ids 2"
                json shouldContain "\"advisorGaps\":[{\"prompt\":\"SelectTargetsReq\",\"consulted\":2,\"chose\":2,\"disagreements\":2"
                json shouldContain "\"category\":\"target-choice\""
            }
        }

        test("buckets actions available advisor disagreements") {
            val root = tempDir()
            val ref = root.resolve("ref").apply { mkdirs() }
            val cand = root.resolve("cand").apply { mkdirs() }
            val out = root.resolve("out")

            writeRef(ref, "action-s1", decisions = listOf(decision("chooseSpellAbilityToPlay")))
            writeCand(
                cand,
                "action-s1",
                prompts = mapOf("ActionsAvailableReq" to 2),
                aiConsultedByPrompt = mapOf("ActionsAvailableReq" to 2),
                aiChoseByPrompt = mapOf("ActionsAvailableReq" to 2),
                advisorDisagreementsByPrompt = mapOf("ActionsAvailableReq" to 1),
                advisorDisagreementSamples =
                    mapOf(
                        "ActionsAvailableReq" to
                            "greedy=perform:Play_add3:iid=1:grp=100:ability=0:alt=0;" +
                            "advisor=perform:Play_add3:iid=2:grp=200:ability=0:alt=0;" +
                            "prompt=Play_add3:1:100:0:0|Play_add3:2:200:0:0",
                    ),
            )

            SimDiffReporter(SimDiffReportConfig(ref, cand, out)).run()

            val report = out.resolve("coverage-report.md").readText()
            val json = out.resolve("coverage-report.json").readText()
            assertSoftly {
                json.substringAfter("\"category\":\"").substringBefore('"') shouldBe "land/play-sequencing"
                report shouldContain "`ActionsAvailableReq`: category=land/play-sequencing disagreements=1/2 consulted=2 rate=0.50"
                report shouldContain "sample=greedy=Play_add3 iid=1 grp=100; advisor=Play_add3 iid=2 grp=200; promptOptions=2"
                json shouldContain "\"sampleSummary\":\"greedy=Play_add3 iid=1 grp=100; advisor=Play_add3 iid=2 grp=200; promptOptions=2\""
            }
        }

        test("sim-ref decision rows include failure metadata") {
            val row =
                DeckSimClientRow(
                    name = "Sample",
                    deckList = "24 Forest\n36 Grizzly Bears",
                    opponentName = null,
                    opponentDeckList = null,
                    useCardDb = false,
                    seed = 7,
                )

            val json =
                simRefDecisionsJson(
                    SimRefDecisionReport(
                        row = row,
                        decisions = listOf(decisionRecord("chooseCounterType")),
                        durationMs = 12,
                        gameOver = false,
                        turn = 8,
                        completionReason = "exception",
                        exceptionMessage = "java.lang.IllegalStateException: boom",
                        exceptionStackTop = "Example.kt:1",
                        outcome = SimRefFinalOutcome(),
                        logs =
                            CollectedLogs(
                                warnsByLogger = mapOf("forge" to 1),
                                errorsByType = mapOf("boom" to 1),
                                errorSamples = listOf("boom"),
                            ),
                    ),
                )

            assertSoftly {
                json.substringAfter("\"completionReason\":").substringBefore(',') shouldBe "\"exception\""
                json shouldContain "\"completionReason\":\"exception\""
                json shouldContain "\"exceptionMessage\":\"java.lang.IllegalStateException: boom\""
                json shouldContain "\"errorsByType\":{\"boom\":1}"
                json shouldContain "\"callbackCounts\":{\"chooseCounterType\":1}"
            }
        }
    })

private fun tempDir(): File = Files.createTempDirectory("sim-diff-report-test").toFile()

private fun writeRef(
    dir: File,
    tag: String,
    decisions: List<String>,
    completionReason: String = "natural",
    errorsByType: Map<String, Int> = emptyMap(),
) {
    dir.resolve("$tag.refdecisions.json").writeText(
        """
        {
          "schemaVersion": 1,
          "deck": "sample",
          "seed": ${tag.substringAfterLast("-s").toLongOrNull() ?: 1L},
          "gameOver": ${completionReason == "natural"},
          "completionReason": "$completionReason",
          "errorsByType": ${mapToJson(errorsByType)},
          "decisions": [${decisions.joinToString(",")}]
        }
        """.trimIndent(),
    )
}

private fun writeCand(
    dir: File,
    tag: String,
    prompts: Map<String, Int>,
    aiConsultedByPrompt: Map<String, Int> = emptyMap(),
    aiChoseByPrompt: Map<String, Int> = emptyMap(),
    advisorDisagreementsByPrompt: Map<String, Int> = emptyMap(),
    advisorMatchesByPrompt: Map<String, Int> = emptyMap(),
    advisorDisagreementSamples: Map<String, String> = emptyMap(),
) {
    dir.resolve("$tag.stats.json").writeText(
        """
        {
          "deck": "sample",
          "seed": ${tag.substringAfterLast("-s").toLongOrNull() ?: 1L},
          "promptHistogram": ${mapToJson(prompts)},
          "aiConsultedByPrompt": ${mapToJson(aiConsultedByPrompt)},
          "aiChoseByPrompt": ${mapToJson(aiChoseByPrompt)},
          "advisorDisagreementsByPrompt": ${mapToJson(advisorDisagreementsByPrompt)},
          "advisorMatchesByPrompt": ${mapToJson(advisorMatchesByPrompt)},
          "advisorDisagreementSamples": ${stringMapToJson(advisorDisagreementSamples)}
        }
        """.trimIndent(),
    )
}

private fun stringMapToJson(values: Map<String, String>): String =
    values.entries.joinToString(",", "{", "}") { (key, value) ->
        "${simJsonString(key)}:${simJsonString(value)}"
    }

private fun decision(
    callback: String,
    source: String? = null,
    api: String? = null,
    prompt: String? = null,
): String =
    buildString {
        append("{\"seat\":1,\"turn\":1,\"phase\":\"MAIN1\",\"callback\":${simJsonString(callback)}")
        source?.let { append(",\"source\":${simJsonString(it)}") }
        api?.let { append(",\"api\":${simJsonString(it)}") }
        prompt?.let { append(",\"prompt\":${simJsonString(it)}") }
        append("}")
    }

private fun decisionRecord(callback: String): SimRefDecision =
    SimRefDecision(
        index = 0,
        seat = 1,
        turn = 1,
        phase = "MAIN1",
        callback = callback,
        source = null,
        api = null,
        prompt = null,
    )
