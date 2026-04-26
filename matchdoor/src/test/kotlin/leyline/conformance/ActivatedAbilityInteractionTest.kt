package leyline.conformance

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * Session-tier activated ability tests — full MatchSession round-trip.
 *
 * Board-level action field tests live in [ActivatedAbilityTest] (SubsystemTest).
 */
class ActivatedAbilityInteractionTest :
    InteractionTest({

        test("Goblin Fireslinger tap-to-ping deals damage to opponent") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=5

                humanbattlefield=Goblin Fireslinger
                humanlibrary=Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain
                """,
                name = "Tap to Ping",
            )

            phase() shouldBe "MAIN1"

            // Activate tap ability → target opponent (seatId 2) → resolve
            activateAbility("Goblin Fireslinger").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntil(maxPasses = 15) { ai.life < 5 }.shouldBeTrue()

            ai.life shouldBe 4
        }
    })
