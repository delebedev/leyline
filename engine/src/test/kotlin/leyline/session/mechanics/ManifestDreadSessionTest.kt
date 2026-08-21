package leyline.session.mechanics

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.DetailKeys
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.allActions
import leyline.testkit.detailInt
import leyline.testkit.haveManaCost
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType

private fun manifestDreadPuzzle(library: String) =
    """
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Unnerving Grasp
    humanbattlefield=Island;Island;Island;Forest;Forest;Forest;Forest;Plains
    humanlibrary=$library;Plains;Plains
    aibattlefield=Grizzly Bears
    ailibrary=Forest;Forest;Forest;Forest
    """.trimIndent()

private fun MatchFlowHarness.castUnnervingGraspUntilSelectN(): SelectNReq {
    val targetIid = ai.battlefield.iid("Grizzly Bears")
    castSpellByName("Unnerving Grasp") shouldBe true
    selectTargets(listOf(targetIid))
    passUntil(maxPasses = 4) { allMessages.any { it.hasSelectNReq() } }
    return allMessages.lastOrNull { it.hasSelectNReq() }?.selectNReq
        ?: error(
            "No SelectNReq; messages=${allMessages.takeLast(30).map { it.type }}",
        )
}

class ManifestDreadSessionTest :
    SessionTest({
        session(
            "Manifest Dread choice, hidden permanent, annotation, graveyard, and turn-up action",
            puzzle = manifestDreadPuzzle("Centaur Courser;Island"),
            turns = 4,
        ) {
            val req = castUnnervingGraspUntilSelectN()
            val creatureIid = findInstanceId(req.idsList, "Centaur Courser")
            val islandIid = findInstanceId(req.idsList, "Island")
            val promptMessage = allMessages.last { it.hasSelectNReq() }

            assertSoftly {
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                req.context shouldBe SelectionContext.Resolution_a163
                req.optionContext shouldBe OptionContext.Resolution_a9d7
                req.listType shouldBe SelectionListType.Dynamic
                req.idType shouldBe IdType.InstanceId_ab2c
                req.idsList shouldContainExactly req.unfilteredIdsList
                req.sourceId shouldNotBe 0
                req.prompt.parametersList
                    .single()
                    .promptId shouldBe PromptIds.MANIFEST_DREAD_INNER_PARAMETER
                promptMessage.prompt.promptId shouldBe PromptIds.MANIFEST_DREAD
                promptMessage.prompt.parametersList.map { it.type } shouldContainExactly
                    listOf(ParameterType.Number, ParameterType.Number)
                promptMessage.prompt.parametersList.map { it.numberValue } shouldContainExactly
                    listOf(req.sourceId, 1)
                promptMessage.allowCancel shouldBe AllowCancel.No_a526
            }

            respondToSelectN(listOf(creatureIid))
            passUntilResolved()

            val manifested = human.getZone(ZoneType.Battlefield).cards.single { it.isFaceDown }
            val manifestedIid = bridge.instanceId(manifested)
            val islandForgeId =
                bridge.getForgeCardId(leyline.bridge.types.InstanceId(islandIid))?.value
                    ?: error("No Forge card id for unselected Island iid=$islandIid")
            val faceDown =
                allMessages
                    .flatMap { if (it.hasGameStateMessage()) it.gameStateMessage.persistentAnnotationsList else emptyList() }
                    .last { AnnotationType.FaceDown in it.typeList && manifestedIid in it.affectedIdsList }
            val turnUp =
                allMessages
                    .allActions()
                    .lastOrNull {
                        it.actionType == ActionType.SpecialTurnFaceUp_add3 && it.instanceId == manifestedIid
                    } ?: error("No active turn-up action for manifested creature")

            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards.map { it.id } shouldContain islandForgeId
                faceDown.detailInt(DetailKeys.REASON_UPPER) shouldBe AnnotationConstants.FACEDOWN_REASON_MANIFEST_DREAD
                faceDown.detailInt(DetailKeys.ABILITY_GRP_ID) shouldBe KeywordAbilityIds.MANIFEST_DREAD
                turnUp.alternativeGrpId shouldBe KeywordAbilityIds.MANIFEST_DREAD
                turnUp.abilityGrpId shouldBe 0
                turnUp should haveManaCost(generic = 2, green = 1)
            }

            session.onPerformAction(
                submitWithGsId(
                    performAction(turnUp),
                ),
            )
            drainSink()

            assertSoftly {
                manifested.isFaceDown shouldBe false
                manifested.name shouldBe "Centaur Courser"
                manifested.netPower shouldBe 3
                manifested.netToughness shouldBe 3
            }
        }

        session(
            "manifested noncreature has no turn-face-up action",
            puzzle = manifestDreadPuzzle("Island;Grizzly Bears"),
            turns = 4,
        ) {
            val req = castUnnervingGraspUntilSelectN()
            val islandIid = findInstanceId(req.idsList, "Island")
            respondToSelectN(listOf(islandIid))
            passUntilResolved()

            val manifested = human.getZone(ZoneType.Battlefield).cards.single { it.isFaceDown }
            val manifestedIid = bridge.instanceId(manifested)
            val turnUp =
                allMessages
                    .allActions()
                    .any { it.actionType == ActionType.SpecialTurnFaceUp_add3 && it.instanceId == manifestedIid }

            turnUp shouldBe false
        }
    })
