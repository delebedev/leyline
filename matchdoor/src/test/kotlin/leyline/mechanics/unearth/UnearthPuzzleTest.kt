package leyline.mechanics.unearth

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.testkit.MatchFlowHarness

/**
 * Integration test for Unearth (graveyard-zone activated ability).
 *
 * Gixian Recycler — `K:Unearth:1 B`. Pay {1}{B}, return from graveyard to
 * battlefield with haste; exile at the next end step. Validates the
 * graveyard-activated rail in ActionMapper (parallel to the Channel hand-rail).
 */
class UnearthPuzzleTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Gixian Recycler unearth from graveyard returns with haste") {
            val pzl =
                """
                [metadata]
                Name:Unearth Gixian Recycler
                Goal:Return + attack
                Turns:1
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Plains
                humangraveyard=Gixian Recycler
                humanbattlefield=Swamp;Swamp;Plains
                humanlibrary=Plains;Plains;Plains
                aibattlefield=
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val human = h.game().registeredPlayers.first()

            // Pre-unearth invariants
            human
                .getZone(ZoneType.Graveyard)
                .cards
                .any { it.name == "Gixian Recycler" }
                .shouldBeTrue()
            human.getZone(ZoneType.Battlefield).cards.none { it.name == "Gixian Recycler" }.shouldBeTrue()

            // The graveyard-activated loop should offer Unearth from GY.
            // We don't have a dedicated harness helper for activating from GY yet,
            // so reach the card directly and submit Activate_add3.
            h.activateAbilityFromGraveyard("Gixian Recycler").shouldBeTrue()
            h.passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Gixian Recycler" }
            }.shouldBeTrue()

            // Now on battlefield with haste (per Forge's Unearth implementation).
            val unearthed =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Gixian Recycler" }
            unearthed.hasKeyword("Haste").shouldBeTrue()
            // Sickness should be cleared: hasSickness gates attack legality.
            unearthed.hasSickness() shouldBe false
        }
    })
