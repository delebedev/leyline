package leyline.mechanics.reconfigure

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.allPersistentAnnotations
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CardType

class ReconfigureLifecycleTest :
    SessionTest({
        session(
            "Rabbit Battery attaches and unattaches through Reconfigure",
            puzzleFile = "data/puzzles/reconfigure-rabbit-battery.pzl",
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
            }
        }
    })
