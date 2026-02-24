package forge.nexus.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import forge.nexus.game.BundleBuilder
import forge.nexus.game.snapshotFromGame
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Spell-forced discard conformance: simulates a spell effect causing
 * the opponent to discard, verifying the Hand→GY annotation uses
 * "Discard" category.
 *
 * Source: recording session game2 (proxy 18-45-57) — observed discard
 * effects with SelectNReq prompts for choosing which card to discard.
 *
 * The annotation pipeline should produce Discard for Hand→GY regardless
 * of whether the discard comes from cleanup (hand size) or a spell effect.
 * Both fire GameEventCardChangeZone Hand→GY which the collector maps to
 * CardDiscarded.
 */
@Test(groups = ["integration", "conformance"])
class SpellForcedDiscardTest : ConformanceTestBase() {

    /**
     * Direct discard (simulating a spell effect like Mind Rot) produces
     * Hand→GY with Discard category.
     */
    @Test
    fun spellForcedDiscardProducesDiscardCategory() {
        val (b, game, counter) = startGameAtMain1()
        val player = b.getPlayer(1)!!
        val cardInHand = player.getZone(ZoneType.Hand).cards.firstOrNull()
            ?: error("Hand empty")
        val forgeCardId = cardInHand.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        b.snapshotFromGame(game, counter.currentGsId())

        // Simulate spell-forced discard (same as what Mind Rot's resolution does)
        player.discard(cardInHand, null, false, AbilityKey.newMap())

        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
        )
        val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer annotation for discarded card")
        assertEquals(
            zt!!.category,
            "Discard",
            "Spell-forced Hand→GY should produce Discard category",
        )

        // Card should be in graveyard
        assertTrue(
            player.getZone(ZoneType.Graveyard).cards.any { it.id == forgeCardId },
            "Discarded card should be in graveyard",
        )
    }

    /**
     * Multiple discards in a single batch (like "discard 2 cards") all
     * produce Discard category annotations.
     */
    @Test
    fun multipleSpellForcedDiscardsAllProduceDiscardCategory() {
        val (b, game, counter) = startGameAtMain1()
        val player = b.getPlayer(1)!!
        val hand = player.getZone(ZoneType.Hand).cards.toList()
        assertTrue(hand.size >= 2, "Need at least 2 cards in hand")

        val card1 = hand[0]
        val card2 = hand[1]
        val forgeId1 = card1.id
        val forgeId2 = card2.id

        b.snapshotFromGame(game, counter.currentGsId())

        // Discard two cards (simulating Mind Rot effect)
        player.discard(card1, null, false, AbilityKey.newMap())
        player.discard(card2, null, false, AbilityKey.newMap())

        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
        )
        val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

        val newId1 = b.getOrAllocInstanceId(forgeId1)
        val newId2 = b.getOrAllocInstanceId(forgeId2)

        // Both should have Discard category
        val zt1 = findZoneTransfer(gsm, newId1)
        assertNotNull(zt1, "Should have ZoneTransfer for first discarded card")
        assertEquals(zt1!!.category, "Discard", "First card should be Discard")

        val zt2 = findZoneTransfer(gsm, newId2)
        assertNotNull(zt2, "Should have ZoneTransfer for second discarded card")
        assertEquals(zt2!!.category, "Discard", "Second card should be Discard")
    }

    /**
     * Discard while SpellResolved fires in the same event batch
     * (spell causes discard, then spell itself goes to GY).
     * Discard category should not be contaminated by Resolve.
     */
    @Test
    fun discardWithSpellResolvedDoesNotContaminate() {
        val (b, game, counter) = startGameAtMain1()
        val player = b.getPlayer(1)!!
        val hand = player.getZone(ZoneType.Hand).cards.toList()
        assertTrue(hand.size >= 2, "Need at least 2 cards")

        // Use first card as "the discard spell" and second as "discarded card"
        val spellCard = hand[0]
        val discardTarget = hand[1]
        val discardForgeId = discardTarget.id

        b.snapshotFromGame(game, counter.currentGsId())

        // Fire SpellResolved for the spell (simulating the discard spell resolving)
        game.fireEvent(
            forge.game.event.GameEventSpellResolved(
                spellCard.firstSpellAbility,
                false,
            ),
        )
        // Then the effect: target discards
        player.discard(discardTarget, null, false, AbilityKey.newMap())

        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
        )
        val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")
        val newId = b.getOrAllocInstanceId(discardForgeId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for discarded card")
        assertEquals(
            zt!!.category,
            "Discard",
            "Discarded card should have Discard category (not Resolve from SpellResolved)",
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun findZoneTransfer(gsm: GameStateMessage, instanceId: Int): ZoneTransferInfo? {
        val ann = gsm.annotationsList.firstOrNull {
            AnnotationType.ZoneTransfer_af5a in it.typeList &&
                instanceId in it.affectedIdsList
        } ?: return null
        return ZoneTransferInfo(
            category = ann.detailsList.firstOrNull { it.key == "category" }?.getValueString(0) ?: "",
        )
    }

    data class ZoneTransferInfo(val category: String)
}
