package leyline.behavior.puzzles

import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import kotlin.time.Duration.Companion.seconds

/**
 * A puzzle whose turn limit expires on the opponent's turn must end the game.
 *
 * The turn-limit goal resolves `DB$ LosesGame` during that cleanup step, after
 * the turn's last state-based check. If the resulting loss is not processed
 * before the next turn is selected, the eliminated player stays in the turn
 * order and next-player selection never converges.
 */
class PuzzleTurnLimitLossTest :
    SessionTest({

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        // Turns:2 puts the turn-limit cleanup on the opponent's turn, so the
        // player that loses is not the one taking the next turn.
        val puzzleText =
            """
            [metadata]
            Name:Turn Limit Loss
            Goal:Win
            Turns:2
            Difficulty:Easy
            Description:Do nothing and run out the turn limit.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Forest
            humanbattlefield=Forest;Forest
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            aibattlefield=Mountain
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

        session(
            "turn limit expiry ends the game instead of spinning on the turn order",
            puzzle = puzzleText,
            timeout = 120.seconds,
        ) {
            // Terminates only if the loss is processed; otherwise next-player
            // selection spins inside the engine and this never returns.
            runCatching { passUntilTurn(targetTurn = 5, maxPasses = 40) }

            runCatching { isGameOver() }.getOrDefault(true) shouldBe true
        }
    })
