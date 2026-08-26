package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.SimClientTag
import leyline.bridge.types.SeatId
import leyline.copilot.ForgeAiPolicy
import leyline.game.generator.PuzzleSource
import leyline.testkit.MatchFlowHarness
import leyline.tooling.artifact.SyntheticArtifactWriter
import java.nio.file.Files

@Suppress("TierPlacementCheck") // These policies must traverse the headless match loop.
class SnapshotPromptDriverTest :
    FunSpec({
        tags(SimClientTag)

        fun runPuzzle(
            puzzle: String,
            policy: SimClientPolicyMode,
        ): GameStats {
            val harness = MatchFlowHarness(seed = 42L)
            val writer = Files.createTempFile("snapshot-prompt-driver-", ".log").toFile().bufferedWriter()
            return try {
                SimClientDriver(
                    harness = harness,
                    log = SyntheticArtifactWriter(writer, "snapshot-prompt-driver"),
                    maxTurns = 2,
                    connect = {
                        harness.connectAndKeepPuzzleText(
                            PuzzleSource.definitionFromResource("data/puzzles/$puzzle").content,
                        )
                    },
                    forgeAi =
                        if (policy == SimClientPolicyMode.ForgeAi) {
                            ForgeAiPolicy({ harness.bridge }, SeatId(1))
                        } else {
                            null
                        },
                    snapshotConsult = policy == SimClientPolicyMode.Snapshot,
                ).runOneGame()
            } finally {
                writer.close()
                runCatching { harness.shutdown() }
            }
        }

        test("forge-ai policy and snapshot consult cast the exact-lethal line") {
            val baseline = runPuzzle("gre-game-over-bolt.pzl", SimClientPolicyMode.ForgeAi)
            val snapshot = runPuzzle("gre-game-over-bolt.pzl", SimClientPolicyMode.Snapshot)

            assertSoftly {
                baseline.gameOver shouldBe true
                baseline.winnerSeat shouldBe 1
                baseline.loserSeat shouldBe 2
                baseline.cleanupConcede shouldBe false
                withClue(baseline.promptProgressSamples) {
                    baseline.promptProgressSamples.any { it.decisionKind == "perform:Cast" } shouldBe true
                }

                snapshot.gameOver shouldBe true
                snapshot.winnerSeat shouldBe 1
                snapshot.loserSeat shouldBe 2
                snapshot.cleanupConcede shouldBe false
                withClue(snapshot.promptProgressSamples) {
                    snapshot.promptProgressSamples.any { it.decisionKind == "snapshot:cast" } shouldBe true
                    snapshot.promptProgressSamples.any { it.decisionKind == "snapshot:target" } shouldBe true
                }
            }
        }

        test("forge-ai policy and snapshot consult make the forced block") {
            val baseline = runPuzzle("declare-blockers.pzl", SimClientPolicyMode.ForgeAi)
            val snapshot = runPuzzle("declare-blockers.pzl", SimClientPolicyMode.Snapshot)

            assertSoftly {
                baseline.finalLifeBySeat["1"] shouldBe 3
                baseline.promptProgressSamples.any { it.decisionKind == "declare-blockers" } shouldBe true
                snapshot.finalLifeBySeat["1"] shouldBe 3
                snapshot.actionAttemptsByType["snapshot:block"] shouldBe 1
                snapshot.actionAttemptsByType["snapshot:submit_blockers"] shouldBe 1
            }
        }

        test("forge-ai policy and snapshot consult preserve an Omen cast offer through the generic matcher") {
            val baseline = runPuzzle("omen-signaling-roar-lethal.pzl", SimClientPolicyMode.ForgeAi)
            val snapshot = runPuzzle("omen-signaling-roar-lethal.pzl", SimClientPolicyMode.Snapshot)

            assertSoftly {
                baseline.winnerSeat shouldBe 1
                baseline.promptProgressSamples.any { it.decisionKind == "perform:CastOmen" } shouldBe true

                snapshot.winnerSeat shouldBe 1
                snapshot.promptProgressSamples.any { it.decisionKind == "snapshot:cast_omen" } shouldBe true
            }
        }

        test("snapshot consult submits a partial blocker declaration without toggling it") {
            val baseline = runPuzzle("partial-blocker-convergence.pzl", SimClientPolicyMode.ForgeAi)
            val snapshot = runPuzzle("partial-blocker-convergence.pzl", SimClientPolicyMode.Snapshot)

            assertSoftly {
                baseline.winnerSeat shouldBe 1
                baseline.promptProgressSamples.single { it.decisionKind == "declare-blockers" }.targetIds shouldBe listOf(100)

                snapshot.winnerSeat shouldBe 1
                snapshot.actionAttemptsByType["snapshot:block"] shouldBe 1
                snapshot.actionAttemptsByType["snapshot:submit_blockers"] shouldBe 1
                snapshot.actionAttemptsByType["snapshot:unblock"] shouldBe null
                snapshot.promptProgressSamples.single { it.decisionKind == "snapshot:block" }.targetIds shouldBe listOf(100)
            }
        }

        test("forge-ai policy and snapshot consult cast an attacking combat trick") {
            val puzzle = "combat-trick-attacking-lethal.pzl"
            val baseline = runPuzzle(puzzle, SimClientPolicyMode.ForgeAi)
            val snapshot = runPuzzle(puzzle, SimClientPolicyMode.Snapshot)

            assertSoftly {
                baseline.winnerSeat shouldBe 1
                baseline.cleanupConcede shouldBe false
                baseline.promptProgressSamples.any { it.decisionKind == "perform:Cast" } shouldBe true

                snapshot.winnerSeat shouldBe 1
                snapshot.cleanupConcede shouldBe false
                snapshot.promptProgressSamples.any { it.decisionKind == "snapshot:cast" } shouldBe true
                snapshot.promptProgressSamples.any { it.decisionKind == "snapshot:target" } shouldBe true
            }
        }
    })
