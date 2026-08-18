package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.SimClientTag
import leyline.bridge.types.SeatId
import leyline.copilot.ForgeAiPolicy
import leyline.testkit.MatchFlowHarness
import java.nio.file.Files
import java.nio.file.Path

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
                    log = PlayerLogWriter(writer, "snapshot-prompt-driver"),
                    maxTurns = 2,
                    connect = {
                        harness.connectAndKeepPuzzleText(
                            Files.readString(Path.of("../puzzles/$puzzle")),
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

        test("Forge AI and snapshot consult cast the exact-lethal line") {
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

        test("Forge AI and snapshot consult make the forced block") {
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

        test("Forge AI and snapshot consult preserve the Adventure cast rail") {
            val baseline = runPuzzle("smaug-spew-flame-lethal.pzl", SimClientPolicyMode.ForgeAi)
            val snapshot = runPuzzle("smaug-spew-flame-lethal.pzl", SimClientPolicyMode.Snapshot)

            assertSoftly {
                baseline.winnerSeat shouldBe 1
                baseline.promptProgressSamples.first().decisionKind shouldBe "perform:CastAdventure"

                snapshot.winnerSeat shouldBe 1
                snapshot.promptProgressSamples.first().decisionKind shouldBe "snapshot:cast_adventure"
                snapshot.promptProgressSamples.any { it.decisionKind == "snapshot:target" } shouldBe true
            }
        }

        test("Forge AI and snapshot consult preserve an Omen cast offer through the generic matcher") {
            val baseline = runPuzzle("omen-signaling-roar-lethal.pzl", SimClientPolicyMode.ForgeAi)
            val snapshot = runPuzzle("omen-signaling-roar-lethal.pzl", SimClientPolicyMode.Snapshot)

            assertSoftly {
                baseline.winnerSeat shouldBe 1
                baseline.promptProgressSamples.any { it.decisionKind == "perform:CastOmen" } shouldBe true

                snapshot.winnerSeat shouldBe 1
                snapshot.promptProgressSamples.any { it.decisionKind == "snapshot:cast_omen" } shouldBe true
            }
        }
    })
