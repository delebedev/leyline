package leyline.behavior.mechanics.saga

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.game.mapping.ZoneIds
import leyline.testkit.ClientAccumulator
import leyline.testkit.MatchFlowHarness
import leyline.testkit.TestCardRegistry
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

/** Cast from hand so Forge can make the Saga's entry choice before its chapter triggers. */
class SagaTransformPuzzleTest :
    FunSpec({

        tags(IntegrationTag)

        test("tribute to horobi: cast → 3 chapters → transform end-to-end") {
            val puzzleText =
                """
                [metadata]
                Name:Saga Full Lifecycle — Tribute to Horobi
                Goal:Survive
                Turns:6
                Difficulty:Easy
                Description:Cast Tribute to Horobi, follow runtime horizons through 3 chapters, observe final-chapter exile-return transform.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Tribute to Horobi
                humanbattlefield=Swamp;Swamp;Swamp
                humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp
                aibattlefield=Mountain
                ailibrary=Mountain;Mountain;Mountain;Mountain
                """.trimIndent()

            val harness = MatchFlowHarness()
            try {
                harness.connectAndKeepPuzzleText(puzzleText)
                val game = harness.bridge.getGame()!!

                // Read grpIds from the repo populated by PuzzleCardRegistrar
                // during connectAndKeepPuzzleText. Don't re-derive via
                // CardDataDeriver — it has its own nameToGrpId counter that
                // can diverge from the puzzle-registry counter, producing
                // test-vs-wire grpId mismatches.
                val sagaFrontGrpId =
                    TestCardRegistry.repo.findGrpIdByName("Tribute to Horobi")
                        ?: error("Tribute to Horobi not registered in puzzle repo")
                val echoBackGrpId =
                    TestCardRegistry.repo.findGrpIdByName("Echo of Death's Wail")
                        ?: run {
                            val tribute =
                                game.humanPlayer
                                    .getZone(ZoneType.Hand)
                                    .cards
                                    .first { it.name == "Tribute to Horobi" }
                            error(
                                "Echo of Death's Wail not registered in puzzle repo. " +
                                    "Tribute states=${tribute.states}, currentName=${tribute.name}, " +
                                    "isDoubleFaced=${tribute.isDoubleFaced}, " +
                                    "allRegistered=${TestCardRegistry.repo.findAllGrpIds().map {
                                        TestCardRegistry.repo.findNameByGrpId(
                                            it,
                                        )
                                    }}",
                            )
                        }
                val chapterIIIGrpId =
                    TestCardRegistry.repo
                        .findByGrpId(sagaFrontGrpId)!!
                        .abilityIds[2]
                        .first

                harness.castSpellByName("Tribute to Horobi").shouldBeTrue()

                harness
                    .passUntil(maxPasses = 40) {
                        allMessages.any { message ->
                            message.hasGameStateMessage() &&
                                message.gameStateMessage.gameObjectsList.any {
                                    it.type == GameObjectType.Ability && it.grpId == chapterIIIGrpId
                                }
                        }
                    }.shouldBeTrue()

                val creationIndex =
                    harness.allMessages.indexOfLast { message ->
                        message.hasGameStateMessage() &&
                            message.gameStateMessage.gameObjectsList.any {
                                it.type == GameObjectType.Ability && it.grpId == chapterIIIGrpId
                            }
                    }
                val beforeResolution = harness.allMessages[creationIndex].gameStateMessage
                val beforeAccumulator = ClientAccumulator().apply { processAll(harness.allMessages.take(creationIndex + 1)) }
                val chapter =
                    beforeResolution.gameObjectsList.single {
                        it.type == GameObjectType.Ability && it.grpId == chapterIIIGrpId
                    }
                val sagaIid = chapter.parentId
                assertSoftly {
                    chapter.objectSourceGrpId shouldBe sagaFrontGrpId
                    beforeResolution.annotationsList
                        .single {
                            AnnotationType.AbilityInstanceCreated in it.typeList && it.affectedIdsList.contains(chapter.instanceId)
                        }.affectorId shouldBe sagaIid
                    (sagaIid in beforeAccumulator.zones.getValue(ZoneIds.BATTLEFIELD).objectInstanceIdsList).shouldBeTrue()
                    (chapter.instanceId in beforeAccumulator.zones.getValue(ZoneIds.STACK).objectInstanceIdsList).shouldBeTrue()
                }

                harness
                    .passUntil(maxPasses = 12) {
                        game.humanPlayer
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .any { it.name == "Echo of Death's Wail" }
                    }.shouldBeTrue()

                val transform =
                    harness.allMessages
                        .single { message ->
                            message.hasGameStateMessage() &&
                                message.gameStateMessage.annotationsList.any {
                                    AnnotationType.ObjectIdChanged in it.typeList && it.detailInt("orig_id") == sagaIid
                                }
                        }.gameStateMessage
                val transfers =
                    transform.annotationsList
                        .filter {
                            AnnotationType.ObjectIdChanged in it.typeList || AnnotationType.ZoneTransfer_af5a in it.typeList
                        }.filter {
                            it.typeList.contains(AnnotationType.ObjectIdChanged) ||
                                it.detailString("category") in setOf("Exile", "Return")
                        }
                val exileIid = transfers[0].detailInt("new_id")
                val echoIid = transfers[2].detailInt("new_id")
                assertSoftly {
                    transfers.map { it.typeList.first() } shouldBe
                        listOf(
                            AnnotationType.ObjectIdChanged,
                            AnnotationType.ZoneTransfer_af5a,
                            AnnotationType.ObjectIdChanged,
                            AnnotationType.ZoneTransfer_af5a,
                        )
                    transfers[0].detailInt("orig_id") shouldBe sagaIid
                    transfers[1].affectedIdsList shouldBe listOf(exileIid)
                    transfers[1].detailString("category") shouldBe "Exile"
                    transfers[2].detailInt("orig_id") shouldBe exileIid
                    transfers[3].affectedIdsList shouldBe listOf(echoIid)
                    transfers[3].detailString("category") shouldBe "Return"
                    transform.annotationsList.single {
                        AnnotationType.ResolutionStart in it.typeList && it.affectorId == chapter.instanceId
                    }
                    transform.annotationsList.single {
                        AnnotationType.ResolutionComplete in it.typeList && it.affectorId == chapter.instanceId
                    }
                    transform.annotationsList
                        .single {
                            AnnotationType.AbilityInstanceDeleted in it.typeList && it.affectedIdsList.contains(chapter.instanceId)
                        }.affectorId shouldBe sagaIid
                }

                harness.accumulator.assertConsistent("after saga transform")

                val bfZone =
                    harness.accumulator.zones[ZoneIds.BATTLEFIELD]
                        ?: error("accumulator lost the battlefield zone")
                val bfGrpIds =
                    bfZone.objectInstanceIdsList
                        .mapNotNull { harness.accumulator.objects[it]?.grpId }
                (echoBackGrpId in bfGrpIds).shouldBeTrue()
                (sagaFrontGrpId in bfGrpIds) shouldBe false

                val echoObj =
                    bfZone.objectInstanceIdsList
                        .mapNotNull { harness.accumulator.objects[it] }
                        .first { it.grpId == echoBackGrpId }
                assertSoftly {
                    echoObj.type shouldBe GameObjectType.Card
                    echoObj.instanceId shouldBe echoIid
                    echoObj.othersideGrpId shouldBe sagaFrontGrpId
                    (chapter.instanceId in harness.accumulator.objects) shouldBe false
                }
                harness.playLand("Swamp").shouldBeTrue()
            } finally {
                harness.shutdown()
            }
        }
    })
