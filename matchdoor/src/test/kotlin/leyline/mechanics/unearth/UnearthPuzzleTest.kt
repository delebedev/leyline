package leyline.mechanics.unearth

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.types.ForgeCardId
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Integration test for Unearth (graveyard-zone activated ability).
 *
 * Gixian Recycler — `K:Unearth:1 B`. Pay {1}{B}, return from graveyard to
 * battlefield with haste; exile at the next end step. Validates the
 * graveyard-activated rail in ActionMapper (parallel to the Channel hand-rail).
 */
@Suppress("MissingAssertSoftly") // intentional fail-fast — offer-shape and passUntil depend on prior steps
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

            val h = MatchFlowHarness(seed = 42L, validating = true)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val human = h.game().registeredPlayers.first()

            // Pre-unearth invariants
            human
                .getZone(ZoneType.Graveyard)
                .cards
                .any { it.name == "Gixian Recycler" }
                .shouldBeTrue()
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .none { it.name == "Gixian Recycler" }
                .shouldBeTrue()

            // The graveyard-activated rail in ActionMapper must surface
            // Activate_add3 for the Gixian Recycler iid. Asserting on the
            // emitted ActionsAvailableReq (rather than just attempting the
            // activation) confirms the offer is on the wire — without this,
            // submitActivateAction below would still succeed even if the
            // graveyard rail returned without emitting anything.
            val gixian = human.getZone(ZoneType.Graveyard).cards.first { it.name == "Gixian Recycler" }
            val gixianIid = h.bridge.getOrAllocInstanceId(ForgeCardId(gixian.id)).value
            val unearthOffer =
                h.allMessages
                    .asReversed()
                    .firstNotNullOfOrNull { msg ->
                        if (!msg.hasActionsAvailableReq()) return@firstNotNullOfOrNull null
                        msg.actionsAvailableReq.actionsList.firstOrNull { a ->
                            a.actionType == ActionType.Activate_add3 && a.instanceId == gixianIid
                        }
                    }
            assertSoftly {
                unearthOffer.shouldNotBeNull()
                unearthOffer.abilityGrpId shouldBeGreaterThan 0
            }

            h.activateAbilityFromGraveyard("Gixian Recycler").shouldBeTrue()
            h
                .passUntil(maxPasses = 10) {
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
