package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Shock land ETB replacement effect — "pay 2 life or enter tapped".
 *
 * Validates: payCostToPreventEffect routes through OptionalActionMessage,
 * life payment works correctly, tapped/untapped state matches decision.
 */
class ShockLandEtbTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        /**
         * Puzzle: Temple Garden in hand, enough life to pay.
         * Human starts at 20 life, Main1.
         */
        fun puzzleText() = """
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

        test("accept — pay 2 life, land enters untapped") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText())

            val human = h.bridge.getPlayer(SeatId(1))!!
            human.life shouldBe 20
            h.phase() shouldBe "MAIN1"

            // Play the shock land — don't use playLand() as it auto-accepts
            val land = human.getZone(ZoneType.Hand).cards.first { it.name == "Temple Garden" }
            val msg = performAction {
                actionType = ActionType.Play_add3
                instanceId = h.bridge.getOrAllocInstanceId(ForgeCardId(land.id)).value
                grpId = h.bridge.cards.findGrpIdByName(land.name) ?: 0
            }
            h.session.onPerformAction(msg)

            // Drain sink to capture OAM (without auto-responding)
            h.allMessages.addAll(h.sink.messages)
            h.allRawMessages.addAll(h.sink.rawMessages)
            h.accumulator.processAll(h.sink.messages)
            h.sink.clear()

            // Verify OAM was sent
            val oam = h.allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            oam shouldBe oam // non-null check implicit in line below
            checkNotNull(oam) { "Expected OptionalActionMessage for shock land" }

            // Accept — pay 2 life
            h.respondToOptionalAction(true)

            // Verify: life=18, Temple Garden on battlefield untapped
            human.life shouldBe 18
            val bf = human.getZone(ZoneType.Battlefield).cards
            val templeGarden = bf.firstOrNull { it.name == "Temple Garden" }
            checkNotNull(templeGarden) { "Temple Garden should be on battlefield" }
            templeGarden.isTapped shouldBe false
        }

        test("decline — land enters tapped, life unchanged") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText())

            val human = h.bridge.getPlayer(SeatId(1))!!
            human.life shouldBe 20

            // Play the shock land manually
            val land = human.getZone(ZoneType.Hand).cards.first { it.name == "Temple Garden" }
            val msg = performAction {
                actionType = ActionType.Play_add3
                instanceId = h.bridge.getOrAllocInstanceId(ForgeCardId(land.id)).value
                grpId = h.bridge.cards.findGrpIdByName(land.name) ?: 0
            }
            h.session.onPerformAction(msg)

            // Drain sink to capture OAM
            h.allMessages.addAll(h.sink.messages)
            h.allRawMessages.addAll(h.sink.rawMessages)
            h.accumulator.processAll(h.sink.messages)
            h.sink.clear()

            // Verify OAM was sent
            checkNotNull(h.allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }) {
                "Expected OptionalActionMessage for shock land"
            }

            // Decline — don't pay life
            h.respondToOptionalAction(false)

            // Verify: life=20, Temple Garden on battlefield tapped
            human.life shouldBe 20
            val bf = human.getZone(ZoneType.Battlefield).cards
            val templeGarden = bf.firstOrNull { it.name == "Temple Garden" }
            checkNotNull(templeGarden) { "Temple Garden should be on battlefield" }
            templeGarden.isTapped shouldBe true
        }
    })
