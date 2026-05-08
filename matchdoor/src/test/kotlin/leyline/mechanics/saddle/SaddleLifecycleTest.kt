package leyline.mechanics.saddle

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

private val PUZZLE =
    """
    [metadata]
    Name:Saddle Drover Grizzly
    Goal:Saddle Drover Grizzly with a helper creature.
    Turns:2
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Forest
    humanbattlefield=Drover Grizzly;Grizzly Bears
    humanlibrary=Forest;Forest;Forest;Forest
    ailibrary=Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

class SaddleLifecycleTest :
    SessionTest({
        test("saddle activation taps helper and emits SaddledThisTurn") {
            startPuzzleRaw(PUZZLE, validating = true)

            activateAbility("Drover Grizzly").shouldBeTrue()
            passUntilResolved(maxPasses = 4)

            val grizzly = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Drover Grizzly" }
            val helper = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val saddledAnn =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.SaddledThisTurn)
                    .firstOrNull { it.affectorId == human.battlefield.iid(grizzly) }

            assertSoftly {
                grizzly.isSaddled.shouldBeTrue()
                helper.isTapped.shouldBeTrue()
                saddledAnn!!.affectedIdsList shouldBe listOf(human.battlefield.iid(helper))
                saddledAnn!!.affectedIdsList shouldContain human.battlefield.iid(helper)
            }
        }

        test("saddled state expires after turn changes") {
            startPuzzleRaw(PUZZLE, validating = true)

            activateAbility("Drover Grizzly").shouldBeTrue()
            passUntilResolved(maxPasses = 4)
            passUntilTurn(2, maxPasses = 20)

            val grizzly = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Drover Grizzly" }
            grizzly.isSaddled shouldBe false
        }
    })
