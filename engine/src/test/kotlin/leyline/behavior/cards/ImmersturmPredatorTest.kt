package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.testkit.SessionTest
import leyline.testkit.beInExileOf

/**
 * Integration test for Immersturm Predator — sacrifice-as-cost activated ability.
 *
 * Chain: activate sacrifice ability → cost prompt (select creature) → creature dies →
 * Predator tapped → tap trigger fires → GY exile targeting → +1/+1 counter.
 */
class ImmersturmPredatorTest :
    SessionTest({
        session(
            "sacrifice-as-cost: sac creature -> Predator tapped -> trigger fires -> counter",
            """
            [metadata]
            Name:Immersturm Predator Sacrifice
            Goal:Win
            Turns:5
            Difficulty:Easy
            Description:Activate sacrifice ability, verify tap trigger and counter.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=4
            removesummoningsickness=true

            humanbattlefield=Immersturm Predator;Grizzly Bears;Swamp;Mountain
            humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp
            aigraveyard=Centaur Courser
            aibattlefield=Mountain
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent(),
            validating = true,
        ) {
            phase() shouldBe "MAIN1"

            val predatorBefore =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Immersturm Predator" }
            val basePower = predatorBefore.netPower

            // --- Step 1: Activate sacrifice ability ---
            activateAbility("Immersturm Predator").shouldBeTrue()

            // Sacrifice cost prompt should appear — verify structural properties
            val sacWindow =
                bridge.cutCoordinator.oneShotPayCosts
                    .current()
                    .shouldNotBeNull()
            val sacPrompt = allMessages.last { it.hasPayCostsReq() }.payCostsReq
            val selection = sacPrompt.effectCostReq.costSelection
            assertSoftly {
                selection.idsCount shouldBeGreaterThan 0
                selection.minSel shouldBe 1
                selection.maxSel shouldBe 1
                sacWindow.kind shouldBe PayCostsRouteKind.Sacrifice
            }

            // --- Step 2: Respond to sacrifice cost through the session wire adapter ---
            respondToEffectCost(listOf(selection.idsList.first()))

            // Drain messages produced by the sacrifice
            session.triggerAutoPass()
            drainSink()

            // Bears should be sacrificed now
            human
                .getZone(ZoneType.Graveyard)
                .cards
                .any { it.name == "Grizzly Bears" }
                .shouldBeTrue()

            // Predator should be tapped (from the ability's "Tap it" rider)
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .first { it.name == "Immersturm Predator" }
                .isTapped
                .shouldBeTrue()

            // --- Step 3: Tap trigger fires → targeting prompt for GY exile ---
            if (bridge.cutCoordinator.targeting
                    .current() != null
            ) {
                val targetInstanceId =
                    allMessages
                        .last { it.hasSelectTargetsReq() }
                        .selectTargetsReq.targetsList
                        .single()
                        .targetsList
                        .first()
                        .targetInstanceId
                selectTargets(listOf(targetInstanceId))
            }

            // --- Step 4: Pass until trigger resolves ---
            passUntil(maxPasses = 10) {
                val predator =
                    human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .firstOrNull { it.name == "Immersturm Predator" }
                predator != null && predator.netPower > basePower
            }.shouldBeTrue()

            // Verify Predator got +1/+1 counter
            val predatorAfter =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Immersturm Predator" }
            predatorAfter.netPower shouldBeGreaterThan basePower
            predatorAfter.getCounters(forge.game.card.CounterEnumType.P1P1) shouldBeGreaterThan 0

            // If tap trigger exiled a card, verify it went to Exile (not under Predator).
            // Trigger is "up to one" — card may stay in GY if targeting skipped.
            // But if it left GY, it must be in Exile (not attached to anything).
            val courserInGY = ai.getZone(ZoneType.Graveyard).cards.any { it.name == "Centaur Courser" }
            if (!courserInGY) {
                "Centaur Courser" should beInExileOf(ai)
            }
        }
    })
