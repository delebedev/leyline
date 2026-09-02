package leyline.mechanics.reconfigure

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.allPersistentAnnotations
import leyline.testkit.annotationsOfType
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CardType

class ReconfigureLifecycleTest :
    SessionTest({
        session(
            "Rabbit Battery attaches and unattaches through Reconfigure",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Rabbit Battery;Grizzly Bears;Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val rabbitIid = human.battlefield.iid("Rabbit Battery")
            val bearIid = human.battlefield.iid("Grizzly Bears")

            val attachSlice =
                after {
                    activateAbility("Rabbit Battery", abilityIndex = 0).shouldBeTrue()
                    selectTargets(listOf(bearIid))
                }.messages

            val attachedObject = accumulator.objects[rabbitIid].shouldNotBeNull()
            val attachedPersistent = attachSlice.allPersistentAnnotations()
            val attachTypes = attachSlice.gameStateMessages().flatMap { it.annotationsList }.flatMap { it.typeList }
            val targetSpec = attachSlice.allPersistentAnnotations().single { AnnotationType.TargetSpec in it.typeList }
            val abilityCreated =
                attachSlice.annotationsOfType(AnnotationType.AbilityInstanceCreated).single {
                    targetSpec.affectorId in it.affectedIdsList
                }
            val selecting = attachSlice.annotationsOfType(AnnotationType.PlayerSelectingTargets).single()
            val submitted = attachSlice.annotationsOfType(AnnotationType.PlayerSubmittedTargets).single()
            val abilityCreatedFrame =
                attachSlice.indexOfFirst {
                    it.hasGameStateMessage() &&
                        it.gameStateMessage.annotationsList.any { annotation ->
                            AnnotationType.AbilityInstanceCreated in annotation.typeList &&
                                targetSpec.affectorId in annotation.affectedIdsList
                        }
                }
            val selectingFrame =
                attachSlice.indexOfFirst {
                    it.hasGameStateMessage() &&
                        it.gameStateMessage.annotationsList.any { annotation ->
                            AnnotationType.PlayerSelectingTargets in annotation.typeList
                        }
                }
            val promptFrame = attachSlice.indexOfFirst { it.hasSelectTargetsReq() }
            val submittedFrame =
                attachSlice.indexOfFirst {
                    it.hasGameStateMessage() &&
                        it.gameStateMessage.annotationsList.any { annotation ->
                            AnnotationType.PlayerSubmittedTargets in annotation.typeList
                        }
                }
            val targetSpecFrame =
                attachSlice.indexOfFirst {
                    it.hasGameStateMessage() &&
                        it.gameStateMessage.persistentAnnotationsList.any { annotation ->
                            AnnotationType.TargetSpec in annotation.typeList
                        }
                }
            val timeline =
                attachSlice.mapIndexed { index, message ->
                    val gsm = message.takeIf { it.hasGameStateMessage() }?.gameStateMessage
                    "$index:${message.type} transient=${gsm?.annotationsList?.flatMap { it.typeList }} " +
                        "persistent=${gsm?.persistentAnnotationsList?.flatMap { it.typeList }}"
                }

            withClue(timeline.joinToString("\n")) {
                assertSoftly {
                    abilityCreatedFrame shouldBe selectingFrame
                    selectingFrame shouldBeGreaterThanOrEqual 0
                    promptFrame shouldBeGreaterThan selectingFrame
                    submittedFrame shouldBeGreaterThan promptFrame
                    targetSpecFrame shouldBe submittedFrame
                    selecting.affectedIdsList shouldContain targetSpec.affectorId
                    submitted.affectedIdsList shouldContain targetSpec.affectorId
                    abilityCreated.affectedIdsList shouldContain targetSpec.affectorId
                    attachSlice.deletedPersistentAnnotationIds() shouldContain targetSpec.id
                }
            }
            assertSoftly {
                attachedObject.cardTypesList shouldContain CardType.Artifact_a80b
                attachedObject.cardTypesList shouldNotContain CardType.Creature
                attachedObject.parentId shouldBe bearIid
                attachedPersistent
                    .any {
                        AnnotationType.Attachment in it.typeList && it.affectorId == rabbitIid && bearIid in it.affectedIdsList
                    }.shouldBeTrue()
                attachedPersistent
                    .any {
                        AnnotationType.ModifiedType in it.typeList && rabbitIid in it.affectedIdsList
                    }.shouldBeTrue()
                attachTypes shouldContain AnnotationType.AttachmentCreated
                attachTypes shouldContain AnnotationType.LayeredEffectCreated
                human
                    .getZone(forge.game.zone.ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Grizzly Bears" }
                    .netPower shouldBe 3
            }

            val unattachSlice =
                after {
                    activateAbility("Rabbit Battery", abilityIndex = 0).shouldBeTrue()
                    passUntilResolved()
                }.messages

            val unattachedObject = accumulator.objects[rabbitIid].shouldNotBeNull()
            val removeAttachment = allMessages.annotationsOfType(AnnotationType.RemoveAttachment).lastOrNull()
            val allActivePersistent =
                bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations
                    .activeAnnotations
                    .values

            assertSoftly {
                unattachedObject.cardTypesList shouldContain CardType.Artifact_a80b
                unattachedObject.cardTypesList shouldContain CardType.Creature
                unattachedObject.parentId shouldBe 0
                removeAttachment.shouldNotBeNull().affectorId shouldBe rabbitIid
                removeAttachment.affectedIdsList shouldBe listOf(bearIid)
                removeAttachment.detailInt(DetailKeys.INVALIDATING_GRPID) shouldBe 244
                unattachSlice
                    .annotationsOfType(AnnotationType.LayeredEffectDestroyed)
                    .shouldNotBeEmpty()
                allActivePersistent
                    .none {
                        AnnotationType.Attachment in it.typeList && it.affectorId == rabbitIid
                    }.shouldBeTrue()
                allActivePersistent
                    .none {
                        AnnotationType.ModifiedType in it.typeList && rabbitIid in it.affectedIdsList
                    }.shouldBeTrue()
                human
                    .getZone(forge.game.zone.ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Grizzly Bears" }
                    .netPower shouldBe 2
            }
        }
    })
