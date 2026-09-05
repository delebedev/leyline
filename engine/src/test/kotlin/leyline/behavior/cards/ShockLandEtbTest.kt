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
import wotc.mtgo.gre.external.messaging.Messages.Visibility

/**
 * Shock land ETB replacement effect — "pay 2 life or enter tapped".
 *
 * Validates: payCostToPreventEffect routes through OptionalActionMessage,
 * life payment works correctly, tapped/untapped state matches decision.
 */
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

            // Play the shock land — don't use playLand() as it auto-accepts
            val land = human.hand.card("Temple Garden")
            val oldIid = human.hand.iid(land)
            val promptStart = messageSnapshot()
            val msg =
                performAction {
                    actionType = ActionType.Play_add3
                    instanceId = oldIid
                    grpId = bridge.cardRepository.findGrpIdByName(land.name) ?: 0
                }
            send(submitWithGsId(msg))

            // Drain sink to keep OAM (without auto-responding)
            allMessages.addAll(sink.messages)
            allRawMessages.addAll(sink.rawMessages)
            accumulator.processAll(sink.messages)
            sink.clear()

            // Verify OAM was sent
            val oam = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            oam shouldBe oam // non-null check implicit in line below
            checkNotNull(oam) { "Expected OptionalActionMessage for shock land" }
            val promptMessages = messagesSince(promptStart)
            val replacementType = checkNotNull(AnnotationType.forNumber(62))
            val replacement = promptMessages.persistentAnnotationsOfType(replacementType).single()
            val futureIid = replacement.affectedIdsList.single()
            val ghost = promptMessages.firstGameObjectByIid(futureIid)

            assertSoftly {
                oam.prompt.promptId shouldBe PromptIds.SHOCK_LAND_ETB
                oam.prompt.parametersList.map { it.parameterName } shouldContainExactly listOf("CardId")
                oam.prompt.parametersList.map { it.numberValue } shouldContainExactly listOf(futureIid)
                oam.optionalActionMessage.sourceId shouldBe replacement.affectorId
                replacement.detailInt(DetailKeys.GRPID) shouldBe 90846
                replacement.detailInt(DetailKeys.REPLACEMENT_SOURCE_ZCID) shouldBe oldIid
                checkNotNull(ghost).grpId shouldBe 98590
                ghost.visibility shouldBe Visibility.Public
                ghost.zoneId shouldBe 0
            }

            // Accept — pay 2 life
            val responseStart = messageSnapshot()
            respondToOptionalAction(true)
            val responseMessages = messagesSince(responseStart)
            val annotations = responseMessages.allAnnotations()
            val objectIdChanged = annotations.single { AnnotationType.ObjectIdChanged in it.typeList }
            val zoneTransfer = annotations.single { AnnotationType.ZoneTransfer_af5a in it.typeList }
            val syntheticEvent = annotations.single { AnnotationType.SyntheticEvent in it.typeList }
            val modifiedLife = annotations.single { AnnotationType.ModifiedLife in it.typeList }
            val userAction = annotations.single { AnnotationType.UserActionTaken in it.typeList }

            // Verify: life=18, Temple Garden on battlefield untapped
            val bf = human.getZone(ZoneType.Battlefield).cards
            val templeGarden = bf.firstOrNull { it.name == "Temple Garden" }
            checkNotNull(templeGarden) { "Temple Garden should be on battlefield" }
            assertSoftly {
                objectIdChanged.detailInt(DetailKeys.ORIG_ID) shouldBe oldIid
                objectIdChanged.detailInt(DetailKeys.NEW_ID) shouldBe futureIid
                zoneTransfer.affectedIdsList shouldContainExactly listOf(futureIid)
                zoneTransfer.detailString(DetailKeys.CATEGORY) shouldBe "PlayLand"
                syntheticEvent.affectorId shouldBe replacement.affectorId
                syntheticEvent.affectedIdsList shouldContainExactly listOf(HUMAN_SEAT)
                syntheticEvent.detailInt(DetailKeys.TYPE) shouldBe 1
                modifiedLife.affectorId shouldBe replacement.affectorId
                modifiedLife.affectedIdsList shouldContainExactly listOf(HUMAN_SEAT)
                modifiedLife.detailInt(DetailKeys.LIFE) shouldBe -2
                userAction.affectorId shouldBe HUMAN_SEAT
                userAction.affectedIdsList shouldContainExactly listOf(futureIid)
                userAction.detailInt(DetailKeys.ACTION_TYPE) shouldBe ActionType.Play_add3.number
                userAction.detailInt(DetailKeys.ABILITY_GRP_ID) shouldBe 0
                annotations.take(5).map { it.getType(0) } shouldContainExactly
                    listOf(
                        AnnotationType.ObjectIdChanged,
                        AnnotationType.ZoneTransfer_af5a,
                        AnnotationType.SyntheticEvent,
                        AnnotationType.ModifiedLife,
                        AnnotationType.UserActionTaken,
                    )
                responseMessages.deletedPersistentAnnotationIds() shouldContain replacement.id
                human.life shouldBe 18
                templeGarden.isTapped shouldBe false
            }
        }

        session("decline — land enters tapped, life unchanged", puzzle = puzzleText()) {
            human.life shouldBe 20

            // Play the shock land manually
            val land = human.hand.card("Temple Garden")
            val msg =
                performAction {
                    actionType = ActionType.Play_add3
                    instanceId = human.hand.iid(land)
                    grpId = bridge.cardRepository.findGrpIdByName(land.name) ?: 0
                }
            send(submitWithGsId(msg))

            // Drain sink to keep OAM
            allMessages.addAll(sink.messages)
            allRawMessages.addAll(sink.rawMessages)
            accumulator.processAll(sink.messages)
            sink.clear()

            // Verify OAM was sent
            val oam =
                checkNotNull(allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }) {
                    "Expected OptionalActionMessage for shock land"
                }
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
