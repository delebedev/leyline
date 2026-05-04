package leyline.conformance

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

/**
 * Session-tier activated ability tests — full MatchSession round-trip.
 *
 * Board-level action field tests live in [ActivatedAbilityTest] (BoardTest).
 */
class ActivatedAbilityInteractionTest :
    SessionTest({

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

            // Activate tap ability → wait for SelectTargetsReq before responding
            // (drainSink returns before the engine emits the prompt under load).
            activateAbility("Goblin Fireslinger").shouldBeTrue()
            passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))

            passUntil(maxPasses = 10) { ai.life < 5 }.shouldBeTrue()
            ai.life shouldBe 4
        }
    })
