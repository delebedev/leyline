package leyline.session.mechanics

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.haveManaCost
import leyline.testkit.performAction
import leyline.tooling.headless.ClientAccumulator
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo

private fun cloakPuzzle(topCard: String) =
    """
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Cryptic Coat
    humanbattlefield=Island;Island;Island;Island;Island;Forest
    humanlibrary=$topCard;Plains;Plains
    ailibrary=Forest;Forest;Forest;Forest
    """.trimIndent()

class CloakSessionTest :
    SessionTest({
        session(
            "Cryptic Coat cloaks, attaches, and turns a creature face up for its mana cost",
            puzzle = cloakPuzzle("Centaur Courser"),
            turns = 4,
        ) {
            castSpellByName("Cryptic Coat") shouldBe true
            passUntilResolved()

            val cloaked = human.getZone(ZoneType.Battlefield).cards.single { it.isCloaked }
            val coat = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Cryptic Coat" }
            val cloakedIid = bridge.getOrAllocInstanceId(ForgeCardId(cloaked.id)).value
            val coatIid = bridge.getOrAllocInstanceId(ForgeCardId(coat.id)).value
            val offer =
                allMessages
                    .filter { it.hasActionsAvailableReq() }
                    .flatMap { it.actionsAvailableReq.actionsList }
                    .lastOrNull {
                        it.actionType == ActionType.SpecialTurnFaceUp_add3 && it.instanceId == cloakedIid
                    }
            val objects = latestObjects(cloakedIid, coatIid)
            val cloakedObject = objects.getValue(cloakedIid)
            val coatObject = objects.getValue(coatIid)
            val turnUp = requireNotNull(offer) { "No turn-face-up offer for cloaked creature" }
            val faceDownAnnotation =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .last { AnnotationType.FaceDown in it.typeList && cloakedIid in it.affectedIdsList }

            assertSoftly {
                cloaked.isFaceDown shouldBe true
                cloaked.hasKeyword("Ward:2") shouldBe true
                coat.isAttachedToEntity(cloaked) shouldBe true
                cloakedObject.isFacedown shouldBe true
                cloakedObject.overlayGrpId shouldBe 3
                cloakedObject.cardTypesList shouldContainExactly listOf(CardType.Creature)
                cloakedObject.power.value shouldBe 2
                cloakedObject.toughness.value shouldBe 2
                cloakedObject.uniqueAbilitiesList.single().grpId shouldBe KeywordAbilityIds.WARD_TWO
                coatObject.parentId shouldBe cloakedIid
                faceDownAnnotation.detailsList.associate { it.key to it.valueInt32List.single() } shouldBe
                    mapOf("REASON" to 7, "abilityGrpId" to KeywordAbilityIds.CLOAK)
                turnUp.alternativeGrpId shouldBe KeywordAbilityIds.WARD_TWO
                turnUp.abilityGrpId shouldBe 0
                turnUp should haveManaCost(generic = 2, green = 1)
            }

            session.onPerformAction(submitWithGsId(performAction(turnUp)))
            drainSink()

            assertSoftly {
                cloaked.isFaceDown shouldBe false
                cloaked.name shouldBe "Centaur Courser"
                coat.isAttachedToEntity(cloaked) shouldBe true
            }
        }

        session(
            "cloaked noncreature has no turn-face-up action",
            puzzle = cloakPuzzle("Island"),
            turns = 4,
        ) {
            castSpellByName("Cryptic Coat") shouldBe true
            passUntilResolved()

            val cloaked = human.getZone(ZoneType.Battlefield).cards.single { it.isCloaked }
            val cloakedIid = bridge.getOrAllocInstanceId(ForgeCardId(cloaked.id)).value
            val hasTurnUp =
                allMessages
                    .filter { it.hasActionsAvailableReq() }
                    .flatMap { it.actionsAvailableReq.actionsList }
                    .any { it.actionType == ActionType.SpecialTurnFaceUp_add3 && it.instanceId == cloakedIid }

            assertSoftly {
                cloaked.isFaceDown shouldBe true
                cloaked.hasKeyword("Ward:2") shouldBe true
                hasTurnUp shouldBe false
            }
        }
    }) {
    companion object {
        private fun leyline.tooling.headless.MatchFlowHarness.latestObjects(vararg instanceIds: Int): Map<Int, GameObjectInfo> {
            val wanted = instanceIds.toSet()
            val accumulator = ClientAccumulator()
            allMessages.forEach(accumulator::process)
            return wanted.associateWith { accumulator.objects[it] ?: error("No accumulated object $it") }
        }
    }
}
