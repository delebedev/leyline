package leyline.game.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ForgeCatalogTag
import leyline.IntegrationTag
import leyline.tooling.artifact.SyntheticArtifactWriter
import leyline.tooling.headless.MatchFlowHarness
import leyline.tooling.headless.TestCardRegistry
import leyline.tooling.simclient.SimClientDriver
import java.io.File

class ForgeCatalogMatchProbeTest :
    FunSpec({
        tags(IntegrationTag, ForgeCatalogTag)
        timeout = 120_000L
        for (seed in listOf(7L, 42L, 99L)) {
            test("a normal match reaches a natural outcome without a database at seed $seed") {
                check(
                    java.nio.file.Files
                        .isDirectory(
                            java.nio.file.Paths
                                .get(System.getenv("LEYLINE_CARD_DB")),
                        ),
                )
                check(runCatching { Class.forName("org.sqlite.JDBC") }.isFailure)
                val repo = ForgeCardRepository.open()
                val deck = "24 Forest\n18 Grizzly Bears\n18 Centaur Courser"
                val harness = MatchFlowHarness(seed = seed, deckList = deck, opponentDeckList = deck, cardRepositoryOverride = repo)
                val output = File("build/forge-catalog-probe").apply { mkdirs() }
                File(output, "match-$seed.log").bufferedWriter().use { writer ->
                    try {
                        val stats =
                            SimClientDriver(
                                harness,
                                SyntheticArtifactWriter(writer, "forge-catalog-$seed"),
                                maxTurns = 40,
                            ).runOneGame()
                        File(output, "match-$seed.stats.txt").writeText(stats.toString())
                        stats.gameOver shouldBe true
                        stats.cleanupConcede shouldBe false
                        check(stats.completionReason in listOf("natural", "terminal-intermission")) { stats.toString() }
                        check(stats.winnerSeat != null) { stats.toString() }
                        check(stats.errorsByType.isEmpty()) { stats.toString() }
                        check(stats.validationViolations.isEmpty()) { stats.toString() }
                        TestCardRegistry.repo.registeredCount shouldBe 0
                        println(
                            "FORGE_MATCH_PASS seed=$seed turns=${stats.turn} messages=${stats.totalMessages} winner=${stats.winnerSeat} reason=${stats.completionReason}",
                        )
                    } finally {
                        harness.shutdown()
                    }
                }
            }
        }
    })
