package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Integration test for planeswalker sacrifice puzzle.
 *
 * Cast Liliana of the Veil → -2 targeting opponent → opponent sacrifices
 * Centaur Courser → Grizzly Bears attacks unblocked for lethal.
 */
class PlaneswalkerSacrificeTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Liliana -2 forces sacrifice, attack for lethal") {
            val pzl = """
            [metadata]
            Name:Liliana Sacrifice
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Cast Liliana, -2 to force sacrifice, attack for lethal.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=2

            humanhand=Liliana of the Veil
            humanbattlefield=Grizzly Bears;Swamp;Swamp;Swamp
            humanlibrary=Swamp
            aibattlefield=Centaur Courser
            ailibrary=Mountain
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val human = h.game().registeredPlayers.first()
            val ai = h.game().registeredPlayers.last()

            h.phase() shouldBe "MAIN1"

            // Cast Liliana of the Veil (1BB)
            h.castSpellByName("Liliana of the Veil").shouldBeTrue()

            // Resolve onto battlefield
            repeat(5) {
                if (h.isGameOver()) return@repeat
                if (human.getZone(ZoneType.Battlefield).cards.any { it.name.contains("Liliana") }) return@repeat
                h.passPriority()
            }
            human.getZone(ZoneType.Battlefield).cards
                .any { it.name.contains("Liliana") }.shouldBeTrue()

            // Find Liliana and build Activate action for -2 (second ability)
            val liliana = human.getZone(ZoneType.Battlefield).cards
                .first { it.name.contains("Liliana") }
            val lilianaIid = h.bridge.getOrAllocInstanceId(ForgeCardId(liliana.id)).value
            val lilianaGrpId = h.bridge.cards.findGrpIdByName("Liliana of the Veil") ?: 0

            // Look up the -2 ability's abilityGrpId (second activated ability after keywords)
            val cardData = h.bridge.cards.findByGrpId(lilianaGrpId)!!
            val keywordCount = cardData.keywordAbilityGrpIds.size
            val minus2AbilityGrpId = cardData.abilityIds.getOrNull(keywordCount + 1)?.first ?: 0

            val activateMsg = performAction {
                actionType = ActionType.Activate_add3
                instanceId = lilianaIid
                grpId = lilianaGrpId
                abilityGrpId = minus2AbilityGrpId
            }
            h.session.onPerformAction(activateMsg)
            h.drainSink()

            // Target opponent (seatId 2)
            h.selectTargets(listOf(2))

            // Resolve -2 — Courser should be sacrificed
            repeat(10) {
                if (h.isGameOver()) return@repeat
                if (ai.getZone(ZoneType.Battlefield).cards.none { it.isCreature }) return@repeat
                h.passPriority()
            }

            ai.getZone(ZoneType.Battlefield).cards
                .filter { it.isCreature }.isEmpty().shouldBeTrue()

            // Liliana should have loyalty 1 (started at 3, used -2)
            liliana.currentLoyalty shouldBe 1

            // Advance to combat, attack with Bears
            repeat(10) {
                if (h.isGameOver()) return@repeat
                if (h.phase() == "COMBAT_DECLARE_ATTACKERS") return@repeat
                h.passPriority()
            }

            val bearsIid = h.humanBattlefieldCreatures()
                .first { it.second == "Grizzly Bears" }.first
            h.declareAttackers(listOf(bearsIid))

            // Pass through combat
            repeat(10) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }

            h.isGameOver().shouldBeTrue()
            human.hasWon().shouldBeTrue()
            human.hasLost().shouldBeFalse()
            ai.life shouldBe 0
        }
    })
