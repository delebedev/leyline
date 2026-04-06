package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag

/**
 * Integration test for Immersturm Predator — sacrifice-as-cost activated ability.
 *
 * Chain: activate sacrifice ability → cost prompt (select creature) → creature dies →
 * Predator tapped → tap trigger fires → GY exile targeting → +1/+1 counter.
 */
class ImmersturmPredatorTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("sacrifice-as-cost: sac creature -> Predator tapped -> trigger fires -> counter") {
            val pzl = """
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
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val human = h.game().registeredPlayers.first()
            h.phase() shouldBe "MAIN1"

            val predatorBefore = human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Immersturm Predator" }
            val basePower = predatorBefore.netPower

            // --- Step 1: Activate sacrifice ability ---
            h.activateAbility("Immersturm Predator").shouldBeTrue()

            // Sacrifice cost prompt should appear — verify structural properties
            val sacPrompt = h.bridge.seat(1).prompt.getPendingPrompt()
            sacPrompt shouldNotBe null
            sacPrompt!!.request.candidateRefs.size shouldBeGreaterThan 0

            // --- Step 2: Respond to sacrifice cost by submitting directly to prompt bridge ---
            // The prompt has candidateRefs with forge card IDs. Index 0 = Grizzly Bears.
            h.bridge.seat(1).prompt.submitResponse(sacPrompt.promptId, listOf(0))
            h.bridge.awaitPriority()

            // Drain messages produced by the sacrifice
            h.session.triggerAutoPass(h.bridge)
            h.drainSink()

            // Bears should be sacrificed now
            human.getZone(ZoneType.Graveyard).cards
                .any { it.name == "Grizzly Bears" }.shouldBeTrue()

            // Predator should be tapped (from the ability's "Tap it" rider)
            human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Immersturm Predator" }.isTapped.shouldBeTrue()

            // --- Step 3: Tap trigger fires → targeting prompt for GY exile ---
            val tapPrompt = h.bridge.seat(1).prompt.getPendingPrompt()
            if (tapPrompt != null && tapPrompt.request.candidateRefs.isNotEmpty()) {
                // Pick first GY card to exile
                h.bridge.seat(1).prompt.submitResponse(tapPrompt.promptId, listOf(0))
                h.bridge.awaitPriority()
                h.session.triggerAutoPass(h.bridge)
                h.drainSink()
            }

            // --- Step 4: Pass until trigger resolves ---
            h.passUntil(maxPasses = 10) {
                val predator = human.getZone(ZoneType.Battlefield).cards
                    .firstOrNull { it.name == "Immersturm Predator" }
                predator != null && predator.netPower > basePower
            }.shouldBeTrue()

            // Verify Predator got +1/+1 counter
            val predatorAfter = human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Immersturm Predator" }
            predatorAfter.netPower shouldBeGreaterThan basePower
            predatorAfter.getCounters(forge.game.card.CounterEnumType.P1P1) shouldBeGreaterThan 0

            // If tap trigger exiled a card, verify it went to Exile (not under Predator)
            val ai = h.game().registeredPlayers.last()
            val courserInGY = ai.getZone(ZoneType.Graveyard).cards.any { it.name == "Centaur Courser" }
            val courserInExile = ai.getZone(ZoneType.Exile).cards.any { it.name == "Centaur Courser" }
            // Trigger is "up to one" — card may stay in GY if targeting skipped.
            // But if it left GY, it must be in Exile (not attached to anything).
            if (!courserInGY) {
                courserInExile.shouldBeTrue()
            }
        }
    })
