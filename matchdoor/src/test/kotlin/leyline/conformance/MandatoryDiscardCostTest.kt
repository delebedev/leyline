package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Mandatory additional cost (discard) -- Mardu Outrider end-to-end.
 *
 * Mardu Outrider costs {1}{B}{B} + discard a card. The engine fires a
 * "choose_cards" prompt via WebCostDecision.visit(CostDiscard). This test
 * verifies:
 * 1. Cast action is offered
 * 2. SelectNReq is sent for discard (not SelectTargetsReq)
 * 3. Client responds -> spell resolves -> 5/5 on battlefield
 * 4. Discarded card goes to graveyard
 */
class MandatoryDiscardCostTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        val puzzleText = """
            [metadata]
            Name:Mandatory Cost - Mardu Outrider
            Goal:Win
            Turns:3
            Difficulty:Tutorial
            Description:Cast Mardu Outrider (discard a card as additional cost).

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=5

            humanhand=Mardu Outrider;Mountain
            humanbattlefield=Swamp;Swamp;Swamp
            humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
        """.trimIndent()

        fun setup(): MatchFlowHarness {
            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)
            return h
        }

        /** Find the instanceId for a card by name from SelectNReq candidates. */
        fun findDiscardCandidate(h: MatchFlowHarness, req: SelectNReq, cardName: String): Int {
            for (iid in req.idsList) {
                val forgeCardId = h.bridge.getForgeCardId(InstanceId(iid)) ?: continue
                val game = h.bridge.getGame() ?: continue
                val card = game.findById(forgeCardId.value) ?: continue
                if (card.name == cardName) return iid
            }
            error("Card '$cardName' not found in SelectNReq candidates: ${req.idsList}")
        }

        /** Cast Mardu Outrider and respond to the discard prompt with Mountain. */
        fun castAndDiscard(h: MatchFlowHarness) {
            h.castSpellByName("Mardu Outrider") shouldBe true

            val req = h.allMessages.last { it.hasSelectNReq() }.selectNReq
            val mountainId = findDiscardCandidate(h, req, "Mountain")
            h.respondToSelectN(listOf(mountainId))

            // Auto-pass resolves the spell and advances through turns.
            if (!h.isGameOver()) {
                h.passPriority()
            }
        }

        test("cast Mardu Outrider produces SelectNReq for discard cost") {
            val h = setup()

            h.castSpellByName("Mardu Outrider") shouldBe true

            val selectNMsg = h.allMessages.lastOrNull { it.hasSelectNReq() }
                ?: error("Expected SelectNReq for discard cost")
            val req = selectNMsg.selectNReq

            assertSoftly {
                req.context shouldBe SelectionContext.Discard_a163
                req.listType shouldBe SelectionListType.Static
                req.optionContext shouldBe OptionContext.Payment
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                req.idsList.size shouldBeGreaterThan 0
            }
        }

        test("discard cost response resolves Mardu Outrider to battlefield") {
            val h = setup()
            castAndDiscard(h)

            val player = h.bridge.getPlayer(SeatId(1))!!
            val bf = player.getZone(ForgeZoneType.Battlefield).cards
            val outriders = bf.filter { it.name == "Mardu Outrider" }
            outriders shouldHaveSize 1

            val outrider = outriders.first()
            outrider.netPower shouldBe 5
            outrider.netToughness shouldBe 5
        }

        test("discarded card goes to graveyard") {
            val h = setup()
            castAndDiscard(h)

            val player = h.bridge.getPlayer(SeatId(1))!!
            val gy = player.getZone(ForgeZoneType.Graveyard).cards
            gy.any { it.name == "Mountain" } shouldBe true
        }

        test("original hand cards consumed by cast") {
            val h = setup()
            castAndDiscard(h)

            val player = h.bridge.getPlayer(SeatId(1))!!
            val hand = player.getZone(ForgeZoneType.Hand).cards
            // Mardu Outrider moved to battlefield, Mountain discarded to graveyard.
            // Hand may contain drawn cards from later turns but not the originals.
            hand.none { it.name == "Mardu Outrider" } shouldBe true
            hand.none { it.name == "Mountain" } shouldBe true
        }

        test("state validity after mandatory discard cost") {
            val h = setup()
            castAndDiscard(h)

            h.accumulator.assertConsistent("after mandatory discard cost")
            assertGsIdChain(h.allMessages, context = "mandatory discard cost flow")
            h.isGameOver().shouldBeFalse()
        }
    })
