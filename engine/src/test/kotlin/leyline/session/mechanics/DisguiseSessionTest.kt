package leyline.session.mechanics

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.getNonManaActivatedAbilities
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

private val DISGUISE_FORUM_FAMILIAR_PUZZLE =
    """
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=4

    humanhand=Forum Familiar
    humanbattlefield=Plains;Plains;Plains;Plains;Plains;Forest
    humanlibrary=Plains;Plains;Plains;Plains
    ailibrary=Forest;Forest;Forest;Forest
    """.trimIndent()

class DisguiseSessionTest :
    SessionTest({
        session(
            "disguise cast emits FaceDown on the battlefield iid immediately",
            puzzle = DISGUISE_FORUM_FAMILIAR_PUZZLE,
            turns = 6,
        ) {
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
            val battlefieldIid = bridge.instanceId(faceDownPermanent)
            val faceDown =
                firstFaceDownGsm!!.persistentAnnotationsList.first { AnnotationType.FaceDown in it.typeList }
            val faceDownIid = faceDown.affectedIdsList.single()

            assertSoftly {
                faceDownIid shouldBe battlefieldIid
                faceDown.affectorId shouldBe faceDownIid
            }
        }

        session(
            "face-down disguise permanent exposes turn-face-up in activation index space",
            puzzle = DISGUISE_FORUM_FAMILIAR_PUZZLE,
            turns = 6,
        ) {
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

        session(
            "printed cast of a disguise card does not use the face-down cast SA",
            puzzle = DISGUISE_FORUM_FAMILIAR_PUZZLE,
            turns = 6,
        ) {
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

        session(
            "Special_TurnFaceUp action flips a face-down disguise permanent",
            puzzle = DISGUISE_FORUM_FAMILIAR_PUZZLE,
            turns = 6,
        ) {
            castSpellByName("Forum Familiar", alternativeGrpId = KeywordAbilityIds.DISGUISE) shouldBe true
            passPriority()
            val faceDown =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isFaceDown }
            val faceDownIid = bridge.instanceId(faceDown)

            session.onPerformAction(
                submitWithGsId(
                    performAction {
                        actionType = ActionType.SpecialTurnFaceUp_add3
                        instanceId = faceDownIid
                    },
                ),
            )
            drainSink()

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
