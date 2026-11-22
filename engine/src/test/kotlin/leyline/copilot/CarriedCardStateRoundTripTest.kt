package leyline.copilot

import leyline.testkit.BoardTest

/** Static boards: each puzzle holds its shape at Main1, so no game-loop step precedes the round trip. */
class CarriedCardStateRoundTripTest :
    BoardTest({
        // Each board reproduces a shape whose hydration once diverged from its source game.
        for (board in staticRoundTripBoards) {
            test("round trip preserves carried card state: ${board.name}") {
                val source = startPuzzleAtMain1(withMetadata(board))
                assertRoundTripPreservesCarriedState(source.bridge)
            }
        }
    })

private fun withMetadata(board: RoundTripBoard): String =
    "[metadata]\nName:${board.name}\nGoal:Win\nTurns:1\n\n[state]\n${board.puzzle.trimIndent()}"

private val staticRoundTripBoards =
    listOf(
        RoundTripBoard(
            "aura attached to a token creature",
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Pacifism|Id:101|AttachedTo:201
            aibattlefield=T:g_1_2_spider_reach|Id:201
            humanlibrary=Forest;Forest;Forest
            ailibrary=Forest;Forest;Forest
            """,
        ),
        RoundTripBoard(
            "Role token attached to a creature",
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Grizzly Bears|Id:101;T:role_wicked|Id:102|AttachedTo:101
            humanlibrary=Forest;Forest;Forest
            ailibrary=Forest;Forest;Forest
            """,
        ),
        RoundTripBoard(
            "types and power changed by a resolved spell",
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanprecast=Turn to Frog->201
            aibattlefield=Grizzly Bears|Id:201
            humanlibrary=Island;Island;Island
            ailibrary=Forest;Forest;Forest
            """,
        ),
        RoundTripBoard(
            "marked damage carried by temporary toughness",
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanprecast=Giant Growth->101
            humanbattlefield=Grizzly Bears|Id:101|Damage:3
            humanlibrary=Forest;Forest;Forest
            ailibrary=Forest;Forest;Forest
            """,
        ),
        RoundTripBoard(
            "Blood token with its draw ability",
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=T:c_a_blood_draw|Id:101
            humanlibrary=Swamp;Swamp;Swamp
            ailibrary=Forest;Forest;Forest
            """,
        ),
    )
