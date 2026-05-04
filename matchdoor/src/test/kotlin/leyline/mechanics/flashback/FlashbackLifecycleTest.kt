package leyline.mechanics.flashback

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

private val PUZZLE =
    """
    [metadata]
    Name:Flashback Think Twice - Full Lifecycle
    Goal:Cast from hand, then flashback from GY. Drawn creature is win condition.
    Turns:5
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=2

    humanhand=Think Twice
    humanbattlefield=Island;Island;Island;Island;Island;Island
    humanlibrary=Coral Merfolk;Plains;Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

class FlashbackLifecycleTest :
    SessionTest({
        test("hand cast goes to graveyard, flashback cast exiles") {
            startPuzzleRaw(PUZZLE, validating = false)

            val handBefore = human.getZone(ZoneType.Hand).size()
            castSpellByName("Think Twice").shouldBeTrue()
            passPriority()

            assertSoftly {
                human.getZone(ZoneType.Hand).size() shouldBe handBefore
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .any { it.name == "Coral Merfolk" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Think Twice" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Exile)
                    .cards
                    .none { it.name == "Think Twice" }
                    .shouldBeTrue()
            }

            val handBeforeFlashback = human.getZone(ZoneType.Hand).size()
            castFromGraveyard("Think Twice").shouldBeTrue()
            passPriority()

            assertSoftly {
                human.getZone(ZoneType.Hand).size() shouldBe handBeforeFlashback + 1
                human
                    .getZone(ZoneType.Exile)
                    .cards
                    .any { it.name == "Think Twice" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .none { it.name == "Think Twice" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .any { it.name == "Coral Merfolk" }
                    .shouldBeTrue()
            }
        }
    })
