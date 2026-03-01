package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Spell-forced discard conformance: simulates a spell effect causing
 * the opponent to discard, verifying the Hand→GY annotation uses
 * "Discard" category.
 *
 * Uses startWithBoard{} — synchronous, no threads (~0.01s per test).
 */
@Test(groups = ["conformance"])
class SpellForcedDiscardTest : ConformanceTestBase() {

    /** Direct discard (simulating Mind Rot) → Hand→GY with Discard category. */
    @Test
    fun spellForcedDiscardProducesDiscardCategory() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Lightning Bolt", human, ZoneType.Hand)
            addCard("Giant Growth", human, ZoneType.Hand)
        }
        val human = game.humanPlayer
        val cardInHand = human.getZone(ZoneType.Hand).cards.first()
        val forgeCardId = cardInHand.id

        val gsm = captureAfterAction(b, game, counter) {
            human.discard(cardInHand, null, false, AbilityKey.newMap())
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer annotation for discarded card" }
        assertEquals(zt.category, "Discard", "Spell-forced Hand→GY should produce Discard category")
        assertTrue(
            human.getZone(ZoneType.Graveyard).cards.any { it.id == forgeCardId },
            "Discarded card should be in graveyard",
        )
    }

    /** Multiple discards in one batch all produce Discard category. */
    @Test
    fun multipleSpellForcedDiscardsAllProduceDiscardCategory() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Lightning Bolt", human, ZoneType.Hand)
            addCard("Giant Growth", human, ZoneType.Hand)
        }
        val human = game.humanPlayer
        val hand = human.getZone(ZoneType.Hand).cards.toList()
        val card1 = hand[0]
        val card2 = hand[1]

        val gsm = captureAfterAction(b, game, counter) {
            human.discard(card1, null, false, AbilityKey.newMap())
            human.discard(card2, null, false, AbilityKey.newMap())
        }

        val zt1 = checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(card1.id))) { "Should have ZoneTransfer for first discarded card" }
        assertEquals(zt1.category, "Discard", "First card should be Discard")

        val zt2 = checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(card2.id))) { "Should have ZoneTransfer for second discarded card" }
        assertEquals(zt2.category, "Discard", "Second card should be Discard")
    }

    /** SpellResolved firing in same batch doesn't contaminate discard category. */
    @Test
    fun discardWithSpellResolvedDoesNotContaminate() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Mind Rot", human, ZoneType.Hand)
            addCard("Lightning Bolt", human, ZoneType.Hand)
        }
        val human = game.humanPlayer
        val hand = human.getZone(ZoneType.Hand).cards.toList()
        val spellCard = hand[0]
        val discardTarget = hand[1]
        val discardForgeId = discardTarget.id

        val gsm = captureAfterAction(b, game, counter) {
            game.fireEvent(
                forge.game.event.GameEventSpellResolved(spellCard.firstSpellAbility, false),
            )
            human.discard(discardTarget, null, false, AbilityKey.newMap())
        }
        val newId = b.getOrAllocInstanceId(discardForgeId)

        val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for discarded card" }
        assertEquals(zt.category, "Discard", "Discarded card should have Discard category (not Resolve)")
    }
}
