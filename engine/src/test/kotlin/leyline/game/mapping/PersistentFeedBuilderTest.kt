package leyline.game.mapping

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.state.DelayedTriggerAffecteesKind

class PersistentFeedBuilderTest :
    FunSpec({

        tags(UnitTag)

        test("delayed trigger affectees remain fed while their ability is on the stack") {
            val affectees =
                AnnotationBuilder
                    .delayedTriggerAffectees(
                        triggerHolderId = InstanceId(124),
                        tokenInstanceIds = listOf(InstanceId(55)),
                        abilityGrpId = GrpId(136220),
                        removesFromZone = null,
                    ).toBuilder()
                    .setId(10)
                    .build()

            val retained =
                PersistentFeedBuilder.retainDelayedTriggerAffectees(
                    feeds = PersistentFeedSet(),
                    activeAnnotations = listOf(affectees),
                    holderIids = setOf(124),
                )

            retained[DelayedTriggerAffecteesKind] shouldBe listOf(affectees)
        }

        test("delayed trigger affectees stop feeding after their ability leaves the stack") {
            val affectees =
                AnnotationBuilder
                    .delayedTriggerAffectees(
                        triggerHolderId = InstanceId(124),
                        tokenInstanceIds = listOf(InstanceId(55)),
                        abilityGrpId = GrpId(136220),
                        removesFromZone = null,
                    ).toBuilder()
                    .setId(10)
                    .build()

            val retained =
                PersistentFeedBuilder.retainDelayedTriggerAffectees(
                    feeds = PersistentFeedSet(),
                    activeAnnotations = listOf(affectees),
                    holderIids = emptySet(),
                )

            retained[DelayedTriggerAffecteesKind].shouldBeEmpty()
        }
    })
