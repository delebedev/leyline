package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import leyline.SimClientTag
import leyline.tooling.headless.TestCardRegistry
import java.nio.file.Files

class SimClientRunnerTest :
    FunSpec({
        tags(SimClientTag)

        test("runner executes one tracked deck row and writes its artifacts") {
            registerRunnerCards()
            val outDir = Files.createTempDirectory("simclient-runner-deck").toFile()
            val result =
                SimClientRunner(
                    SimClientConfig(
                        deckSpec = "forest-only",
                        seedSpec = "42",
                        maxTurns = 1,
                        outDir = outDir,
                        excludeCardsFile = null,
                    ),
                    TestCardRegistry.repo,
                ).run()

            result.rows shouldHaveSize 1
            val row = result.rows.single().row
            row shouldBe
                DeckSimClientRow(
                    name = "forest-only",
                    deckList = "60 Forest\n",
                    opponentName = null,
                    opponentDeckList = null,
                    seed = 42,
                )
            assertSoftly {
                Files.exists(outDir.resolve("${row.tag}.log").toPath()) shouldBe true
                val meta = outDir.resolve("${row.tag}.meta.json").toPath()
                Files.exists(meta) shouldBe true
                Files.readString(meta) shouldContain "\"source\": \"simclient\""
                Files.readString(meta) shouldContain "\"deck:forest-only\""
                Files.exists(outDir.resolve("${row.tag}.stats.json").toPath()) shouldBe true
            }
        }

        test("runner executes one tracked puzzle row through the same runner") {
            registerRunnerCards()
            val outDir = Files.createTempDirectory("simclient-runner-puzzle").toFile()
            val result =
                SimClientRunner(
                    SimClientConfig(
                        puzzleSpec = "bolt-face.pzl",
                        seedSpec = "42",
                        maxTurns = 2,
                        outDir = outDir,
                        excludeCardsFile = null,
                    ),
                    TestCardRegistry.repo,
                ).run()

            result.rows shouldHaveSize 1
            val row = result.rows.single().row
            assertSoftly {
                row.runKind shouldBe "puzzle"
                row.runLabel shouldBe "bolt-face"
                Files.readString(outDir.resolve("${row.tag}.meta.json").toPath()) shouldContain "\"puzzle:bolt-face\""
                Files.exists(outDir.resolve("${row.tag}.stats.json").toPath()) shouldBe true
            }
        }

        test("strict runner failures change the command exit status") {
            val stats = failureStats()
            val outDir = Files.createTempDirectory("simclient-runner-strict").toFile()

            fun run(strict: Boolean): Int =
                SimClientMain.run(
                    buildList {
                        addAll(listOf("--decks", "forest-only", "--seeds", "1", "--out-dir", outDir.path))
                        if (strict) add("--strict")
                    },
                    emptyMap(),
                ) { config ->
                    SimClientRunner(config, TestCardRegistry.repo) { stats }
                }

            run(strict = true) shouldBe 1
            run(strict = false) shouldBe 0

            val summary = outDir.resolve("summary.json").readText()
            assertSoftly {
                summary shouldContain "\"schemaVersion\":$STATS_SCHEMA_VERSION"
                summary shouldContain "\"rowsRun\":1"
                summary shouldContain "\"strictFailures\":1"
                summary shouldNotContain "promptHistogram"
            }
        }

        test("summary sums affordability diagnostics by card across rows") {
            val stats =
                failureStats().copy(
                    errorsByType = emptyMap(),
                    completionReason = "max-turns",
                    offeredWhileUnsureByCard = mapOf("Chord of Calling" to 3),
                    offeredThenPaymentFailedByCard = mapOf("Savannah Lions (unsure)" to 1),
                )
            val outDir = Files.createTempDirectory("simclient-runner-affordability").toFile()
            SimClientMain.run(listOf("--decks", "forest-only", "--seeds", "1..2", "--out-dir", outDir.path), emptyMap()) { config ->
                SimClientRunner(config, TestCardRegistry.repo) { stats }
            }

            val summary = outDir.resolve("summary.json").readText()
            val row =
                statsToJson(
                    DeckSimClientRow(
                        name = "forest-only",
                        deckList = "60 Forest\n",
                        opponentName = null,
                        opponentDeckList = null,
                        seed = 1,
                    ),
                    stats,
                    SimClientPolicyMode.ForgeAi,
                )
            val affordability = Json.parseToJsonElement(summary).jsonObject.getValue("affordability")
            val rowJson = Json.parseToJsonElement(row).jsonObject
            assertSoftly {
                affordability shouldBe
                    Json.parseToJsonElement(
                        "{\"offeredWhileUnsure\":6,\"offeredThenPaymentFailed\":2," +
                            "\"offeredWhileUnsureByCard\":{\"Chord of Calling\":6}," +
                            "\"offeredThenPaymentFailedByCard\":{\"Savannah Lions (unsure)\":2}}",
                    )
                rowJson.getValue("offeredWhileUnsureByCard") shouldBe Json.parseToJsonElement("{\"Chord of Calling\":3}")
                rowJson.getValue("offeredThenPaymentFailedByCard") shouldBe
                    Json.parseToJsonElement("{\"Savannah Lions (unsure)\":1}")
            }
        }
    })

private fun registerRunnerCards() {
    TestCardRegistry.ensureDeckRegistered(
        """
        60 Forest
        4 Grizzly Bears
        4 Lightning Bolt
        4 Mountain
        """.trimIndent(),
    )
}

private fun failureStats() =
    GameStats(
        turn = 0,
        gameOver = false,
        iterations = 0,
        totalMessages = 0,
        promptHistogram = emptyMap(),
        hitIterCap = false,
        errorsByType = mapOf("test.failure" to 1),
        completionReason = "exception",
    )
