package leyline.testkit

import forge.game.zone.ZoneType
import io.kotest.assertions.shouldFail
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import io.kotest.matchers.string.shouldContain

@Suppress(
    // Matcher tests verify failure-message shape via `shouldFail { ... }.message
    // shouldContain "..."` — the assertion shape IS what's under test. Detekt
    // doesn't recognize this pattern, so suppress at class level.
    "WeakAssertionOnly",
    "MissingAssertSoftly",
)
class ZoneMatchersTest :
    BoardTest({

        test("beInHandOf passes when card is present, fails with naming message when absent") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }
            val human = board.human

            "Grizzly Bears" should beInHandOf(human)

            val failure =
                shouldFail {
                    "Coral Merfolk" should beInHandOf(human)
                }
            failure.message shouldContain "'Coral Merfolk'"
            failure.message shouldContain "Hand"
        }

        test("beOnBattlefieldOf with count enforces exact cardinality") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val human = board.human

            "Forest" should beOnBattlefieldOf(human, count = 2)
            "Forest" should beOnBattlefieldOf(human) // unbounded ≥1 still passes

            val tooFew =
                shouldFail {
                    "Forest" should beOnBattlefieldOf(human, count = 3)
                }
            tooFew.message shouldContain "3 copies of 'Forest'"
            tooFew.message shouldContain "found 2"

            val tooMany =
                shouldFail {
                    "Forest" should beOnBattlefieldOf(human, count = 1)
                }
            tooMany.message shouldContain "1 copies of 'Forest'"
            tooMany.message shouldContain "found 2"
        }

        test("shouldNot inversion fires the negation message") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Mountain", human, ZoneType.Hand)
                }
            val human = board.human

            "Mountain" shouldNot beInGraveyardOf(human)

            val failure =
                shouldFail {
                    "Mountain" shouldNot beInHandOf(human)
                }
            failure.message shouldContain "'Mountain' should not be in"
            failure.message shouldContain "Hand"
        }

        test("beInZoneOf works for less common zones (Library, Exile, Command)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Library)
                    addCard("Swamp", human, ZoneType.Exile)
                }
            val human = board.human

            "Plains" should beInLibraryOf(human)
            "Swamp" should beInExileOf(human)
            "Plains" shouldNot beInExileOf(human)
        }
    })
