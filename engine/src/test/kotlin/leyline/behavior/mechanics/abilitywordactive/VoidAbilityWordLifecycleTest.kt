package leyline.behavior.mechanics.abilitywordactive

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailString
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class VoidAbilityWordLifecycleTest :
    SessionTest({
        test("Void marker binds the controller to its source and is deleted next turn") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Insatiable Skittermaw;Mountain
                humanhand=Shock
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Forest;Forest;Forest
                """,
                name = "Void lifecycle",
                turns = 3,
            )
            val sourceIid = instanceIdOf("Insatiable Skittermaw")
            val source = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Insatiable Skittermaw" }
            val target = instanceIdOf("Grizzly Bears", ai)

            val activeMessages =
                after {
                    castSpellByName("Shock").shouldBeTrue()
                    selectTargetsIterative(listOf(target))
                    submitTargets()
                    passUntilResolved(maxPasses = 20)
                }.messages
            val marker =
                activeMessages
                    .persistentAnnotationsOfType(AnnotationType.AbilityWordActive)
                    .single { it.detailString("AbilityWordName") == "Void" && it.affectorId == 1 }

            assertSoftly {
                marker.affectedIdsList shouldBe listOf(sourceIid)
                marker.detailsList.map { it.key }.toSet() shouldBe setOf("AbilityWordName")
                marker.affectorId shouldBe 1
            }

            val turnMessages =
                after {
                    passUntil(maxPasses = 60) { turn() > 1 }.shouldBeTrue()
                }.messages

            assertSoftly {
                source.getCounters(CounterEnumType.P1P1) shouldBe 1
                turnMessages.deletedPersistentAnnotationIds() shouldContain marker.id
                turnMessages
                    .persistentAnnotationsOfType(AnnotationType.AbilityWordActive)
                    .filter { it.detailString("AbilityWordName") == "Void" }
                    .shouldHaveSize(0)
            }
        }
    })
