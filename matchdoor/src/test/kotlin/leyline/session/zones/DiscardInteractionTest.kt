package leyline.session.zones

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest
import leyline.testkit.assertGsIdChain
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Discard subsystem — both discard-as-cost (spell additional cost)
 * and cleanup discard (hand size enforcement).
 *
 * Board-level discard annotation tests would go in a BoardTest file.
 */
class DiscardInteractionTest :
    SessionTest({

        // --- Discard-as-cost (Mardu Outrider: {1}{B}{B} + discard a card) ---

        val marduState =
            """
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
            startPuzzle(marduState, name = "Mardu Outrider", turns = 2)

            castSpellByName("Mardu Outrider") shouldBe true

            val req = lastSelectNReq()
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
            startPuzzle(marduState, name = "Mardu Outrider", turns = 2)

            castSpellByName("Mardu Outrider") shouldBe true
            val req = lastSelectNReq()
            val mountainId = findInstanceId(req.idsList, "Mountain")
            respondToSelectN(listOf(mountainId))
            passPriority()

            assertSoftly {
                // Outrider on battlefield
                val outriders =
                    human
                        .getZone(ForgeZoneType.Battlefield)
                        .cards
                        .filter { it.name == "Mardu Outrider" }
                outriders shouldHaveSize 1
                outriders.first().netPower shouldBe 5
                outriders.first().netToughness shouldBe 5

                // Discarded Mountain in graveyard — exactly one
                human
                    .getZone(ForgeZoneType.Graveyard)
                    .cards
                    .filter { it.name == "Mountain" } shouldHaveSize 1

                // Original hand cards consumed — hand empty (started with 2, both gone)
                human.getZone(ForgeZoneType.Hand).cards shouldHaveSize 0

                assertAccumulatorConsistent("after mandatory discard cost")
                assertGsIdChain(allMessages, context = "mandatory discard cost flow")
            }
        }

        test("targeted reveal discard emits SelectNReq") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Duress
                humanbattlefield=Swamp
                humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp
                aihand=Divination;Walking Corpse;Swamp
                ailibrary=Island;Island;Island;Island;Island
                """,
                name = "Duress reveal discard",
                turns = 2,
            )

            castSpellByName("Duress") shouldBe true
            passPriority()

            val req = lastSelectNReq()
            val divinationId = findInstanceId(req.idsList, "Divination")
            assertSoftly {
                req.context shouldBe SelectionContext.Resolution_a163
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                req.idsList shouldHaveSize 1
            }

            respondToSelectN(listOf(divinationId))

            ai
                .getZone(ForgeZoneType.Graveyard)
                .cards
                .filter { it.name == "Divination" } shouldHaveSize 1
        }

        // --- Cleanup discard (hand exceeds max hand size) ---

        // TODO: cleanup discard is currently auto-resolved by TargetingHandler
        // (picks first card). Real Arena sends a SelectNReq letting the player
        // choose which card to discard. When we implement interactive cleanup
        // discard, this test should assert the prompt and respond explicitly.
        test("cleanup discard — hand size enforced at end of turn") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Divination;Island;Island;Island;Island;Island;Island
                humanbattlefield=Island;Island;Island
                humanlibrary=Island;Island;Island;Island;Island
                aibattlefield=Centaur Courser
                ailibrary=Island;Island;Island;Island;Island
                """,
                name = "Cleanup Discard",
                turns = 2,
            )

            human.getZone(ForgeZoneType.Hand).size() shouldBe 7

            // Cast Divination (draw 2): hand 7 → 6 (on stack) → resolve → 8
            castSpellByName("Divination") shouldBe true
            // One pass resolves Divination: hand 6 + 2 drawn = 8
            passPriority()
            human.getZone(ForgeZoneType.Hand).size() shouldBe 8

            // Pass through to cleanup where hand size is enforced (8 → 7)
            passUntil(maxPasses = 10) {
                human.getZone(ForgeZoneType.Hand).size() <= 7
            }

            human.getZone(ForgeZoneType.Hand).size() shouldBe 7
            // Divination (resolved) + 1 discarded card
            human.getZone(ForgeZoneType.Graveyard).size() shouldBe 2

            // Verify the discard prompt was answered via the bridge
            val discardPrompts =
                harness.bridge
                    .promptBridge(SeatId(1))
                    .history
                    .filter { it.message.contains("iscard", ignoreCase = true) }
            discardPrompts shouldHaveSize 1
            discardPrompts.first().outcome shouldBe InteractivePromptBridge.PromptCallStatus.RESPONDED
        }
    })
