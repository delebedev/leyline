package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.SimClientTag
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.nio.file.Files
import java.time.LocalDateTime

@Suppress("TierPlacementCheck") // SimClientDriver is the game-loop interaction; the custom rule cannot see through it.
class SimClientMutateTest :
    FunSpec({
        tags(SimClientTag)

        test("simclient answers Mutate top choice SelectN prompt") {
            val harness =
                MatchFlowHarness(
                    seed = 42,
                )
            val tempLog = Files.createTempFile("simclient-mutate-", ".log").toFile()
            var fakeNow = LocalDateTime.of(2026, 5, 1, 12, 0, 0)
            val writer = tempLog.bufferedWriter()
            val playerLog =
                PlayerLogWriter(
                    out = writer,
                    matchId = "simclient-mutate",
                    clock = {
                        fakeNow = fakeNow.plusSeconds(1)
                        fakeNow
                    },
                )
            try {
                val driver =
                    SimClientDriver(
                        harness = harness,
                        log = playerLog,
                        maxTurns = 2,
                        maxIterations = 80,
                        connect = { harness.connectAndKeepPuzzleText(MUTATE_PUZZLE) },
                    )
                val stats = driver.runOneGame()
                writer.close()

                val selectNReq = harness.allMessages.firstOrNull { it.type == GREMessageType.SelectNreq }?.selectNReq
                val mergeEffect =
                    harness.allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.persistentAnnotationsList }
                        .lastOrNull {
                            AnnotationType.LayeredEffect in it.typeList &&
                                it.detailInt("abilityGrpId") == KeywordAbilityIds.MUTATE
                        }

                assertSoftly {
                    stats.promptHistogram.keys shouldContain GREMessageType.SelectNreq
                    selectNReq shouldNotBe null
                    selectNReq!!.idsCount shouldBe 2
                    selectNReq.sourceId shouldBe selectNReq.getIds(0)
                    mergeEffect!!.detailInt("isTop") shouldBe 1
                    mergeEffect.detailIntList("abilityGRPIDs") shouldContain 137240
                }
            } finally {
                writer.close()
                harness.shutdown()
            }
        }
    })

private val MUTATE_PUZZLE =
    """
    [metadata]
    Name:Mutate Hemophage
    Goal:Win
    Turns:4
    Difficulty:Easy
    Description:Cast Insatiable Hemophage for mutate onto Runeclaw Bear.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Insatiable Hemophage
    humanbattlefield=Swamp;Swamp;Swamp;Runeclaw Bear
    humanlibrary=Swamp;Swamp;Swamp
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()
