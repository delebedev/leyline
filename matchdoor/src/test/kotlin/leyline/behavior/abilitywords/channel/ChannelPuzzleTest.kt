package leyline.behavior.abilitywords.channel

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

/**
 * Integration test for Channel (hand-zone activated ability).
 *
 * Twinshot Sniper: {1}{R}, discard from hand → deal 2 damage to any target.
 * Validates: Activate_add3 offered for hand card, discard-as-cost, damage resolution.
 */
class ChannelPuzzleTest :
    SessionTest({

        test("Twinshot Sniper channel from hand kills opponent") {
            val pzl =
                """
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

            startPuzzleRaw(pzl, validating = true)

            phase() shouldBe "MAIN1"

            // Channel from hand (activated ability, not cast). activateAbilityFromHand
            // submits the Activate_add3 action then drains the sink — but drainSink
            // returns before the engine emits the SelectTargetsReq. Wait for the
            // prompt before responding, otherwise selectTargets races the engine
            // thread under load.
            activateAbilityFromHand("Twinshot Sniper").shouldBeTrue()
            passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()

            // Target opponent (seatId 2)
            selectTargets(listOf(OPPONENT_SEAT))

            // Resolve — wait for damage to apply (ai.life dropping is the immediate
            // outcome; isGameOver is a downstream consequence the engine sets later).
            passUntil(maxPasses = 20) { ai.life < 2 }.shouldBeTrue()

            ai.life shouldBe 0
            isGameOver().shouldBeTrue()
        }
    })
