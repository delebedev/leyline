package leyline.behavior.mechanics.abilitywordactive

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.annotationsOfType
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.detailUint
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

            val triggerMessages =
                after {
                    passUntil(maxPasses = 60) {
                        allMessages.annotationsOfType(AnnotationType.AbilityInstanceCreated).any {
                            it.affectorId == sourceIid
                        }
                    }.shouldBeTrue()
                }.messages
            val abilityCreated =
                triggerMessages.annotationsOfType(AnnotationType.AbilityInstanceCreated).single {
                    it.affectorId == sourceIid
                }
            val abilityIid = abilityCreated.affectedIdsList.single()
            val triggerMarker =
                triggerMessages
                    .persistentAnnotationsOfType(AnnotationType.AbilityWordActive)
                    .single { abilityIid in it.affectedIdsList }

            assertSoftly {
                triggerMarker.id shouldBe marker.id
                triggerMarker.affectorId shouldBe 1
                triggerMarker.affectedIdsList shouldBe listOf(sourceIid, abilityIid)
                triggerMessages
                    .persistentAnnotationsOfType(AnnotationType.TriggeringObject)
                    .filter { it.affectorId == abilityIid }
                    .shouldHaveSize(0)
            }

            val turnMessages =
                after {
                    passUntil(maxPasses = 60) { turn() > 1 }.shouldBeTrue()
                }.messages
            val resolutionMessages = triggerMessages + turnMessages
            val counterAdded = resolutionMessages.annotationsOfType(AnnotationType.CounterAdded).single()
            val resolutionStart =
                resolutionMessages.annotationsOfType(AnnotationType.ResolutionStart).single { it.affectorId == abilityIid }
            val resolutionComplete =
                resolutionMessages.annotationsOfType(AnnotationType.ResolutionComplete).single { it.affectorId == abilityIid }
            val abilityDeleted =
                resolutionMessages.annotationsOfType(AnnotationType.AbilityInstanceDeleted).single {
                    abilityIid in it.affectedIdsList
                }

            assertSoftly {
                source.getCounters(CounterEnumType.P1P1) shouldBe 1
                counterAdded.affectorId shouldBe abilityIid
                counterAdded.affectedIdsList shouldBe listOf(sourceIid)
                counterAdded.detailInt("transaction_amount") shouldBe 1
                resolutionStart.affectorId shouldBe abilityIid
                resolutionStart.detailUint("grpid") shouldBe 190962
                resolutionComplete.affectorId shouldBe abilityIid
                resolutionComplete.detailUint("grpid") shouldBe 190962
                abilityDeleted.affectedIdsList shouldBe listOf(abilityIid)
                resolutionMessages.deletedPersistentAnnotationIds() shouldContain marker.id
                turnMessages
                    .persistentAnnotationsOfType(AnnotationType.AbilityWordActive)
                    .filter { it.detailString("AbilityWordName") == "Void" }
                    .shouldHaveSize(0)
            }
        }
    })
