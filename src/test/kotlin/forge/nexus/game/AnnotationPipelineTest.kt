package forge.nexus.game

import forge.nexus.game.mapper.ZoneIds
import forge.web.game.GameBootstrap
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Unit tests for the three-stage annotation pipeline extracted from
 * [StateMapper.buildFromGame]. Tests [AnnotationPipeline.annotationsForTransfer]
 * as a pure function — no game engine, no bridge, no card DB.
 *
 * Each test constructs an [AnnotationPipeline.AppliedTransfer] and verifies the
 * annotation sequence matches the real client server pattern.
 */
@Test(groups = ["unit"])
class AnnotationPipelineTest {

    // StateMapper's Kotlin WhenMappings clinit references PhaseType, which
    // requires the card DB to be loaded. Bootstrap once for the whole class.
    @BeforeClass(alwaysRun = true)
    fun initCardDatabase() {
        GameBootstrap.initializeCardDatabase(quiet = true)
    }

    // --- annotationsForTransfer: PlayLand ---

    @Test
    fun playLandProducesThreeAnnotations() {
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

        assertEquals(annotations.size, 3, "PlayLand should produce 3 annotations")
        assertEquals(annotations[0].typeList.first(), AnnotationType.ObjectIdChanged)
        assertEquals(annotations[1].typeList.first(), AnnotationType.ZoneTransfer_af5a)
        assertEquals(annotations[2].typeList.first(), AnnotationType.UserActionTaken)

        // UserActionTaken should have actionType=3 (Play)
        val actionType = annotations[2].detailsList.first { it.key == "actionType" }
        assertEquals(actionType.getValueInt32(0), 3)
    }

    @Test
    fun playLandHasCorrectIds() {
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
        assertTrue(annotations[0].affectedIdsList.contains(100))
        // ZoneTransfer should reference newId
        assertTrue(annotations[1].affectedIdsList.contains(200))
    }

    @Test
    fun playLandProducesPersistentAnnotation() {
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

        assertEquals(persistent.size, 1, "Battlefield entry should produce EnteredZoneThisTurn")
        assertEquals(persistent[0].typeList.first(), AnnotationType.EnteredZoneThisTurn)
    }

    // --- annotationsForTransfer: CastSpell ---

    @Test
    fun castSpellProducesSixAnnotations() {
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

        assertEquals(annotations.size, 6, "CastSpell should produce 6 annotations (tap events come via Stage 4)")
        assertEquals(annotations[0].typeList.first(), AnnotationType.ObjectIdChanged)
        assertEquals(annotations[1].typeList.first(), AnnotationType.ZoneTransfer_af5a)
        assertEquals(annotations[2].typeList.first(), AnnotationType.AbilityInstanceCreated)
        assertEquals(annotations[3].typeList.first(), AnnotationType.ManaPaid)
        assertEquals(annotations[4].typeList.first(), AnnotationType.AbilityInstanceDeleted)
        assertEquals(annotations[5].typeList.first(), AnnotationType.UserActionTaken)

        // Stack is not battlefield — no persistent annotation
        assertTrue(persistent.isEmpty())
    }

    @Test
    fun castSpellUserActionIsCast() {
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
        assertEquals(actionType.getValueInt32(0), 1, "actionType should be 1 (Cast)")
    }

    // --- annotationsForTransfer: Resolve ---

    @Test
    fun resolveProducesThreeAnnotations() {
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

        assertEquals(annotations.size, 3, "Resolve should produce 3 annotations")
        assertEquals(annotations[0].typeList.first(), AnnotationType.ResolutionStart)
        assertEquals(annotations[1].typeList.first(), AnnotationType.ResolutionComplete)
        assertEquals(annotations[2].typeList.first(), AnnotationType.ZoneTransfer_af5a)

        // Lands on battlefield — persistent annotation
        assertEquals(persistent.size, 1)
    }

    @Test
    fun resolveZoneTransferHasActingSeat() {
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
        assertEquals(annotations[2].affectorId, 2)
    }

    @Test
    fun resolveUsesGrpId() {
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
        assertEquals(grpid.getValueUint32(0), 67890)
    }

    // --- Edge cases ---

    @Test
    fun genericZoneTransferProducesAnnotations() {
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
        assertEquals(annotations.size, 2, "ZoneTransfer should produce ObjectIdChanged + ZoneTransfer")
        assertEquals(annotations[0].typeList.first(), AnnotationType.ObjectIdChanged)
        assertEquals(annotations[1].typeList.first(), AnnotationType.ZoneTransfer_af5a)
        assertTrue(persistent.isEmpty(), "Graveyard dest should produce no persistent annotation")
    }

    @Test
    fun noBattlefieldDestNoPersistentAnnotation() {
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
        assertTrue(persistent.isEmpty(), "Stack dest should not produce EnteredZoneThisTurn")
    }

    @Test
    fun resolveToGraveyardNoPersistentAnnotation() {
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

        assertEquals(annotations.size, 3, "Resolve still produces 3 annotations")
        assertTrue(persistent.isEmpty(), "Graveyard dest should not produce EnteredZoneThisTurn")
    }

    // --- Stage 4: mechanicAnnotations (Group B) ---

    /** Identity resolver for unit tests — forgeCardId maps to forgeCardId + 1000. */
    private fun testResolver(forgeCardId: Int): Int = forgeCardId + 1000

    // -- CountersChanged --

    @Test
    fun counterAddedAnnotation() {
        val events = listOf(
            NexusGameEvent.CountersChanged(forgeCardId = 42, counterType = "P1P1", oldCount = 0, newCount = 2),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 1, "Should produce one CounterAdded annotation")
        assertTrue(annotations[0].typeList.contains(AnnotationType.CounterAdded))
        assertTrue(annotations[0].affectedIdsList.contains(1042), "instanceId should be resolved via idResolver")

        val counterType = annotations[0].detailsList.first { it.key == "counter_type" }
        assertEquals(counterType.getValueString(0), "P1P1")
        val txnAmount = annotations[0].detailsList.first { it.key == "transaction_amount" }
        assertEquals(txnAmount.getValueInt32(0), 2)
    }

    @Test
    fun counterRemovedAnnotation() {
        val events = listOf(
            NexusGameEvent.CountersChanged(forgeCardId = 42, counterType = "LOYALTY", oldCount = 5, newCount = 2),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 1, "Should produce one CounterRemoved annotation")
        assertTrue(annotations[0].typeList.contains(AnnotationType.CounterRemoved))
        val txnAmount = annotations[0].detailsList.first { it.key == "transaction_amount" }
        assertEquals(txnAmount.getValueInt32(0), 3, "Removed amount should be abs(delta)")
    }

    @Test
    fun counterUnchangedSkipped() {
        val events = listOf(
            NexusGameEvent.CountersChanged(forgeCardId = 42, counterType = "P1P1", oldCount = 3, newCount = 3),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient
        assertTrue(annotations.isEmpty(), "No annotation when counter count unchanged")
    }

    // -- LibraryShuffled --

    @Test(enabled = false)
    fun shuffleAnnotation() {
        // Shuffle annotations are suppressed in production (crash client). See commit 76d61d2973.
        val events = listOf(
            NexusGameEvent.LibraryShuffled(seatId = 1),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 1)
        assertTrue(annotations[0].typeList.contains(AnnotationType.Shuffle))
        assertTrue(annotations[0].affectedIdsList.contains(1), "Shuffle affectedId should be seatId")
    }

    // -- Scry --

    @Test
    fun scryAnnotation() {
        val events = listOf(
            NexusGameEvent.Scry(seatId = 2, topCount = 1, bottomCount = 2),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 1)
        assertTrue(annotations[0].typeList.contains(AnnotationType.Scry_af5a))
        val top = annotations[0].detailsList.first { it.key == "topCount" }
        assertEquals(top.getValueInt32(0), 1)
        val bottom = annotations[0].detailsList.first { it.key == "bottomCount" }
        assertEquals(bottom.getValueInt32(0), 2)
    }

    // -- Surveil --

    @Test
    fun surveilAnnotation() {
        val events = listOf(
            NexusGameEvent.Surveil(seatId = 1, toLibrary = 1, toGraveyard = 1),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 1, "Surveil should produce a scry-type annotation")
        assertTrue(annotations[0].typeList.contains(AnnotationType.Scry_af5a))
    }

    // -- TokenCreated --

    @Test
    fun tokenCreatedAnnotation() {
        val events = listOf(
            NexusGameEvent.TokenCreated(forgeCardId = 99, seatId = 1),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 1)
        assertTrue(annotations[0].typeList.contains(AnnotationType.TokenCreated))
        assertTrue(annotations[0].affectedIdsList.contains(1099), "instanceId should be resolved")
    }

    // -- TokenDestroyed --

    @Test
    fun tokenDestroyedProducesAnnotation() {
        val events = listOf(
            NexusGameEvent.TokenDestroyed(forgeCardId = 88, seatId = 1),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 1)
        assertTrue(annotations[0].typeList.contains(AnnotationType.TokenDeleted))
        assertEquals(annotations[0].affectorId, 1088, "affectorId should be resolved instanceId")
        assertTrue(annotations[0].affectedIdsList.contains(1088), "affectedIds should contain resolved instanceId")
    }

    // -- PowerToughnessChanged --

    @Test
    fun powerToughnessChangedBothAnnotations() {
        val events = listOf(
            NexusGameEvent.PowerToughnessChanged(forgeCardId = 50, oldPower = 2, newPower = 4, oldToughness = 3, newToughness = 5),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 2, "Both power and toughness changed → 2 annotations")
        assertTrue(annotations[0].typeList.contains(AnnotationType.ModifiedPower))
        assertTrue(annotations[0].affectedIdsList.contains(1050))
        val powerValue = annotations[0].detailsList.first { it.key == "value" }
        assertEquals(powerValue.getValueInt32(0), 4, "value should be the new power")

        assertTrue(annotations[1].typeList.contains(AnnotationType.ModifiedToughness))
        val toughValue = annotations[1].detailsList.first { it.key == "value" }
        assertEquals(toughValue.getValueInt32(0), 5, "value should be the new toughness")
    }

    @Test
    fun powerOnlyChangedOneAnnotation() {
        val events = listOf(
            NexusGameEvent.PowerToughnessChanged(forgeCardId = 50, oldPower = 2, newPower = 5, oldToughness = 3, newToughness = 3),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 1, "Only power changed → 1 annotation")
        assertTrue(annotations[0].typeList.contains(AnnotationType.ModifiedPower))
    }

    @Test
    fun toughnessOnlyChangedOneAnnotation() {
        val events = listOf(
            NexusGameEvent.PowerToughnessChanged(forgeCardId = 50, oldPower = 2, newPower = 2, oldToughness = 3, newToughness = 1),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 1, "Only toughness changed → 1 annotation")
        assertTrue(annotations[0].typeList.contains(AnnotationType.ModifiedToughness))
    }

    // -- CardAttached --

    @Test
    fun attachProducesCorrectAnnotationShape() {
        val events = listOf(
            NexusGameEvent.CardAttached(forgeCardId = 55, targetForgeId = 66, seatId = 1),
        )
        val result = AnnotationPipeline.mechanicAnnotations(events, ::testResolver)

        // Transient: AttachmentCreated
        assertEquals(result.transient.size, 1)
        val created = result.transient[0]
        assertTrue(created.typeList.contains(AnnotationType.AttachmentCreated))
        assertEquals(created.affectorId, 0, "no affectorId")
        assertEquals(created.affectedIdsList, listOf(testResolver(55), testResolver(66)), "affectedIds=[aura, target]")

        // Persistent: Attachment
        assertEquals(result.persistent.size, 1)
        val attach = result.persistent[0]
        assertTrue(attach.typeList.contains(AnnotationType.Attachment))
        assertEquals(attach.affectorId, 0, "no affectorId")
        assertEquals(attach.affectedIdsList, listOf(testResolver(55), testResolver(66)), "affectedIds=[aura, target]")
    }

    @Test
    fun detachReturnsDetachedForgeCardId() {
        val events = listOf(
            NexusGameEvent.CardDetached(forgeCardId = 60, seatId = 1),
        )
        val result = AnnotationPipeline.mechanicAnnotations(events, ::testResolver)
        assertEquals(result.detachedForgeCardIds, listOf(60), "detachedForgeCardIds should contain the detached aura's forge card ID")
    }

    // -- RemoveAttachment --

    @Test
    fun detachProducesRemoveAttachment() {
        val events = listOf(
            NexusGameEvent.CardDetached(forgeCardId = 60, seatId = 1),
        )
        val result = AnnotationPipeline.mechanicAnnotations(events, ::testResolver)
        val annotations = result.transient

        assertEquals(annotations.size, 1, "CardDetached → 1 RemoveAttachment annotation")
        assertTrue(annotations[0].typeList.contains(AnnotationType.RemoveAttachment))
        assertTrue(annotations[0].affectedIdsList.contains(testResolver(60)))
    }

    // -- Mixed events --

    @Test
    fun mechanicAnnotationsIgnoresZoneTransferEvents() {
        val events = listOf(
            NexusGameEvent.ZoneChanged(forgeCardId = 1, from = forge.game.zone.ZoneType.Hand, to = forge.game.zone.ZoneType.Battlefield),
            NexusGameEvent.LandPlayed(forgeCardId = 1, seatId = 1),
            NexusGameEvent.CardDestroyed(forgeCardId = 2, seatId = 1),
            NexusGameEvent.DamageDealtToPlayer(sourceForgeId = 4, targetSeatId = 1, amount = 3, combat = true),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient
        assertTrue(annotations.isEmpty(), "Zone transfer and combat events should be ignored by Stage 4")
    }

    // -- CardTapped --

    @Test
    fun cardTappedProducesAnnotation() {
        val events = listOf(
            NexusGameEvent.CardTapped(forgeCardId = 70, tapped = true),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 1, "CardTapped should produce one TappedUntappedPermanent annotation")
        assertTrue(annotations[0].typeList.contains(AnnotationType.TappedUntappedPermanent))
        assertTrue(annotations[0].affectedIdsList.contains(testResolver(70)))
        val tapped = annotations[0].detailsList.first { it.key == "tapped" }
        assertEquals(tapped.getValueUint32(0), 1, "tapped detail should be 1")
    }

    @Test
    fun cardUntappedProducesAnnotation() {
        val events = listOf(
            NexusGameEvent.CardTapped(forgeCardId = 71, tapped = false),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient

        assertEquals(annotations.size, 1, "CardTapped(tapped=false) should produce untap annotation")
        assertTrue(annotations[0].typeList.contains(AnnotationType.TappedUntappedPermanent))
        val tapped = annotations[0].detailsList.first { it.key == "tapped" }
        assertEquals(tapped.getValueUint32(0), 0, "tapped detail should be 0 for untap")
    }

    @Test
    fun mechanicAnnotationsMultipleEvents() {
        // NOTE: LibraryShuffled is suppressed in production (crash client). See commit 76d61d2973.
        // Only testing CounterChanged + Scry here (2 events → 2 annotations).
        val events = listOf(
            NexusGameEvent.CountersChanged(forgeCardId = 42, counterType = "P1P1", oldCount = 0, newCount = 1),
            NexusGameEvent.Scry(seatId = 1, topCount = 2, bottomCount = 0),
        )
        val annotations = AnnotationPipeline.mechanicAnnotations(events, ::testResolver).transient
        assertEquals(annotations.size, 2, "Should produce one annotation per Group B event")
        assertTrue(annotations[0].typeList.contains(AnnotationType.CounterAdded))
        assertTrue(annotations[1].typeList.contains(AnnotationType.Scry_af5a))
    }

    // --- annotationsForTransfer: new zone-specific categories ---

    @Test
    fun destroyProducesAnnotations() {
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

        assertEquals(annotations.size, 2, "Destroy: ObjectIdChanged + ZoneTransfer")
        assertEquals(annotations[0].typeList.first(), AnnotationType.ObjectIdChanged)
        assertEquals(annotations[1].typeList.first(), AnnotationType.ZoneTransfer_af5a)
        val category = annotations[1].detailsList.first { it.key == "category" }
        assertEquals(category.getValueString(0), "Destroy")
        assertTrue(persistent.isEmpty(), "GY dest should have no persistent annotation")
    }

    @Test
    fun sacrificeProducesAnnotations() {
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
        assertEquals(category.getValueString(0), "Sacrifice")
    }

    @Test
    fun bounceProducesAnnotations() {
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
        assertEquals(category.getValueString(0), "Bounce")
    }

    @Test
    fun exileProducesAnnotations() {
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
        assertEquals(category.getValueString(0), "Exile")
    }

    @Test
    fun discardProducesAnnotations() {
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
        assertEquals(category.getValueString(0), "Discard")
    }

    @Test
    fun drawProducesAnnotations() {
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
        assertEquals(category.getValueString(0), "Draw")
    }

    @Test
    fun millProducesAnnotations() {
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
        assertEquals(category.getValueString(0), "Mill")
    }

    @Test
    fun counteredProducesAnnotations() {
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
        assertEquals(category.getValueString(0), "Countered")
    }

    // --- annotationsForTransfer: Return ---

    @Test
    fun returnFromGraveyardProducesAnnotations() {
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

        assertEquals(annotations.size, 2, "Return: ObjectIdChanged + ZoneTransfer")
        assertEquals(annotations[0].typeList.first(), AnnotationType.ObjectIdChanged)
        assertEquals(annotations[1].typeList.first(), AnnotationType.ZoneTransfer_af5a)
        val category = annotations[1].detailsList.first { it.key == "category" }
        assertEquals(category.getValueString(0), "Return")
        assertEquals(persistent.size, 1, "BF dest should produce EnteredZoneThisTurn")
    }

    @Test
    fun returnToHandNoPersistent() {
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
        assertEquals(category.getValueString(0), "Return")
        assertTrue(persistent.isEmpty(), "Hand dest should have no persistent annotation")
    }

    // --- annotationsForTransfer: Search ---

    @Test
    fun searchProducesAnnotations() {
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

        assertEquals(annotations.size, 2, "Search: ObjectIdChanged + ZoneTransfer")
        val category = annotations[1].detailsList.first { it.key == "category" }
        assertEquals(category.getValueString(0), "Search")
        assertEquals(persistent.size, 1, "BF dest should produce EnteredZoneThisTurn")
    }

    // --- annotationsForTransfer: Put ---

    @Test
    fun putProducesAnnotations() {
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

        assertEquals(annotations.size, 2, "Put: ObjectIdChanged + ZoneTransfer")
        val category = annotations[1].detailsList.first { it.key == "category" }
        assertEquals(category.getValueString(0), "Put")
        assertTrue(persistent.isEmpty())
    }
}
