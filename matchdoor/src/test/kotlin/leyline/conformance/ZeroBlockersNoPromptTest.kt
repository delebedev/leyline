package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import leyline.IntegrationTag

class ZeroBlockersNoPromptTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("AI combat with zero blockers does not prompt client") {
            val puzzleText = """
                [metadata]
                Name:Zero Blockers No Prompt
                Goal:Win
                Turns:3
                Difficulty:Easy
                Description:AI attacks while defender has no legal blockers

                [state]
                ActivePlayer=AI
                ActivePhase=MAIN1
                HumanLife=5
                AILife=20

                humanbattlefield=Plains;Plains
                humanlibrary=Plains;Plains;Plains
                aibattlefield=Hurloon Minotaur;Centaur Courser;Raging Goblin;Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)

            val blockerReqs = h.allMessages.filter { it.hasDeclareBlockersReq() }
            blockerReqs.shouldBeEmpty()

            h.isGameOver().shouldBeTrue()
        }
    })
