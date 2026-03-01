package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.event.GameEventCardAttachment
import forge.game.zone.ZoneType
import leyline.game.BundleBuilder
import leyline.game.snapshotFromGame
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Attachment annotation pipeline: verifies that aura/equipment attachment
 * events produce the correct transient (AttachmentCreated) and persistent
 * (Attachment) annotations in the GSM.
 *
 * Source: RecordingDecoderTest.onDrawAuraResolve() (lines 141-168) shows
 * the expected annotation pattern from real Arena captures.
 *
 * Uses startWithBoard{} — synchronous, no threads (~0.01s per test).
 */
@Test(groups = ["conformance"])
class AttachmentAnnotationTest : ConformanceTestBase() {

    /**
     * Simulates attaching a card to a creature (aura/equipment) and verifies:
     * 1. Transient AttachmentCreated annotation with both IDs in affectedIds
     * 2. Persistent Attachment annotation with both IDs in affectedIds
     */
    @Test
    fun attachmentProducesTransientAndPersistentAnnotations() {
        val (b, game, counter) = startWithBoard { g, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
            addCard("Holy Strength", human, ZoneType.Hand)
        }
        val human = game.humanPlayer
        val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val auraCard = human.getZone(ZoneType.Hand).cards.first()

        // Move aura to battlefield (simulating resolve)
        game.action.moveToPlay(auraCard, null, AbilityKey.newMap())

        b.snapshotFromGame(game, counter.currentGsId())

        // Fire attachment event (simulates aura enchanting creature)
        game.fireEvent(GameEventCardAttachment(auraCard, null, creature))

        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
        )
        val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

        // Verify transient AttachmentCreated annotation
        val auraIid = b.getOrAllocInstanceId(auraCard.id)
        val creatureIid = b.getOrAllocInstanceId(creature.id)

        val attachCreated = gsm.annotationsList.firstOrNull {
            AnnotationType.AttachmentCreated in it.typeList
        }
        assertNotNull(attachCreated, "Should have transient AttachmentCreated annotation")
        assertEquals(
            attachCreated!!.affectedIdsList,
            listOf(auraIid, creatureIid),
            "AttachmentCreated affectedIds should be [aura, target]",
        )
        assertEquals(attachCreated.affectorId, 0, "AttachmentCreated should have no affectorId")

        // Verify persistent Attachment annotation
        val attachPersistent = gsm.persistentAnnotationsList.firstOrNull {
            AnnotationType.Attachment in it.typeList
        }
        assertNotNull(attachPersistent, "Should have persistent Attachment annotation")
        assertEquals(
            attachPersistent!!.affectedIdsList,
            listOf(auraIid, creatureIid),
            "Attachment affectedIds should be [aura, target]",
        )
        assertEquals(attachPersistent.affectorId, 0, "Attachment should have no affectorId")
        assertTrue(
            attachPersistent.id > 0,
            "Persistent annotation should have a positive ID",
        )
    }

    /**
     * Detach event (aura falling off) should NOT produce AttachmentCreated.
     * CardDetached is captured but not yet wired to remove persistent annotations
     * (deferred — would need annotation deletion pipeline).
     */
    @Test
    fun detachDoesNotProduceAttachmentCreated() {
        val (b, game, counter) = startWithBoard { g, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
            addCard("Holy Strength", human, ZoneType.Hand)
        }
        val human = game.humanPlayer
        val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val auraCard = human.getZone(ZoneType.Hand).cards.first()

        game.action.moveToPlay(auraCard, null, AbilityKey.newMap())

        b.snapshotFromGame(game, counter.currentGsId())

        // Fire detach event (aura falling off — newTarget is null)
        game.fireEvent(GameEventCardAttachment(auraCard, creature, null))

        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
        )
        val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

        val attachCreated = gsm.annotationsList.firstOrNull {
            AnnotationType.AttachmentCreated in it.typeList
        }
        assertTrue(
            attachCreated == null,
            "Detach should NOT produce AttachmentCreated annotation",
        )
    }
}
