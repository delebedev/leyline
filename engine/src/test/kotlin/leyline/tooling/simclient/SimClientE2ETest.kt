package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.SimClientTag
import leyline.testkit.MatchFlowHarness
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.Step
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the simclient-specific ordering assertion that is not owned by a
 * mechanic or mapper test.
 */
@Suppress("TierPlacementCheck") // SimClientDriver owns the game-loop interaction exercised here.
class SimClientE2ETest :
    FunSpec({
        tags(SimClientTag)

        test("bolt-face orders noncombat damage inside its resolution lifecycle before stack exit") {
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
                    types shouldBe
                        listOf(
                            AnnotationType.ResolutionStart,
                            AnnotationType.DamageDealt_af5a,
                            AnnotationType.SyntheticEvent,
                            AnnotationType.ModifiedLife,
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
