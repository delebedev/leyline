package leyline.bridge.coord

import io.kotest.matchers.collections.shouldContainExactly
import leyline.testkit.BoardTest

class MatchPromptRuntimeSetTest :
    BoardTest({
        test("the settled cohort contributes one match lifecycle owner") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:prompt runtime inventory
                    Goal:Win
                    Turns:1

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanlibrary=Forest
                    ailibrary=Forest
                    """.trimIndent(),
                )
            val prompts = board.bridge.cutCoordinator.prompts

            prompts.lifecycleOwners() shouldContainExactly
                listOf(
                    prompts.settled,
                    prompts.targeting,
                    prompts.blocking,
                    prompts.manaSourcePayments,
                )
        }
    })
