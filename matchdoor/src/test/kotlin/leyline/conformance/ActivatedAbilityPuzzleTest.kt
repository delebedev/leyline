package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag

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

            // Activate tap ability (only ability, index 0)
            h.activateAbility("Goblin Fireslinger").shouldBeTrue()

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
