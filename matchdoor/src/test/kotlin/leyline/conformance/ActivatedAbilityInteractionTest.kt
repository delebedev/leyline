package leyline.conformance

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * Session-tier activated ability tests — full MatchSession round-trip.
 *
 * Board-level action field tests live in [ActivatedAbilityTest] (SubsystemTest).
 * Puzzle iteration scaffolding in [ActivatedAbilityPuzzleTest] (kept as-is).
 */
class ActivatedAbilityInteractionTest :
    InteractionTest({

        test("Goblin Fireslinger tap-to-ping deals damage to opponent") {
            startPuzzle(
                """
            [metadata]
            Name:Tap to Ping
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=5

            humanbattlefield=Goblin Fireslinger
            humanlibrary=Mountain
            aibattlefield=Centaur Courser
            ailibrary=Mountain
                """.trimIndent(),
            )

            phase() shouldBe "MAIN1"
            val aiPlayer = ai

            // Activate tap ability → target opponent (seatId 2) → resolve
            activateAbility("Goblin Fireslinger").shouldBeTrue()
            selectTargets(listOf(2))
            passUntil(maxPasses = 5) { aiPlayer.life < 5 }.shouldBeTrue()

            aiPlayer.life shouldBe 4
        }
    })
