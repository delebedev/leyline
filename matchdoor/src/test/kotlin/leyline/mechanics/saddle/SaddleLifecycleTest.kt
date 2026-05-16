package leyline.mechanics.saddle

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.DetailKeys
import leyline.testkit.SessionTest
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import leyline.testkit.detailUint
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
        test("saddle activation taps helper and emits saddled annotations") {
            startPuzzleRaw(PUZZLE, validating = true)

            activateAbility("Drover Grizzly").shouldBeTrue()
            passUntilResolved(maxPasses = 4)

            val grizzly = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Drover Grizzly" }
            val helper = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val saddledAnn =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.SaddledThisTurn)
                    .firstOrNull { it.affectorId == human.battlefield.iid(grizzly) }
            val saddledDesignation =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.Designation)
                    .firstOrNull {
                        it.affectorId == human.battlefield.iid(grizzly) &&
                            it.detailInt(DetailKeys.DESIGNATION_TYPE) == AnnotationConstants.DESIGNATION_TYPE_SADDLED
                    }
            val gainSaddled =
                allMessages
                    .annotationsOfType(AnnotationType.GainDesignation)
                    .firstOrNull {
                        it.affectorId == human.battlefield.iid(grizzly) &&
                            it.detailInt(DetailKeys.DESIGNATION_TYPE) == AnnotationConstants.DESIGNATION_TYPE_SADDLED
                    }

            assertSoftly {
                grizzly.isSaddled.shouldBeTrue()
                helper.isTapped.shouldBeTrue()
                saddledDesignation.shouldNotBeNull()
                gainSaddled.shouldNotBeNull()
                saddledAnn!!.affectedIdsList shouldBe listOf(human.battlefield.iid(helper))
                saddledAnn!!.affectedIdsList shouldContain human.battlefield.iid(helper)
            }
        }

        test("saddled attack condition grants trample") {
            startPuzzleRaw(PUZZLE, validating = true)

            activateAbility("Drover Grizzly").shouldBeTrue()
            passUntilResolved(maxPasses = 4)

            val grizzly = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Drover Grizzly" }
            harness.advanceToCombat(turn = 1)
            declareAttackers(listOf(human.battlefield.iid(grizzly)))
            passUntilResolved(maxPasses = 4)

            val trampleGrant =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.AddAbility_af5a)
                    .firstOrNull { ann ->
                        ann.detailUint("grpid") == 14 &&
                            ann.affectedIdsList.contains(human.battlefield.iid(grizzly))
                    }

            assertSoftly {
                trampleGrant.shouldNotBeNull()
                trampleGrant.detailUint("grpid") shouldBe 14
                trampleGrant.affectedIdsList shouldContain human.battlefield.iid(grizzly)
            }
        }

        test("saddled state expires after turn changes") {
            startPuzzleRaw(PUZZLE, validating = true)

            activateAbility("Drover Grizzly").shouldBeTrue()
            passUntilResolved(maxPasses = 4)
            passUntilTurn(2, maxPasses = 20)

            val grizzly = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Drover Grizzly" }
            withClue("turn=${turn()} phase=${phase()} stack=${game().stack.map { it.sourceCard.name }}") {
                grizzly.isSaddled shouldBe false
            }
        }
    })
