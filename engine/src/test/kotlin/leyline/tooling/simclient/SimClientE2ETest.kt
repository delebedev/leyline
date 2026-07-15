package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import leyline.SimClientTag
import leyline.testkit.MatchFlowHarness
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.Step
import java.nio.file.Files
import java.nio.file.Path
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
@Suppress("TierPlacementCheck") // SimClientDriver owns the game-loop interaction exercised here.
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

        test("bolt-face keeps combat-phase noncombat damage inside its resolution GSM") {
            val harness = MatchFlowHarness(seed = 42L)
            val tempLog = Files.createTempFile("simclient-bolt-face-", ".log").toFile()
            val writer = tempLog.bufferedWriter()
            val playerLog = PlayerLogWriter(out = writer, matchId = "simclient-bolt-face")
            try {
                SimClientDriver(
                    harness = harness,
                    log = playerLog,
                    maxTurns = 3,
                    connect = {
                        harness.connectAndKeepPuzzleText(
                            Files.readString(Path.of("../puzzles/bolt-face.pzl")),
                        )
                    },
                ).runOneGame()
                val damageGsms =
                    harness.allMessages
                        .gameStateMessages()
                        .filter { gsm -> gsm.annotationsList.any { AnnotationType.DamageDealt_af5a in it.typeList } }
                damageGsms.shouldHaveSize(1)
                val damageGsm = damageGsms.single()
                val types = damageGsm.annotationsList.flatMap { it.typeList }

                assertSoftly {
                    types shouldContainAll
                        listOf(
                            AnnotationType.ResolutionStart,
                            AnnotationType.DamageDealt_af5a,
                            AnnotationType.ResolutionComplete,
                            AnnotationType.ZoneTransfer_af5a,
                        )
                    damageGsm.annotationsList
                        .single { AnnotationType.DamageDealt_af5a in it.typeList }
                        .detailInt("type") shouldBe 2
                    damageGsm.turnInfo.step shouldNotBe Step.CombatDamage_a2cb
                }
            } finally {
                writer.close()
                runCatching { harness.shutdown() }
            }
        }
    })
