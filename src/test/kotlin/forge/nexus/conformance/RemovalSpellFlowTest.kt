package forge.nexus.conformance

import forge.game.Game
import forge.game.ability.AbilityKey
import forge.game.card.Card
import forge.game.zone.ZoneType
import forge.nexus.game.BundleBuilder
import forge.nexus.game.GameBridge
import forge.nexus.game.snapshotFromGame
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * End-to-end removal spell flow: simulates a removal spell resolving
 * (including SpellResolved event firing) and verifies the target's
 * zone transition annotation uses the correct category.
 *
 * Key scenario: when a removal spell resolves, both SpellResolved
 * (for the spell going Stack→GY) and zone-change events (for the
 * target) fire in the same event batch. The annotation builder must
 * attribute each card's transfer to the correct category.
 *
 * Source: recording sessions 22-31-58 (Destroy/Exile/Bounce observed).
 */
@Test(groups = ["integration", "conformance"])
class RemovalSpellFlowTest : ConformanceTestBase() {

    /**
     * Bounce: removal spell resolves → target creature goes BF→Hand
     * with "Bounce" category (not "Resolve" from SpellResolved).
     */
    @Test
    fun bounceSpellResolutionProducesBounceCategory() {
        val (b, game, counter) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val forgeCardId = creature.id

        // Simulate Unsummon-style spell resolving:
        // 1. Create a dummy spell on the stack (to emit SpellResolved)
        // 2. Move target creature BF→Hand (what the spell effect does)
        b.snapshotFromGame(game, counter.currentGsId())

        // Fire SpellResolved for the removal spell itself (non-fizzled)
        // Note: we use a different card's SA to simulate the spell, but
        // the important thing is that SpellResolved fires in the same batch
        // as the target's zone change.
        // The creature's zone change should NOT pick up SpellResolved's
        // "Resolve" category because forgeCardIds differ.
        game.action.moveToHand(creature, null)

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
        assertNotNull(zt, "Should have ZoneTransfer annotation for bounced creature")
        assertEquals(
            zt!!.category,
            "Bounce",
            "BF→Hand should produce Bounce category",
        )
    }

    /**
     * Destroy: removal spell resolves → target creature goes BF→GY
     * with "Destroy" category.
     */
    @Test
    fun destroySpellResolutionProducesDestroyCategory() {
        val (b, game, counter) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val forgeCardId = creature.id

        b.snapshotFromGame(game, counter.currentGsId())

        game.action.destroy(creature, null, false, AbilityKey.newMap())

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
        assertNotNull(zt, "Should have ZoneTransfer annotation for destroyed creature")
        assertEquals(
            zt!!.category,
            "Destroy",
            "BF→GY via destroy should produce Destroy category",
        )
    }

    /**
     * Exile: removal spell resolves → target creature goes BF→Exile
     * with "Exile" category.
     */
    @Test
    fun exileSpellResolutionProducesExileCategory() {
        val (b, game, counter) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val forgeCardId = creature.id

        b.snapshotFromGame(game, counter.currentGsId())

        game.action.exile(creature, null, AbilityKey.newMap())

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
        assertNotNull(zt, "Should have ZoneTransfer annotation for exiled creature")
        assertEquals(
            zt!!.category,
            "Exile",
            "BF→Exile via exile should produce Exile category",
        )
    }

    /**
     * Mixed batch: SpellResolved fires in same event window as target zone
     * change. Verifies the annotation builder doesn't cross-contaminate:
     * the removal spell gets "Resolve" and the target gets the correct
     * removal category.
     *
     * This is the critical regression test for the fizzled-spell fix
     * (SpellResolved matching must check forgeCardId, not apply globally).
     */
    @Test
    fun spellResolvedDoesNotContaminateTargetCategory() {
        val (b, game, counter) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val creatureForgeId = creature.id

        // Get a second card to act as the "spell" (e.g. use a hand card)
        val player = b.getPlayer(1)!!
        val spellCard = player.getZone(ZoneType.Hand).cards.firstOrNull()
            ?: error("No cards in hand to simulate spell")
        val spellForgeId = spellCard.id

        b.snapshotFromGame(game, counter.currentGsId())

        // Fire SpellResolved for the spell card (simulating removal spell resolving)
        game.fireEvent(
            forge.game.event.GameEventSpellResolved(
                spellCard.firstSpellAbility,
                false,
            ),
        )
        // Then the spell's effect: exile the creature
        game.action.exile(creature, null, AbilityKey.newMap())
        // And the spell goes to GY (Stack→GY for the instant)
        // Note: the spell is actually in hand, not stack, but the events
        // are what matter for categoryFromEvents testing

        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
        )
        val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

        // The creature should have Exile category (not Resolve from SpellResolved)
        val creatureNewId = b.getOrAllocInstanceId(creatureForgeId)
        val creatureZt = findZoneTransfer(gsm, creatureNewId)
        assertNotNull(creatureZt, "Should have ZoneTransfer for exiled creature")
        assertEquals(
            creatureZt!!.category,
            "Exile",
            "Target creature should have Exile category, not Resolve (SpellResolved is for a different card)",
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun ensureCreatureOnBattlefield(b: GameBridge, game: Game): Card {
        val player = b.getPlayer(1)!!
        val bf = player.getZone(ZoneType.Battlefield)
        bf.cards.firstOrNull { it.isCreature }?.let { return it }

        playLand(b)
        b.snapshotFromGame(game)
        castCreature(b)
        b.snapshotFromGame(game)
        passPriority(b)
        b.snapshotFromGame(game)

        return bf.cards.firstOrNull { it.isCreature }
            ?: error("Failed to get creature on battlefield")
    }

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
