package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag

/**
 * Diagnostic probes for `MatchFlowHarness.connectAndKeepPuzzleText` failure
 * modes. Run to verify the findings still hold after infra changes. See also
 * the `ConcealingCurtainsPuzzleTest` and `DfcTransformTest` for the known-good
 * DFC path through this harness.
 *
 * Findings (2026-04-12):
 *
 * | Probe | Shape                                 | Pass? | Notes |
 * |-------|---------------------------------------|-------|-------|
 * | 1     | saga BF alone                         | ✗ 4s  | ETB PutCounter prompt hangs — no controller |
 * | 2     | saga in hand alone                    | ✓     | Saga construction is fine |
 * | 3     | different saga BF alone               | ✗     | Generic saga-BF bug |
 * | 4     | DFC BF alone                          | ✗     | Not saga-specific — harness needs "full context" |
 * | 5     | DFC BF + lands + AI creature          | ✓     | Full context works for DFC |
 * | 6     | saga BF + same full context           | ✗     | Saga-BF breaks even with full context |
 * | 7     | saga BF + NoETBTrigs                  | ✗     | Modifier doesn't suppress the PutCounter prompt |
 * | 8     | saga BF + pre-seeded LORE=1           | ✗     | Pre-seed doesn't bypass ETB path |
 * | 9     | saga in hand + full context           | ✓     | Cast-from-hand is the clean shape |
 *
 * Two distinct gaps:
 *
 * - **Bug A (MatchFlowHarness bootstrap):** single-card-one-side puzzles fail
 *   silently. Needs AI presence + lands. Not saga-specific.
 * - **Bug B (saga ETB on puzzle-BF load):** Forge saga ETB is
 *   `DB$ PutCounter | ETB$ True | UpTo$ True | CounterNum$ FinalChapterNr` —
 *   the `UpTo` needs a player controller to answer. Puzzle finalizer has no
 *   controller → hangs or silent-fails. `NoETBTrigs` and pre-seeded counters
 *   don't bypass it.
 *
 * Workaround: cast-from-hand. Forge runs the same ETB with a real controller
 * in place, resolves the prompt (player auto-picks 1 counter), saga lands on
 * BF, chapter triggers wire through normally. This is what real Arena sessions
 * look like anyway, so it's also higher-fidelity.
 */
class SagaLoadProbeTest :
    FunSpec({

        tags(IntegrationTag)

        test("probe 1: saga on BF, no Counters modifier") {
            val pzl = """
                [metadata]
                Name:Probe1
                Goal:Survive
                Turns:1
                Difficulty:Easy
                Description:Bare saga load probe.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Tribute to Horobi
                humanlibrary=Swamp
                ailibrary=Swamp
            """.trimIndent()
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(pzl)
                val game = h.bridge.getGame()
                (game != null).shouldBeTrue()
                val saga = game!!.humanPlayer.getZone(ZoneType.Battlefield).cards
                    .firstOrNull { it.name == "Tribute to Horobi" }
                (saga != null).shouldBeTrue()
            } finally {
                h.shutdown()
            }
        }

        test("probe 2: saga in hand only (not on BF)") {
            val pzl = """
                [metadata]
                Name:Probe2
                Goal:Survive
                Turns:1
                Difficulty:Easy
                Description:Saga loads into hand, never enters play.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Tribute to Horobi
                humanbattlefield=Swamp;Swamp;Swamp
                humanlibrary=Swamp
                ailibrary=Swamp
            """.trimIndent()
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(pzl)
                val game = h.bridge.getGame()
                (game != null).shouldBeTrue()
                val saga = game!!.humanPlayer.getZone(ZoneType.Hand).cards
                    .firstOrNull { it.name == "Tribute to Horobi" }
                (saga != null).shouldBeTrue()
            } finally {
                h.shutdown()
            }
        }

        test("probe 3: different saga on BF (Huntsman's Redemption)") {
            val pzl = """
                [metadata]
                Name:Probe3
                Goal:Survive
                Turns:1
                Difficulty:Easy
                Description:Non-transforming saga on BF.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=The Huntsman's Redemption
                humanlibrary=Forest
                ailibrary=Forest
            """.trimIndent()
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(pzl)
                val game = h.bridge.getGame()
                (game != null).shouldBeTrue()
            } finally {
                h.shutdown()
            }
        }

        test("probe 4 (control): DFC Concealing Curtains on BF — known-good") {
            val pzl = """
                [metadata]
                Name:Probe4Control
                Goal:Survive
                Turns:1
                Difficulty:Easy
                Description:Known-good DFC load for comparison.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Concealing Curtains
                humanlibrary=Swamp
                ailibrary=Swamp
            """.trimIndent()
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(pzl)
                val game = h.bridge.getGame()
                (game != null).shouldBe(true)
            } finally {
                h.shutdown()
            }
        }

        test("probe 5: DFC on BF + AI creature (DfcTransformTest pattern)") {
            val pzl = """
                [metadata]
                Name:Probe5
                Goal:Win
                Turns:1
                Difficulty:Tutorial
                Description:Mirror DfcTransformTest's known-working shape.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Concealing Curtains;Swamp;Swamp;Swamp
                aibattlefield=Runeclaw Bear
            """.trimIndent()
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(pzl)
                val game = h.bridge.getGame()
                (game != null).shouldBe(true)
            } finally {
                h.shutdown()
            }
        }

        test("probe 7: saga + full context + NoETBTrigs modifier") {
            val pzl = """
                [metadata]
                Name:Probe7
                Goal:Win
                Turns:1
                Difficulty:Tutorial
                Description:Saga with NoETBTrigs to suppress ETB PutCounter prompt.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Tribute to Horobi|NoETBTrigs;Swamp;Swamp;Swamp
                aibattlefield=Runeclaw Bear
            """.trimIndent()
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(pzl)
                val game = h.bridge.getGame()
                (game != null).shouldBe(true)
            } finally {
                h.shutdown()
            }
        }

        test("probe 8: saga pre-seeded counters to satisfy FinalChapterNr") {
            val pzl = """
                [metadata]
                Name:Probe8
                Goal:Win
                Turns:1
                Difficulty:Tutorial
                Description:Saga with LORE=1 pre-seeded; ETB PutCounter may short-circuit if counters already satisfied.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Tribute to Horobi|Counters:LORE=1;Swamp;Swamp;Swamp
                aibattlefield=Runeclaw Bear
            """.trimIndent()
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(pzl)
                val game = h.bridge.getGame()
                (game != null).shouldBe(true)
            } finally {
                h.shutdown()
            }
        }

        test("probe 9: saga cast-from-hand (our real integration shape)") {
            val pzl = """
                [metadata]
                Name:Probe9
                Goal:Win
                Turns:1
                Difficulty:Tutorial
                Description:Saga in hand — let Forge cast it through real ETB path.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Tribute to Horobi
                humanbattlefield=Swamp;Swamp;Swamp
                humanlibrary=Swamp
                aibattlefield=Runeclaw Bear
                ailibrary=Swamp
            """.trimIndent()
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(pzl)
                val game = h.bridge.getGame()
                (game != null).shouldBe(true)
            } finally {
                h.shutdown()
            }
        }

        test("probe 6: saga + AI creature (DfcTransformTest shape, swap card)") {
            val pzl = """
                [metadata]
                Name:Probe6
                Goal:Win
                Turns:1
                Difficulty:Tutorial
                Description:Saga on BF with same accompanying state as probe 5.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Tribute to Horobi;Swamp;Swamp;Swamp
                aibattlefield=Runeclaw Bear
            """.trimIndent()
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(pzl)
                val game = h.bridge.getGame()
                (game != null).shouldBe(true)
            } finally {
                h.shutdown()
            }
        }
    })
