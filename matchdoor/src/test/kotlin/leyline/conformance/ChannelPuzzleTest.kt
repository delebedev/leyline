package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag

/**
 * Integration test for Channel (hand-zone activated ability).
 *
 * Twinshot Sniper: {1}{R}, discard from hand → deal 2 damage to any target.
 * Validates: Activate_add3 offered for hand card, discard-as-cost, damage resolution.
 */
class ChannelPuzzleTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Twinshot Sniper channel from hand kills opponent") {
            val pzl = """
            [metadata]
            Name:Channel for Lethal
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Channel Twinshot Sniper from hand to deal 2 damage to opponent for lethal.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=2

            humanhand=Twinshot Sniper
            humanbattlefield=Mountain;Mountain
            humanlibrary=Mountain
            aibattlefield=Centaur Courser
            ailibrary=Mountain
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val ai = h.game().registeredPlayers.last()

            h.phase() shouldBe "MAIN1"

            // Channel from hand (activated ability, not cast)
            h.activateAbilityFromHand("Twinshot Sniper").shouldBeTrue()

            // Target opponent (seatId 2)
            h.selectTargets(listOf(2))

            // Resolve — damage kills opponent
            h.passUntil(maxPasses = 20) { isGameOver() || ai.life <= 0 }.shouldBeTrue()

            h.isGameOver().shouldBeTrue()
            ai.life shouldBe 0
        }
    })
