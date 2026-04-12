package leyline.conformance

import forge.card.CardStateName
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag

/**
 * Phase 3 — Saga full-lifecycle integration test (scope §2a + §2c, layers L2 and L3).
 *
 * Cast-from-hand shape. BF-pre-seeded sagas hang the puzzle finalizer because
 * Forge's saga ETB `DB$ PutCounter | UpTo$ True | CounterNum$ FinalChapterNr`
 * prompts the player and the puzzle loader has no controller. See
 * `SagaLoadProbeTest` KDoc for the bisect that led here.
 *
 * Lore counter timing: PhaseHandler adds a lore counter at the active player's
 * precombat-Main1. Human→AI→human turn sequence means our saga ticks on turn 1
 * (ETB), turn 3 (Main1), turn 5 (Main1) → lore 1/2/3 → Ch I / Ch II / Ch III.
 * The Ch III transform fires in our turn 5 Main1.
 */
class SagaTransformPuzzleTest :
    FunSpec({

        tags(IntegrationTag)

        test("tribute to horobi: cast → 3 chapters → transform end-to-end") {
            val puzzleText = """
                [metadata]
                Name:Saga Full Lifecycle — Tribute to Horobi
                Goal:Survive
                Turns:6
                Difficulty:Easy
                Description:Cast Tribute to Horobi, auto-advance through 3 chapters, observe final-chapter exile-return transform.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Tribute to Horobi
                humanbattlefield=Swamp;Swamp;Swamp
                humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp
                aibattlefield=Mountain
                ailibrary=Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

            val harness = MatchFlowHarness(validating = false)
            try {
                harness.connectAndKeepPuzzleText(puzzleText)
                val game = harness.bridge.getGame()!!

                // Cast the saga. MatchSession's auto-pass loop will resolve the
                // cast, fire ETB, then advance through turns letting each
                // chapter trigger resolve on its active-player Main1.
                harness.castSpellByName("Tribute to Horobi").shouldBeTrue()

                // Keep passing until the transform lands or we run out of budget.
                // Break as soon as we see Echo on the battlefield — after that
                // the game will reach Survive-goal cleanup and the bridge will
                // shut down the game, invalidating further queries.
                var transformed = false
                repeat(8) {
                    if (harness.isGameOver()) return@repeat
                    val ourBf = game.humanPlayer.getZone(ZoneType.Battlefield).cards
                    if (ourBf.any { it.name == "Echo of Death's Wail" }) {
                        transformed = true
                        return@repeat
                    }
                    harness.passPriority()
                }

                transformed.shouldBeTrue()

                val echo = game.humanPlayer.getZone(ZoneType.Battlefield).cards
                    .first { it.name == "Echo of Death's Wail" }
                echo.isBackSide shouldBe true
                echo.currentStateName shouldBe CardStateName.Backside

                // Front-face Tribute has left the battlefield (went through
                // Exile→Return transformed).
                val frontStill = game.humanPlayer.getZone(ZoneType.Battlefield).cards
                    .any { it.name == "Tribute to Horobi" }
                frontStill shouldBe false
            } finally {
                harness.shutdown()
            }
        }
    })
