package leyline.bridge.coord

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest
import java.lang.reflect.Modifier

class MatchPromptRuntimeSetTest :
    BoardTest({
        test("every bound prompt runtime has exactly one lifecycle owner") {
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
            val bindings = prompts.bindings(SeatId(1))
            val boundRuntimes =
                bindings.javaClass.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .onEach { it.trySetAccessible() }
                    .mapNotNull { it.get(bindings) }
                    .filter { it !== prompts.compatibilityCostSelection }
            val expectedOwners = boundRuntimes + prompts.blocking

            prompts.lifecycleOwners() shouldContainExactlyInAnyOrder expectedOwners
        }
    })
