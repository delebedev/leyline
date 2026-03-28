package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag

/**
 * Integration test for vehicle crew mechanic.
 *
 * Validates: crew activation, cost payment (tap creature), vehicle becomes creature, combat attack.
 * Brute Suit (4/3 Vigilance, Crew 1) crewed by Centaur Courser (3/3) attacks for lethal.
 *
 * AILife set to 10 so Centaur Courser's auto-pass attacks (3 damage each) don't kill
 * before we get to crew. After crewing, Brute Suit (4/3) finishes the job.
 */
class VehicleCrewPuzzleTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("crew vehicle and attack for lethal") {
            val pzl = """
            [metadata]
            Name:Crew and Attack
            Goal:Win
            Turns:4
            Difficulty:Easy
            Description:Crew Brute Suit with Centaur Courser, then attack for lethal.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=10

            humanbattlefield=Brute Suit|Centaur Courser
            humanlibrary=Mountain|Mountain|Mountain|Mountain
            aibattlefield=Coral Merfolk
            ailibrary=Mountain|Mountain|Mountain|Mountain
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val human = h.game().registeredPlayers.first()
            val ai = h.game().registeredPlayers.last()

            // Auto-pass should stop at Main1 when crew ability is available
            h.phase() shouldBe "MAIN1"

            // Activate crew ability on Brute Suit — engine auto-selects Centaur Courser
            h.activateAbility("Brute Suit").shouldBeTrue()

            // Pass priority until game over — auto-pass handles combat
            h.passUntil(maxPasses = 40) { isGameOver() }.shouldBeTrue()

            h.isGameOver().shouldBeTrue()
            human.hasWon().shouldBeTrue()
        }
    })
