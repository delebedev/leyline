package leyline.session.mechanics

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class DisguiseSessionTest :
    SessionTest({
        test("disguise cast emits FaceDown on the battlefield iid immediately") {
            startDisguisePuzzle()

            val before = messageSnapshot()
            castSpellByName("Forum Familiar", alternativeGrpId = KeywordAbilityIds.DISGUISE) shouldBe true
            passPriority()

            val firstFaceDownGsm =
                gameStateMessagesSince(before).firstOrNull { gsm ->
                    gsm.persistentAnnotationsList.any { AnnotationType.FaceDown in it.typeList }
                }
            firstFaceDownGsm shouldNotBe null

            val faceDownPermanent =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isFaceDown }
            val battlefieldIid = harness.bridge.getOrAllocInstanceId(ForgeCardId(faceDownPermanent.id)).value
            val faceDown =
                firstFaceDownGsm!!.persistentAnnotationsList.first { AnnotationType.FaceDown in it.typeList }
            val faceDownIid = faceDown.affectedIdsList.single()

            assertSoftly {
                faceDownIid shouldBe battlefieldIid
                faceDown.affectorId shouldBe faceDownIid
            }
        }

        test("face-down disguise permanent exposes turn-face-up in activation index space") {
            startDisguisePuzzle()

            castSpellByName("Forum Familiar", alternativeGrpId = KeywordAbilityIds.DISGUISE) shouldBe true
            passPriority()

            val faceDown =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .firstOrNull { it.isFaceDown }
            faceDown shouldNotBe null

            val turnFaceUpAbilities =
                getNonManaActivatedAbilities(faceDown!!, human)
                    .filter { it.isDisguiseUp }
            turnFaceUpAbilities shouldHaveSize 1
        }

        test("printed cast of a disguise card does not use the face-down cast SA") {
            startDisguisePuzzle()

            castSpellByName("Forum Familiar") shouldBe true
            passPriority()

            val familiar =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Forum Familiar" }

            assertSoftly {
                familiar.isFaceDown shouldBe false
                familiar.netPower shouldBe 1
                familiar.netToughness shouldBe 1
            }
        }

        test("Special_TurnFaceUp action flips a face-down disguise permanent") {
            startDisguisePuzzle()

            castSpellByName("Forum Familiar", alternativeGrpId = KeywordAbilityIds.DISGUISE) shouldBe true
            passPriority()
            val faceDown =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isFaceDown }
            val faceDownIid = harness.bridge.getOrAllocInstanceId(ForgeCardId(faceDown.id)).value

            harness.session.onPerformAction(
                harness.submitWithGsId(
                    performAction {
                        actionType = ActionType.SpecialTurnFaceUp_add3
                        instanceId = faceDownIid
                    },
                ),
            )
            harness.drainSink()

            val familiar =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.id == faceDown.id }

            assertSoftly {
                familiar.isFaceDown shouldBe false
                familiar.netPower shouldBe 1
                familiar.netToughness shouldBe 1
                allMessages.any { it.hasSelectTargetsReq() } shouldBe true
            }
        }
    })

private fun SessionTest.startDisguisePuzzle() {
    startPuzzle(
        """
        ActivePlayer=Human
        ActivePhase=Main1
        HumanLife=20
        AILife=4

        humanhand=Forum Familiar
        humanbattlefield=Plains;Plains;Plains;Plains;Plains;Forest
        humanlibrary=Plains;Plains;Plains;Plains
        ailibrary=Forest;Forest;Forest;Forest
        """.trimIndent(),
        name = "Disguise Forum Familiar",
        turns = 6,
        validating = false,
    )
}
