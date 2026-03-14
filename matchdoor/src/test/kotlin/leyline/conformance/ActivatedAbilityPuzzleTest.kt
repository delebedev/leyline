package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Integration test for activated ability puzzle (Goblin Fireslinger tap-to-ping).
 *
 * Validates: activate action, player targeting, damage resolution.
 * Single-ability card — verifies basic Activate path works end-to-end.
 */
class ActivatedAbilityPuzzleTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Goblin Fireslinger tap-to-ping kills opponent") {
            val pzl = """
            [metadata]
            Name:Ping for Lethal
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Tap Goblin Fireslinger to ping opponent for lethal.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=1

            humanbattlefield=Goblin Fireslinger
            humanlibrary=Mountain
            aibattlefield=Centaur Courser
            ailibrary=Mountain
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val human = h.game().registeredPlayers.first()
            val ai = h.game().registeredPlayers.last()

            h.phase() shouldBe "MAIN1"

            // Find Fireslinger and activate its tap ability
            val fireslinger = human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Goblin Fireslinger" }
            val iid = h.bridge.getOrAllocInstanceId(ForgeCardId(fireslinger.id)).value
            val grpId = h.bridge.cards.findGrpIdByName("Goblin Fireslinger") ?: 0

            // Single activated ability — abilityGrpId from first non-keyword slot
            val cardData = h.bridge.cards.findByGrpId(grpId)!!
            val keywordCount = cardData.keywordAbilityGrpIds.size
            val tapAbilityGrpId = cardData.abilityIds.getOrNull(keywordCount)?.first ?: 0

            val activateMsg = performAction {
                actionType = ActionType.Activate_add3
                instanceId = iid
                this.grpId = grpId
                abilityGrpId = tapAbilityGrpId
            }
            h.session.onPerformAction(activateMsg)
            h.drainSink()

            // Target opponent (seatId 2)
            h.selectTargets(listOf(2))

            // Resolve
            repeat(10) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }

            h.isGameOver().shouldBeTrue()
            human.hasWon().shouldBeTrue()
            ai.life shouldBe 0
        }
    })
