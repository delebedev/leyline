package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
import leyline.conformance.detailInt
import leyline.conformance.detailString
import leyline.conformance.detailUint
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Mechanic-stage annotation pipeline tests — counters, shuffle, scry, surveil,
 * tokens, power/toughness, attach/detach, tap/untap, and mixed mechanic events.
 */
class MechanicAnnotationPipelineTest :
    FunSpec({

        tags(UnitTag)

        /** Identity resolver for unit tests — forgeCardId maps to forgeCardId + 1000. */
        fun testResolver(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value + 1000)

        // -- CountersChanged --

        test("counterAddedAnnotation") {
            // Forge sends display name "+1/+1" (from CounterEnumType.getName()), not "P1P1"
            val events = listOf(
                GameEvent.CountersChanged(cardId = ForgeCardId(42), counterType = "+1/+1", oldCount = 0, newCount = 2),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)

            assertSoftly {
                result.transient.size shouldBe 1
                result.transient[0].typeList shouldContain AnnotationType.CounterAdded
                result.transient[0].affectedIdsList shouldContain 1042
                result.transient[0].detailString("counter_type") shouldBe "+1/+1"
                result.transient[0].detailInt("transaction_amount") shouldBe 2
            }

            // Persistent: Counter state annotation with current count and correct enum value
            assertSoftly {
                result.persistent.size shouldBe 1
                result.persistent[0].typeList shouldContain AnnotationType.Counter_803b
                result.persistent[0].detailInt("count") shouldBe 2
                result.persistent[0].detailInt("counter_type") shouldBe 1 // P1P1 = 1
            }
        }

        test("counterRemovedAnnotation") {
            // Forge sends "LOYAL" for loyalty counters (CounterEnumType.LOYALTY.getName())
            val events = listOf(
                GameEvent.CountersChanged(cardId = ForgeCardId(42), counterType = "LOYAL", oldCount = 5, newCount = 2),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.CounterRemoved
            annotations[0].detailInt("transaction_amount") shouldBe 3
        }

        test("counterUnchangedSkipped") {
            val events = listOf(
                GameEvent.CountersChanged(cardId = ForgeCardId(42), counterType = "P1P1", oldCount = 3, newCount = 3),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient
            annotations.shouldBeEmpty()
        }

        // -- LibraryShuffled --

        xtest("shuffleAnnotation") {
            // Shuffle annotations are suppressed in production (crash client). See commit 76d61d2973.
            val events = listOf(
                GameEvent.LibraryShuffled(seatId = SeatId(1)),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.Shuffle
            annotations[0].affectedIdsList shouldContain 1
        }

        // -- Scry --

        test("scryAnnotation") {
            val events = listOf(
                GameEvent.Scry(seatId = SeatId(2), topCount = 1, bottomCount = 2),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.Scry_af5a
            annotations[0].detailInt("topCount") shouldBe 1
            annotations[0].detailInt("bottomCount") shouldBe 2
        }

        // -- Surveil --

        test("surveilAnnotation") {
            val events = listOf(
                GameEvent.Surveil(seatId = SeatId(1), toLibrary = 1, toGraveyard = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.Scry_af5a
        }

        // -- TokenCreated --

        test("tokenCreatedAnnotation") {
            val events = listOf(
                GameEvent.TokenCreated(cardId = ForgeCardId(99), seatId = SeatId(1)),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TokenCreated
            annotations[0].affectedIdsList shouldContain 1099
        }

        // -- TokenDestroyed --

        test("tokenDestroyedProducesAnnotation") {
            val events = listOf(
                GameEvent.TokenDestroyed(cardId = ForgeCardId(88), seatId = SeatId(1)),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TokenDeleted
            annotations[0].affectorId shouldBe 1088
            annotations[0].affectedIdsList shouldContain 1088
        }

        // -- PowerToughnessChanged --

        test("powerToughnessChangedBothAnnotations") {
            val events = listOf(
                GameEvent.PowerToughnessChanged(cardId = ForgeCardId(50), oldPower = 2, newPower = 4, oldToughness = 3, newToughness = 5),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            assertSoftly {
                annotations.size shouldBe 3
                annotations[0].typeList shouldContain AnnotationType.ModifiedPower
                annotations[0].affectedIdsList shouldContain 1050
                annotations[0].detailsCount shouldBe 0
                annotations[1].typeList shouldContain AnnotationType.ModifiedToughness
                annotations[1].detailsCount shouldBe 0
                annotations[2].typeList shouldContain AnnotationType.PowerToughnessModCreated
            }
        }

        test("powerOnlyChangedOneAnnotation") {
            val events = listOf(
                GameEvent.PowerToughnessChanged(cardId = ForgeCardId(50), oldPower = 2, newPower = 5, oldToughness = 3, newToughness = 3),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 2
            annotations[0].typeList shouldContain AnnotationType.ModifiedPower
            annotations[1].typeList shouldContain AnnotationType.PowerToughnessModCreated
        }

        test("toughnessOnlyChangedOneAnnotation") {
            val events = listOf(
                GameEvent.PowerToughnessChanged(cardId = ForgeCardId(50), oldPower = 2, newPower = 2, oldToughness = 3, newToughness = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 2
            annotations[0].typeList shouldContain AnnotationType.ModifiedToughness
            annotations[1].typeList shouldContain AnnotationType.PowerToughnessModCreated
        }

        // -- CardAttached --

        test("attachProducesCorrectAnnotationShape") {
            val events = listOf(
                GameEvent.CardAttached(cardId = ForgeCardId(55), targetCardId = ForgeCardId(66), seatId = SeatId(1)),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)

            // Transient: AttachmentCreated
            val created = result.transient[0]
            assertSoftly {
                result.transient.size shouldBe 1
                created.typeList shouldContain AnnotationType.AttachmentCreated
                created.affectorId shouldBe testResolver(ForgeCardId(55)).value
                created.affectedIdsList shouldBe listOf(testResolver(ForgeCardId(66)).value)
            }

            // Persistent: Attachment
            val attach = result.persistent[0]
            assertSoftly {
                result.persistent.size shouldBe 1
                attach.typeList shouldContain AnnotationType.Attachment
                attach.affectorId shouldBe testResolver(ForgeCardId(55)).value
                attach.affectedIdsList shouldBe listOf(testResolver(ForgeCardId(66)).value)
            }
        }

        test("detachReturnsDetachedForgeCardId") {
            val events = listOf(
                GameEvent.CardDetached(cardId = ForgeCardId(60), seatId = SeatId(1)),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)
            result.detachedForgeCardIds shouldBe listOf(ForgeCardId(60))
        }

        // -- RemoveAttachment --

        test("detachProducesRemoveAttachment") {
            val events = listOf(
                GameEvent.CardDetached(cardId = ForgeCardId(60), seatId = SeatId(1)),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)
            val annotations = result.transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.RemoveAttachment
            annotations[0].affectedIdsList shouldContain testResolver(ForgeCardId(60)).value
        }

        // -- Mixed events --

        test("zoneTransferEventsProduceNoTransientButTrackCleanup") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(1), from = Zone.Hand, to = Zone.Battlefield),
                GameEvent.LandPlayed(cardId = ForgeCardId(1), seatId = SeatId(1)),
                GameEvent.CardDestroyed(cardId = ForgeCardId(2), seatId = SeatId(1)),
                GameEvent.DamageDealtToPlayer(sourceCardId = ForgeCardId(4), targetSeatId = SeatId(1), amount = 3, combat = true),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)
            result.transient.shouldBeEmpty()
            // CardDestroyed tracks for DisplayCardUnderCard cleanup
            result.exileSourceLeftPlayForgeCardIds shouldBe listOf(ForgeCardId(2))
        }

        // -- CardTapped --

        test("cardTappedProducesAnnotation") {
            val events = listOf(
                GameEvent.CardTapped(cardId = ForgeCardId(70), tapped = true),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TappedUntappedPermanent
            annotations[0].affectedIdsList shouldContain testResolver(ForgeCardId(70)).value
            annotations[0].detailUint("tapped") shouldBe 1
        }

        test("cardUntappedProducesAnnotation") {
            val events = listOf(
                GameEvent.CardTapped(cardId = ForgeCardId(71), tapped = false),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TappedUntappedPermanent
            annotations[0].detailUint("tapped") shouldBe 0
        }

        test("mechanicAnnotationsMultipleEvents") {
            // NOTE: LibraryShuffled is suppressed in production (crash client). See commit 76d61d2973.
            // Only testing CounterChanged + Scry here (2 events → 2 annotations).
            val events = listOf(
                GameEvent.CountersChanged(cardId = ForgeCardId(42), counterType = "P1P1", oldCount = 0, newCount = 1),
                GameEvent.Scry(seatId = SeatId(1), topCount = 2, bottomCount = 0),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient
            annotations.size shouldBe 2
            annotations[0].typeList shouldContain AnnotationType.CounterAdded
            annotations[1].typeList shouldContain AnnotationType.Scry_af5a
        }
    })
