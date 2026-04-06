package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.SeatId
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
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("flashback from GY: targets creature, resolves, spell exiled") {
            // 2 creatures on BF so targeting prompt fires (not auto-resolved for single target).
            // Auto-pass advances to combat — declare no attackers, pass through to Main2.
            val pzl = """
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

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            // 1. Verify Cast action offered with abilityGrpId for flashback
            val player = h.bridge.getPlayer(SeatId(1))!!
            val actions = h.allMessages
                .filter { it.hasActionsAvailableReq() }
                .flatMap { it.actionsAvailableReq.actionsList }
            val flashbackAction = actions.firstOrNull {
                it.actionType == ActionType.Cast && it.abilityGrpId > 0
            }
            flashbackAction.shouldNotBeNull()
            flashbackAction.abilityGrpId shouldBeGreaterThan 0

            // Auto-pass went to combat — skip it to get to Main2
            if (h.phase() != "MAIN1" && h.phase() != "MAIN2") {
                h.declareNoAttackers()
                h.passThroughCombat()
            }

            val creaturesBefore = player.getZone(ForgeZoneType.Battlefield).cards
                .filter { it.isCreature }
            creaturesBefore.size shouldBeGreaterThan 1

            val targetIid = h.bridge.getOrAllocInstanceId(
                leyline.bridge.ForgeCardId(creaturesBefore.first().id),
            ).value

            // 2. Cast from GY — triggers SelectTargetsReq
            val snap = h.messageSnapshot()
            h.castFromGraveyard("Electroduplicate").shouldBeTrue()
            val msgs = h.messagesSince(snap)
            val stReq = msgs.firstOrNull { it.hasSelectTargetsReq() }
            stReq.shouldNotBeNull()

            // 3. Select target + resolve
            h.selectTargets(listOf(targetIid))
            h.passUntil(maxPasses = 10) { game().stack.isEmpty }

            // 4. Spell not in GY/Hand/Stack after flashback resolve
            val nonExileZones = listOf(ForgeZoneType.Graveyard, ForgeZoneType.Hand, ForgeZoneType.Stack)
            val strayCards = nonExileZones.flatMap { z ->
                player.getZone(z)?.cards?.filter { it.name == "Electroduplicate" } ?: emptyList()
            }
            strayCards.size shouldBe 0

            // 5. Copy token on BF (creature count increased)
            val afterCreatures = player.getZone(ForgeZoneType.Battlefield).cards
                .filter { it.isCreature }
            afterCreatures.size shouldBeGreaterThan creaturesBefore.size
        }
    })
