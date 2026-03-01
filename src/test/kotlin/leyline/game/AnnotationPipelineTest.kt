package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.GameBootstrap
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Unit tests for the three-stage annotation pipeline extracted from
 * [StateMapper.buildFromGame]. Tests [AnnotationPipeline.annotationsForTransfer]
 * as a pure function — no game engine, no bridge, no card DB.
 *
 * Each test constructs an [AnnotationPipeline.AppliedTransfer] and verifies the
 * annotation sequence matches the real client server pattern.
 */
class AnnotationPipelineTest :
    FunSpec({

        // StateMapper's Kotlin WhenMappings clinit references PhaseType, which
        // requires the card DB to be loaded. Bootstrap once for the whole class.
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        /** Identity resolver for unit tests — forgeCardId maps to forgeCardId + 1000. */
        fun testResolver(forgeCardId: Int): Int = forgeCardId + 1000

        // --- annotationsForTransfer: PlayLand ---

        test("playLandProducesThreeAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.PlayLand,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 12345,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 3
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            annotations[2].typeList.first() shouldBe AnnotationType.UserActionTaken

            // UserActionTaken should have actionType=3 (Play)
            val actionType = annotations[2].detailsList.first { it.key == "actionType" }
            actionType.getValueInt32(0) shouldBe 3
        }

        test("playLandHasCorrectIds") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.PlayLand,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 12345,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            // ObjectIdChanged should reference origId in affectedIds
            annotations[0].affectedIdsList shouldContain 100
            // ZoneTransfer should reference newId
            annotations[1].affectedIdsList shouldContain 200
        }

        test("playLandProducesPersistentAnnotation") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.PlayLand,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 12345,
                ownerSeatId = 1,
            )
            val (_, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            persistent.size shouldBe 1
            persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
        }

        // --- annotationsForTransfer: CastSpell ---

        test("castSpellProducesSixAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.STACK,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 6
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            annotations[2].typeList.first() shouldBe AnnotationType.AbilityInstanceCreated
            annotations[3].typeList.first() shouldBe AnnotationType.ManaPaid
            annotations[4].typeList.first() shouldBe AnnotationType.AbilityInstanceDeleted
            annotations[5].typeList.first() shouldBe AnnotationType.UserActionTaken

            // Stack is not battlefield — no persistent annotation
            persistent.shouldBeEmpty()
        }

        test("castSpellUserActionIsCast") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.STACK,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            val actionType = annotations[5].detailsList.first { it.key == "actionType" }
            actionType.getValueInt32(0) shouldBe 1
        }

        // --- annotationsForTransfer: Resolve ---

        test("resolveProducesThreeAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 3
            annotations[0].typeList.first() shouldBe AnnotationType.ResolutionStart
            annotations[1].typeList.first() shouldBe AnnotationType.ResolutionComplete
            annotations[2].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a

            // Lands on battlefield — persistent annotation
            persistent.size shouldBe 1
        }

        test("resolveZoneTransferHasActingSeat") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 2)

            // Resolve ZoneTransfer should carry actingSeat as affectorId
            annotations[2].affectorId shouldBe 2
        }

        test("resolveUsesGrpId") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            val grpid = annotations[0].detailsList.first { it.key == "grpid" }
            grpid.getValueUint32(0) shouldBe 67890
        }

        // --- Edge cases ---

        test("genericZoneTransferProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.ZoneTransfer,
                srcZoneId = ZoneIds.EXILE,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            // ZoneTransfer category produces ObjectIdChanged (when origId != newId) + ZoneTransfer
            annotations.size shouldBe 2
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            persistent.shouldBeEmpty()
        }

        test("noBattlefieldDestNoPersistentAnnotation") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.STACK,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (_, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            persistent.shouldBeEmpty()
        }

        test("resolveToGraveyardNoPersistentAnnotation") {
            // Spell resolves but goes to graveyard (instant/sorcery)
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 3
            persistent.shouldBeEmpty()
        }

        // --- Stage 4: mechanicAnnotations (Group B) ---

        // -- CountersChanged --

        test("counterAddedAnnotation") {
            val events = listOf(
                GameEvent.CountersChanged(forgeCardId = 42, counterType = "P1P1", oldCount = 0, newCount = 2),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, ::testResolver)

            result.transient.size shouldBe 1
            result.transient[0].typeList shouldContain AnnotationType.CounterAdded
            result.transient[0].affectedIdsList shouldContain 1042

            val counterType = result.transient[0].detailsList.first { it.key == "counter_type" }
            counterType.getValueString(0) shouldBe "P1P1"
            val txnAmount = result.transient[0].detailsList.first { it.key == "transaction_amount" }
            txnAmount.getValueInt32(0) shouldBe 2

            // Persistent: Counter state annotation with current count
            result.persistent.size shouldBe 1
            result.persistent[0].typeList shouldContain AnnotationType.Counter_803b
            val count = result.persistent[0].detailsList.first { it.key == "count" }
            count.getValueInt32(0) shouldBe 2
        }

        test("counterRemovedAnnotation") {
            val events = listOf(
                GameEvent.CountersChanged(forgeCardId = 42, counterType = "LOYALTY", oldCount = 5, newCount = 2),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.CounterRemoved
            val txnAmount = annotations[0].detailsList.first { it.key == "transaction_amount" }
            txnAmount.getValueInt32(0) shouldBe 3
        }

        test("counterUnchangedSkipped") {
            val events = listOf(
                GameEvent.CountersChanged(forgeCardId = 42, counterType = "P1P1", oldCount = 3, newCount = 3),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient
            annotations.shouldBeEmpty()
        }

        // -- LibraryShuffled --

        xtest("shuffleAnnotation") {
            // Shuffle annotations are suppressed in production (crash client). See commit 76d61d2973.
            val events = listOf(
                GameEvent.LibraryShuffled(seatId = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.Shuffle
            annotations[0].affectedIdsList shouldContain 1
        }

        // -- Scry --

        test("scryAnnotation") {
            val events = listOf(
                GameEvent.Scry(seatId = 2, topCount = 1, bottomCount = 2),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.Scry_af5a
            val top = annotations[0].detailsList.first { it.key == "topCount" }
            top.getValueInt32(0) shouldBe 1
            val bottom = annotations[0].detailsList.first { it.key == "bottomCount" }
            bottom.getValueInt32(0) shouldBe 2
        }

        // -- Surveil --

        test("surveilAnnotation") {
            val events = listOf(
                GameEvent.Surveil(seatId = 1, toLibrary = 1, toGraveyard = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.Scry_af5a
        }

        // -- TokenCreated --

        test("tokenCreatedAnnotation") {
            val events = listOf(
                GameEvent.TokenCreated(forgeCardId = 99, seatId = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TokenCreated
            annotations[0].affectedIdsList shouldContain 1099
        }

        // -- TokenDestroyed --

        test("tokenDestroyedProducesAnnotation") {
            val events = listOf(
                GameEvent.TokenDestroyed(forgeCardId = 88, seatId = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TokenDeleted
            annotations[0].affectorId shouldBe 1088
            annotations[0].affectedIdsList shouldContain 1088
        }

        // -- PowerToughnessChanged --

        test("powerToughnessChangedBothAnnotations") {
            val events = listOf(
                GameEvent.PowerToughnessChanged(forgeCardId = 50, oldPower = 2, newPower = 4, oldToughness = 3, newToughness = 5),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

            annotations.size shouldBe 3
            annotations[0].typeList shouldContain AnnotationType.ModifiedPower
            annotations[0].affectedIdsList shouldContain 1050
            annotations[0].detailsCount shouldBe 0

            annotations[1].typeList shouldContain AnnotationType.ModifiedToughness
            annotations[1].detailsCount shouldBe 0

            annotations[2].typeList shouldContain AnnotationType.PowerToughnessModCreated
        }

        test("powerOnlyChangedOneAnnotation") {
            val events = listOf(
                GameEvent.PowerToughnessChanged(forgeCardId = 50, oldPower = 2, newPower = 5, oldToughness = 3, newToughness = 3),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

            annotations.size shouldBe 2
            annotations[0].typeList shouldContain AnnotationType.ModifiedPower
            annotations[1].typeList shouldContain AnnotationType.PowerToughnessModCreated
        }

        test("toughnessOnlyChangedOneAnnotation") {
            val events = listOf(
                GameEvent.PowerToughnessChanged(forgeCardId = 50, oldPower = 2, newPower = 2, oldToughness = 3, newToughness = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

            annotations.size shouldBe 2
            annotations[0].typeList shouldContain AnnotationType.ModifiedToughness
            annotations[1].typeList shouldContain AnnotationType.PowerToughnessModCreated
        }

        // -- CardAttached --

        test("attachProducesCorrectAnnotationShape") {
            val events = listOf(
                GameEvent.CardAttached(forgeCardId = 55, targetForgeId = 66, seatId = 1),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, ::testResolver)

            // Transient: AttachmentCreated
            result.transient.size shouldBe 1
            val created = result.transient[0]
            created.typeList shouldContain AnnotationType.AttachmentCreated
            created.affectorId shouldBe 0
            created.affectedIdsList shouldBe listOf(testResolver(55), testResolver(66))

            // Persistent: Attachment
            result.persistent.size shouldBe 1
            val attach = result.persistent[0]
            attach.typeList shouldContain AnnotationType.Attachment
            attach.affectorId shouldBe 0
            attach.affectedIdsList shouldBe listOf(testResolver(55), testResolver(66))
        }

        test("detachReturnsDetachedForgeCardId") {
            val events = listOf(
                GameEvent.CardDetached(forgeCardId = 60, seatId = 1),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, ::testResolver)
            result.detachedForgeCardIds shouldBe listOf(60)
        }

        // -- RemoveAttachment --

        test("detachProducesRemoveAttachment") {
            val events = listOf(
                GameEvent.CardDetached(forgeCardId = 60, seatId = 1),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, ::testResolver)
            val annotations = result.transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.RemoveAttachment
            annotations[0].affectedIdsList shouldContain testResolver(60)
        }

        // -- Mixed events --

        test("mechanicAnnotationsIgnoresZoneTransferEvents") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 1, from = forge.game.zone.ZoneType.Hand, to = forge.game.zone.ZoneType.Battlefield),
                GameEvent.LandPlayed(forgeCardId = 1, seatId = 1),
                GameEvent.CardDestroyed(forgeCardId = 2, seatId = 1),
                GameEvent.DamageDealtToPlayer(sourceForgeId = 4, targetSeatId = 1, amount = 3, combat = true),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient
            annotations.shouldBeEmpty()
        }

        // -- CardTapped --

        test("cardTappedProducesAnnotation") {
            val events = listOf(
                GameEvent.CardTapped(forgeCardId = 70, tapped = true),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TappedUntappedPermanent
            annotations[0].affectedIdsList shouldContain testResolver(70)
            val tapped = annotations[0].detailsList.first { it.key == "tapped" }
            tapped.getValueUint32(0) shouldBe 1
        }

        test("cardUntappedProducesAnnotation") {
            val events = listOf(
                GameEvent.CardTapped(forgeCardId = 71, tapped = false),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TappedUntappedPermanent
            val tapped = annotations[0].detailsList.first { it.key == "tapped" }
            tapped.getValueUint32(0) shouldBe 0
        }

        test("mechanicAnnotationsMultipleEvents") {
            // NOTE: LibraryShuffled is suppressed in production (crash client). See commit 76d61d2973.
            // Only testing CounterChanged + Scry here (2 events → 2 annotations).
            val events = listOf(
                GameEvent.CountersChanged(forgeCardId = 42, counterType = "P1P1", oldCount = 0, newCount = 1),
                GameEvent.Scry(seatId = 1, topCount = 2, bottomCount = 0),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient
            annotations.size shouldBe 2
            annotations[0].typeList shouldContain AnnotationType.CounterAdded
            annotations[1].typeList shouldContain AnnotationType.Scry_af5a
        }

        // --- annotationsForTransfer: new zone-specific categories ---

        test("destroyProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Destroy,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            val category = annotations[1].detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Destroy"
            persistent.shouldBeEmpty()
        }

        test("sacrificeProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Sacrifice,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            val category = annotations.last().detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Sacrifice"
        }

        test("bounceProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Bounce,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_HAND,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            val category = annotations.last().detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Bounce"
        }

        test("exileProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Exile,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.EXILE,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            val category = annotations.last().detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Exile"
        }

        test("discardProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Discard,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            val category = annotations.last().detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Discard"
        }

        test("drawProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Draw,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.P1_HAND,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            val category = annotations.last().detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Draw"
        }

        test("millProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Mill,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            val category = annotations.last().detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Mill"
        }

        test("counteredProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Countered,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            val category = annotations.last().detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Countered"
        }

        // --- annotationsForTransfer: Return ---

        test("returnFromGraveyardProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Return,
                srcZoneId = ZoneIds.P1_GRAVEYARD,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            val category = annotations[1].detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Return"
            persistent.size shouldBe 1
        }

        test("returnToHandNoPersistent") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Return,
                srcZoneId = ZoneIds.P1_GRAVEYARD,
                destZoneId = ZoneIds.P1_HAND,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            val category = annotations.last().detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Return"
            persistent.shouldBeEmpty()
        }

        // --- annotationsForTransfer: Search ---

        test("searchProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Search,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            val category = annotations[1].detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Search"
            persistent.size shouldBe 1
        }

        // --- annotationsForTransfer: Put ---

        test("putProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Put,
                srcZoneId = ZoneIds.EXILE,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            val category = annotations[1].detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "Put"
            persistent.shouldBeEmpty()
        }
    })
