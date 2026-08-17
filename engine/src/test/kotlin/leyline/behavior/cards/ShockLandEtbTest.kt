package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

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

        session("accept — pay 2 life, land enters untapped", puzzle = puzzleText(), validating = true) {
            human.life shouldBe 20
            phase() shouldBe "MAIN1"

            // Play the shock land — don't use playLand() as it auto-accepts
            val land = human.getZone(ZoneType.Hand).cards.first { it.name == "Temple Garden" }
            val msg =
                performAction {
                    actionType = ActionType.Play_add3
                    instanceId = human.hand.iid(land)
                    grpId = bridge.cardRepository.findGrpIdByName(land.name) ?: 0
                }
            session.onPerformAction(submitWithGsId(msg))

            // Drain sink to keep OAM (without auto-responding)
            allMessages.addAll(sink.messages)
            allRawMessages.addAll(sink.rawMessages)
            accumulator.processAll(sink.messages)
            sink.clear()

            // Verify OAM was sent
            val oam = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            oam shouldBe oam // non-null check implicit in line below
            checkNotNull(oam) { "Expected OptionalActionMessage for shock land" }

            // Accept — pay 2 life
            respondToOptionalAction(true)

            // Verify: life=18, Temple Garden on battlefield untapped
            human.life shouldBe 18
            val bf = human.getZone(ZoneType.Battlefield).cards
            val templeGarden = bf.firstOrNull { it.name == "Temple Garden" }
            checkNotNull(templeGarden) { "Temple Garden should be on battlefield" }
            templeGarden.isTapped shouldBe false
        }

        session("decline — land enters tapped, life unchanged", puzzle = puzzleText(), validating = true) {
            human.life shouldBe 20

            // Play the shock land manually
            val land = human.getZone(ZoneType.Hand).cards.first { it.name == "Temple Garden" }
            val msg =
                performAction {
                    actionType = ActionType.Play_add3
                    instanceId = human.hand.iid(land)
                    grpId = bridge.cardRepository.findGrpIdByName(land.name) ?: 0
                }
            session.onPerformAction(submitWithGsId(msg))

            // Drain sink to keep OAM
            allMessages.addAll(sink.messages)
            allRawMessages.addAll(sink.rawMessages)
            accumulator.processAll(sink.messages)
            sink.clear()

            // Verify OAM was sent
            checkNotNull(allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }) {
                "Expected OptionalActionMessage for shock land"
            }

            // Decline — don't pay life
            respondToOptionalAction(false)

            // Verify: life=20, Temple Garden on battlefield tapped
            human.life shouldBe 20
            val bf = human.getZone(ZoneType.Battlefield).cards
            val templeGarden = bf.firstOrNull { it.name == "Temple Garden" }
            checkNotNull(templeGarden) { "Temple Garden should be on battlefield" }
            templeGarden.isTapped shouldBe true
        }
    })
