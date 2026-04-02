package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.bridge.InteractivePromptBridge
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Discard subsystem — both discard-as-cost (spell additional cost)
 * and cleanup discard (hand size enforcement).
 *
 * Board-level discard annotation tests would go in a SubsystemTest file.
 */
class DiscardInteractionTest :
    InteractionTest({

        // --- Discard-as-cost (Mardu Outrider: {1}{B}{B} + discard a card) ---

        val marduPuzzle = """
            [metadata]
            Name:Mandatory Cost - Mardu Outrider
            Goal:Win
            Turns:2

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

        test("discard-as-cost — SelectNReq proto shape") {
            startPuzzle(marduPuzzle)

            castSpellByName("Mardu Outrider") shouldBe true

            val req = harness.allMessages.last { it.hasSelectNReq() }.selectNReq
            assertSoftly {
                req.context shouldBe SelectionContext.Discard_a163
                req.listType shouldBe SelectionListType.Static
                req.optionContext shouldBe OptionContext.Payment
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                req.idsList shouldHaveSize 1
            }
        }

        test("discard-as-cost — spell resolves after responding") {
            startPuzzle(marduPuzzle)

            castSpellByName("Mardu Outrider") shouldBe true
            val req = harness.allMessages.last { it.hasSelectNReq() }.selectNReq
            val mountainId = findInstanceId(req.idsList, "Mountain")
            harness.respondToSelectN(listOf(mountainId))
            passPriority()

            val player = human
            assertSoftly {
                // Outrider on battlefield
                val outriders = player.getZone(ForgeZoneType.Battlefield).cards
                    .filter { it.name == "Mardu Outrider" }
                outriders shouldHaveSize 1
                outriders.first().netPower shouldBe 5
                outriders.first().netToughness shouldBe 5

                // Discarded Mountain in graveyard
                player.getZone(ForgeZoneType.Graveyard).cards
                    .any { it.name == "Mountain" } shouldBe true

                // Original hand cards consumed
                val hand = player.getZone(ForgeZoneType.Hand).cards
                hand.none { it.name == "Mardu Outrider" } shouldBe true
                hand.none { it.name == "Mountain" } shouldBe true
            }

            harness.accumulator.assertConsistent("after mandatory discard cost")
            assertGsIdChain(harness.allMessages, context = "mandatory discard cost flow")
        }

        // --- Cleanup discard (hand exceeds max hand size) ---

        // TODO: cleanup discard is currently auto-resolved by TargetingHandler
        // (picks first card). Real Arena sends a SelectNReq letting the player
        // choose which card to discard. When we implement interactive cleanup
        // discard, this test should assert the prompt and respond explicitly.
        test("cleanup discard — hand size enforced at end of turn") {
            startPuzzle(
                """
            [metadata]
            Name:Cleanup Discard
            Goal:Win
            Turns:2

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Divination;Island;Island;Island;Island;Island;Island
            humanbattlefield=Island;Island;Island
            humanlibrary=Island;Island;Island;Island;Island
            aibattlefield=Centaur Courser
            ailibrary=Island;Island;Island;Island;Island
                """.trimIndent(),
            )

            val player = human
            player.getZone(ForgeZoneType.Hand).size() shouldBe 7

            // Cast Divination (draw 2): hand 7 → 6 (on stack) → resolve → 8
            castSpellByName("Divination") shouldBe true
            // One pass resolves Divination: hand 6 + 2 drawn = 8
            passPriority()
            player.getZone(ForgeZoneType.Hand).size() shouldBe 8

            // Pass through to cleanup where hand size is enforced (8 → 7)
            passUntil(maxPasses = 10) {
                player.getZone(ForgeZoneType.Hand).size() <= 7
            }

            player.getZone(ForgeZoneType.Hand).size() shouldBe 7
            // Divination (resolved) + 1 discarded card
            player.getZone(ForgeZoneType.Graveyard).size() shouldBe 2

            // Verify the discard prompt was answered via the bridge
            val discardPrompts = harness.bridge.promptBridge(1).history
                .filter { it.message.contains("iscard", ignoreCase = true) }
            discardPrompts shouldHaveSize 1
            discardPrompts.first().outcome shouldBe InteractivePromptBridge.PromptOutcome.RESPONDED
        }
    })
