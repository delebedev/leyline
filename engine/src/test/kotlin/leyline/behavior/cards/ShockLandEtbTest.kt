package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.game.mapping.PromptIds
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Shock land ETB replacement effect — "pay 2 life or enter tapped".
 *
 * Validates: payCostToPreventEffect routes through OptionalActionMessage,
 * life payment works correctly, tapped/untapped state matches decision.
 */
@FixturePinned
class ShockLandEtbTest :
    SessionTest({

        /**
         * Puzzle: Temple Garden in hand, enough life to pay.
         * Human starts at 20 life, Main1.
         */
        fun puzzleText() =
            """
            [metadata]
            Name:Shock Land ETB
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Test shock land ETB replacement.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Temple Garden
            humanlibrary=Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        session("accept — pay 2 life, land enters untapped", puzzle = puzzleText()) {
            human.life shouldBe 20
            phase() shouldBe "MAIN1"

            val oldIid = human.hand.iid("Temple Garden")
            val promptStart = messageSnapshot()
            playShockLandUntilChoice()
            val promptMessages = messagesSince(promptStart)
            val replacementType = checkNotNull(AnnotationType.forNumber(62))
            val replacement = promptMessages.persistentAnnotationsOfType(replacementType).single()
            val futureIid = replacement.affectedIdsList.single()
            val ghost = promptMessages.firstGameObjectByIid(futureIid)

            assertSoftly {
                replacement.detailInt(DetailKeys.GRPID) shouldBe 90846
                replacement.detailInt(DetailKeys.REPLACEMENT_SOURCE_ZCID) shouldBe oldIid
                checkNotNull(ghost).grpId shouldBe 98590
            }

            // Accept — pay 2 life
            val responseStart = messageSnapshot()
            respondToOptionalAction(true)
            val annotations = messagesSince(responseStart).allAnnotations()
            val expectedTypes =
                listOf(
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.ZoneTransfer_af5a,
                    AnnotationType.SyntheticEvent,
                    AnnotationType.ModifiedLife,
                    AnnotationType.UserActionTaken,
                )
            // Verify: life=18, Temple Garden on battlefield untapped
            val bf = human.getZone(ZoneType.Battlefield).cards
            val templeGarden = bf.firstOrNull { it.name == "Temple Garden" }
            checkNotNull(templeGarden) { "Temple Garden should be on battlefield" }
            assertSoftly {
                annotations.map { it.getType(0) }.filter { it in expectedTypes } shouldContainExactly expectedTypes
                for (type in expectedTypes) annotations.count { type in it.typeList } shouldBe 1
                human.life shouldBe 18
                templeGarden.isTapped shouldBe false
            }
        }

        session("decline — land enters tapped, life unchanged", puzzle = puzzleText()) {
            human.life shouldBe 20

            val oam = playShockLandUntilChoice()
            val replacementType = checkNotNull(AnnotationType.forNumber(62))
            val replacement = allMessages.persistentAnnotationsOfType(replacementType).single()
            oam.prompt.promptId shouldBe PromptIds.SHOCK_LAND_ETB

            // Decline — don't pay life
            val responseStart = messageSnapshot()
            respondToOptionalAction(false)
            val responseMessages = messagesSince(responseStart)

            // Verify: life=20, Temple Garden on battlefield tapped
            val bf = human.getZone(ZoneType.Battlefield).cards
            val templeGarden = bf.firstOrNull { it.name == "Temple Garden" }
            checkNotNull(templeGarden) { "Temple Garden should be on battlefield" }
            assertSoftly {
                responseMessages.annotationsOfType(AnnotationType.SyntheticEvent).size shouldBe 0
                responseMessages.annotationsOfType(AnnotationType.ModifiedLife).size shouldBe 0
                responseMessages.deletedPersistentAnnotationIds() shouldContain replacement.id
                human.life shouldBe 20
                templeGarden.isTapped shouldBe true
            }
        }
    })

private fun MatchFlowHarness.playShockLandUntilChoice(): GREToClientMessage {
    val land = human.hand.card("Temple Garden")
    val msg =
        performAction {
            actionType = ActionType.Play_add3
            instanceId = human.hand.iid(land)
            grpId = bridge.cardRepository.findGrpIdByName(land.name) ?: 0
        }
    send(submitWithGsId(msg))
    allMessages.addAll(sink.messages)
    allRawMessages.addAll(sink.rawMessages)
    accumulator.processAll(sink.messages)
    sink.clear()
    return checkNotNull(allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }) {
        "Expected OptionalActionMessage for shock land"
    }
}
