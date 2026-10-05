package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.SimClientTag
import leyline.game.generator.PuzzleSource
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import leyline.tooling.artifact.SyntheticArtifactWriter
import leyline.tooling.headless.HeadlessResponseMode
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.Step
import java.nio.file.Files

/**
 * Verifies simclient response routing and ordering through the shared runtime.
 */
@Suppress("TierPlacementCheck") // SimClientDriver owns the game-loop interaction exercised here.
class SimClientE2ETest :
    FunSpec({
        tags(SimClientTag)

        for (discovered in listOf("Lightning Bolt", "Sage of the Skies")) {
            test("discovered $discovered continues through its cast work") {
                val harness = MatchFlowHarness(seed = 42L, responseMode = HeadlessResponseMode.PolicyVisible)
                val tempLog = Files.createTempFile("simclient-discover-", ".log").toFile()
                val writer = tempLog.bufferedWriter()
                val playerLog = SyntheticArtifactWriter(out = writer, matchId = "simclient-discover")
                try {
                    val stats =
                        SimClientDriver(
                            harness = harness,
                            log = playerLog,
                            maxTurns = 2,
                            connect = {
                                harness.connectAndKeepPuzzleText(
                                    PuzzleSource
                                        .definitionFromResource("data/puzzles/discover-geological-appraiser.pzl")
                                        .content
                                        .replace("Llanowar Elves", discovered),
                                )
                            },
                        ).runOneGame()

                    stats.completionReason shouldBe "max-turns"
                    harness.allMessages.any {
                        it.hasActionsAvailableReq() && it.prompt.promptId == PromptIds.FREE_CAST_FROM_REVEAL
                    } shouldBe true
                    val states = harness.allMessages.gameStateMessages()
                    if (discovered == "Lightning Bolt") {
                        harness.allMessages.any { it.hasSelectTargetsReq() } shouldBe true
                        states.flatMap { it.annotationsList }.count {
                            AnnotationType.DamageDealt_af5a in it.typeList
                        } shouldBe 1
                    } else {
                        val grpId = harness.bridge.cardRepository.findGrpIdByName(discovered)
                        states
                            .flatMap { it.gameObjectsList }
                            .filter {
                                it.type in setOf(GameObjectType.Card, GameObjectType.Token) &&
                                    it.grpId == grpId &&
                                    it.zoneId == ZoneIds.BATTLEFIELD
                            }.map { it.instanceId }
                            .distinct()
                            .size shouldBe 2
                    }
                } finally {
                    writer.close()
                    runCatching { harness.shutdown() }
                }
            }
        }

        test("grouped target decisions advance each target group before submit") {
            val harness = MatchFlowHarness(seed = 42L)
            val tempLog = Files.createTempFile("simclient-grouped-targets-", ".log").toFile()
            val writer = tempLog.bufferedWriter()
            val playerLog = SyntheticArtifactWriter(out = writer, matchId = "simclient-grouped-targets")
            try {
                val stats =
                    SimClientDriver(
                        harness = harness,
                        log = playerLog,
                        maxTurns = 2,
                        connect = {
                            harness.connectAndKeepPuzzleText(
                                PuzzleSource.definitionFromResource("data/puzzles/bite-down.pzl").content,
                            )
                        },
                    ).runOneGame()

                val targetIndices =
                    harness.allMessages
                        .filter { it.hasSelectTargetsReq() }
                        .flatMap { it.selectTargetsReq.targetsList.map { selection -> selection.targetIdx } }
                        .distinct()
                targetIndices shouldBe listOf(1, 2)
                stats.completionReason shouldBe "max-turns"
            } finally {
                writer.close()
                runCatching { harness.shutdown() }
            }
        }

        test("bolt-face orders noncombat damage inside its resolution lifecycle before stack exit") {
            val harness = MatchFlowHarness(seed = 42L)
            val tempLog = Files.createTempFile("simclient-bolt-face-", ".log").toFile()
            val writer = tempLog.bufferedWriter()
            val playerLog = SyntheticArtifactWriter(out = writer, matchId = "simclient-bolt-face")
            try {
                SimClientDriver(
                    harness = harness,
                    log = playerLog,
                    maxTurns = 3,
                    connect = {
                        harness.connectAndKeepPuzzleText(
                            PuzzleSource.definitionFromResource("data/puzzles/bolt-face.pzl").content,
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
                            AnnotationType.ObjectIdChanged,
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
