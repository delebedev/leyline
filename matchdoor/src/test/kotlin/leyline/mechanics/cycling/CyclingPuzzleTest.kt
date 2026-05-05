package leyline.mechanics.cycling

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.testkit.MatchFlowHarness

/**
 * Integration test for Cycling (hand-zone activated ability with discard-as-cost).
 *
 * Miscalculation — `K:Cycling:2`. Pay {2}, discard from hand → draw a card.
 * Validates that the existing hand-activated-ability rail (which makes Channel
 * work) also surfaces Cycling: Activate_add3 offered for the hand card,
 * Discard<1/CARDNAME> cost component fires the Hand→Graveyard ZoneTransfer
 * with category=Discard, and the resolve effect (Draw) lands.
 */
class CyclingPuzzleTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Miscalculation cycle from hand draws + discards") {
            val pzl =
                """
                [metadata]
                Name:Cycle Miscalculation
                Goal:Cycle and draw
                Turns:1
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Miscalculation
                humanbattlefield=Island;Island
                humanlibrary=Lightning Bolt;Island
                ailibrary=Mountain
                """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val human = h.game().registeredPlayers.first()

            // Pre-cycle invariants
            human.getZone(ZoneType.Hand).cards.any { it.name == "Miscalculation" }.shouldBeTrue()
            val handBefore = human.getZone(ZoneType.Hand).size()
            val gyBefore = human.getZone(ZoneType.Graveyard).size()

            // Cycle Miscalculation — same path as Channel.
            h.activateAbilityFromHand("Miscalculation").shouldBeTrue()
            // Wait for the Cycling AB to resolve (Discard is part of the cost,
            // Draw is the resolve effect — we need both to land before asserting).
            h.passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Graveyard).cards.any { it.name == "Miscalculation" } &&
                    human.getZone(ZoneType.Hand).cards.any { it.name == "Lightning Bolt" }
            }.shouldBeTrue()

            // Miscalculation now in graveyard
            human
                .getZone(ZoneType.Graveyard)
                .cards
                .any { it.name == "Miscalculation" }
                .shouldBeTrue()
            // Hand size: -1 (cycled) +1 (drew Lightning Bolt) = same
            human.getZone(ZoneType.Hand).size() shouldBe handBefore
            human
                .getZone(ZoneType.Hand)
                .cards
                .any { it.name == "Lightning Bolt" }
                .shouldBeTrue()
            human.getZone(ZoneType.Graveyard).size() shouldBe gyBefore + 1
        }
    })
