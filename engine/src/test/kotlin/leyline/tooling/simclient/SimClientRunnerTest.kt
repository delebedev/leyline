package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
                Files.exists(outDir.resolve("${row.tag}.meta.json").toPath()) shouldBe true
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
            row.runKind shouldBe "puzzle"
            row.runLabel shouldBe "bolt-face"
            Files.exists(outDir.resolve("${row.tag}.stats.json").toPath()) shouldBe true
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
