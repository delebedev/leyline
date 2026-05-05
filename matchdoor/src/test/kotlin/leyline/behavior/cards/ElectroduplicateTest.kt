package leyline.behavior.cards

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Integration test: Electroduplicate flashback from graveyard.
 *
 * Verifies:
 * 1. Cast action offered with flashback abilityGrpId
 * 2. Flashback cast triggers SelectTargetsReq (targeting)
 * 3. After resolution, spell exiled (not in GY)
 * 4. Copy token appears on battlefield
 */
class ElectroduplicateTest :
    SessionTest({

        test("flashback from GY: targets creature, resolves, spell exiled") {
            // 2 creatures on BF so targeting prompt fires (not auto-resolved for single target).
            // Auto-pass advances to combat — declare no attackers, pass through to Main2.
            val pzl =
                """
                [metadata]
                Name:Electroduplicate Flashback
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=4

                humangraveyard=Electroduplicate
                humanbattlefield=Raging Goblin;Ornithopter;Mountain;Mountain;Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """.trimIndent()

            startPuzzleRaw(pzl, validating = false)

            // 1. Verify Cast action offered with abilityGrpId for flashback
            val actions =
                allMessages
                    .filter { it.hasActionsAvailableReq() }
                    .flatMap { it.actionsAvailableReq.actionsList }
            val flashbackAction =
                actions.firstOrNull {
                    it.actionType == ActionType.Cast && it.abilityGrpId > 0
                }
            flashbackAction.shouldNotBeNull()
            flashbackAction.abilityGrpId shouldBeGreaterThan 0

            // Auto-pass went to combat — skip it to get to Main2
            if (phase() != "MAIN1" && phase() != "MAIN2") {
                harness.declareNoAttackers()
                harness.passThroughCombat()
            }

            val creaturesBefore =
                human
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .filter { it.isCreature }
            creaturesBefore.size shouldBeGreaterThan 1

            val targetIid = human.battlefield.iid(creaturesBefore.first())

            // 2. Cast from GY — triggers SelectTargetsReq
            val cast = after { harness.castFromGraveyard("Electroduplicate").shouldBeTrue() }
            cast.expectOneSelectTargetsReq()

            // 3. Select target + resolve
            selectTargets(listOf(targetIid))
            passUntil(maxPasses = 10) { game().stack.isEmpty }

            // 4. Spell not in GY/Hand/Stack after flashback resolve
            val nonExileZones = listOf(ForgeZoneType.Graveyard, ForgeZoneType.Hand, ForgeZoneType.Stack)
            val strayCards =
                nonExileZones.flatMap { z ->
                    human.getZone(z)?.cards?.filter { it.name == "Electroduplicate" } ?: emptyList()
                }
            strayCards.size shouldBe 0

            // 5. Copy token on BF (creature count increased)
            val afterCreatures =
                human
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .filter { it.isCreature }
            afterCreatures.size shouldBeGreaterThan creaturesBefore.size
        }
    })
