package leyline.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.string.shouldContain
import leyline.SimClientTag
import leyline.game.bundle.InvariantCheck
import leyline.game.bundle.InvariantSelection
import leyline.testkit.MatchFlowHarness
import java.nio.file.Files
import java.time.LocalDateTime

/**
 * v0 end-to-end smoke for the simclient. Both variants drive a full game
 * through MatchSession + GameBridge + Forge engine, write a Player.log,
 * and assert the log shape.
 *
 * - mono-Forest: pipeline-only (no combat). Validates non-combat wire.
 * - vanilla-creatures: 24 Forest + 36 Grizzly Bears mirror. Forces combat
 *   prompts so DeclareAttackers/Blockers branches in the driver are live.
 */
class SimClientE2ETest :
    FunSpec({
        tags(SimClientTag)

        fun runOneGame(
            deck: String,
            seed: Long,
            tag: String,
            maxTurns: Int = 30,
        ): java.io.File {
            val harness =
                MatchFlowHarness(
                    seed = seed,
                    deckList = deck,
                    validation =
                        InvariantSelection.except(
                            "simclient driver can replay older queued ids around play-land diffs (leyline-qiws)",
                            InvariantCheck.GsIdMonotonicity,
                            InvariantCheck.MsgIdMonotonicity,
                            InvariantCheck.GsIdPrevKnown,
                        ),
                )
            val tempLog = Files.createTempFile("simclient-$tag-", ".log").toFile()
            var fakeNow = LocalDateTime.of(2026, 5, 1, 12, 0, 0)
            val writer = tempLog.bufferedWriter()
            val playerLog =
                PlayerLogWriter(
                    out = writer,
                    matchId = "simclient-$tag",
                    clock = {
                        fakeNow = fakeNow.plusSeconds(1)
                        fakeNow
                    },
                )
            val driver = SimClientDriver(harness, playerLog, maxTurns = maxTurns)
            driver.runOneGame()
            writer.close()
            val finalTurn = runCatching { harness.turn() }.getOrNull() ?: -1
            println("SimClientE2ETest [$tag] log: ${tempLog.absolutePath} (${tempLog.length()} bytes), turn=$finalTurn")
            return tempLog
        }

        test("mono-Forest mirror — pipeline smoke") {
            val log = runOneGame(deck = "60 Forest", seed = 42L, tag = "forest")
            val contents = log.readText()
            assertSoftly {
                contents.length shouldBeGreaterThan 0
                contents shouldContain "[UnityCrossThreadLogger]"
                contents shouldContain "greToClientEvent"
            }
        }

        test("vanilla-creatures mirror — exercises combat") {
            val deck =
                """
                24 Forest
                36 Grizzly Bears
                """.trimIndent()
            val log = runOneGame(deck = deck, seed = 42L, tag = "bears", maxTurns = 20)
            val contents = log.readText()
            assertSoftly {
                contents.length shouldBeGreaterThan 0
                // Combat prompts should fire when both seats run creatures.
                contents shouldContain "DeclareAttackersReq"
            }
        }
    })
