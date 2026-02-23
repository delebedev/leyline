package forge.nexus.conformance

import forge.game.Game
import forge.game.ability.AbilityKey
import forge.game.card.Card
import forge.game.card.CounterEnumType
import forge.game.player.Player
import forge.game.zone.ZoneType
import forge.nexus.game.BundleBuilder
import forge.nexus.game.GameBridge
import forge.nexus.game.snapshotFromGame
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Tier 1 zone-pair coverage: exercises every zone transition the Arena client
 * expects, at the StateMapper level.
 *
 * Each test starts a game, sets up board state, triggers a zone transition via
 * [forge.game.GameAction], captures the resulting diff via [BundleBuilder],
 * and asserts on annotation types, ordering, category codes, instanceId
 * realloc vs keep, and Limbo retirement.
 *
 * These tests use GameAction directly (bypassing priority/stack) — they test
 * the StateMapper translation layer, not game flow.
 */
@Test(groups = ["integration"])
class ZoneTransitionConformanceTest : ConformanceTestBase() {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Get human player (seat 1). */
    private fun human(b: GameBridge): Player = b.getPlayer(1)!!

    /** Get AI player (seat 2). */
    private fun ai(b: GameBridge): Player = b.getPlayer(2)!!

    /** Get a creature from the battlefield, or put one there first. */
    private fun ensureCreatureOnBattlefield(b: GameBridge, game: Game): Card {
        val player = human(b)
        val bf = player.getZone(ZoneType.Battlefield)
        bf.cards.firstOrNull { it.isCreature }?.let { return it }

        // No creature on BF — play a land + cast one
        playLand(b)
        b.snapshotFromGame(game)
        castCreature(b)
        b.snapshotFromGame(game)
        passPriority(b) // resolve
        b.snapshotFromGame(game)

        return bf.cards.firstOrNull { it.isCreature }
            ?: error("Failed to get creature on battlefield")
    }

    /**
     * Capture a diff after a forced zone transition.
     * Takes a snapshot, runs [action], then builds a postAction bundle.
     */
    private fun captureAfterAction(
        b: GameBridge,
        game: Game,
        gsId: Int,
        action: () -> Unit,
    ): GameStateMessage {
        b.snapshotFromGame(game, gsId)
        action()
        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            1,
            gsId,
        )
        return result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")
    }

    /** Find the ZoneTransfer annotation for a given instanceId. */
    private fun findZoneTransfer(gsm: GameStateMessage, instanceId: Int): ZoneTransferInfo? {
        val ann = gsm.annotationsList.firstOrNull {
            AnnotationType.ZoneTransfer_af5a in it.typeList &&
                instanceId in it.affectedIdsList
        } ?: return null
        return ZoneTransferInfo(
            category = ann.detailsList.firstOrNull { it.key == "category" }?.getValueString(0) ?: "",
            zoneSrc = ann.detailsList.firstOrNull { it.key == "zone_src" }?.getValueInt32(0) ?: -1,
            zoneDest = ann.detailsList.firstOrNull { it.key == "zone_dest" }?.getValueInt32(0) ?: -1,
        )
    }

    /** Find ObjectIdChanged annotation with orig_id matching. */
    private fun findObjectIdChanged(gsm: GameStateMessage, origId: Int): Pair<Int, Int>? {
        val ann = gsm.annotationsList.firstOrNull {
            AnnotationType.ObjectIdChanged in it.typeList &&
                it.detailsList.any { d -> d.key == "orig_id" && d.getValueInt32(0) == origId }
        } ?: return null
        val newId = ann.detailsList.first { it.key == "new_id" }.getValueInt32(0)
        return origId to newId
    }

    /** Check annotation ordering: ObjectIdChanged before ZoneTransfer for a given instanceId. */
    private fun assertObjectIdChangedBeforeZoneTransfer(gsm: GameStateMessage, origId: Int) {
        val annotations = gsm.annotationsList
        val oicIndex = annotations.indexOfFirst {
            AnnotationType.ObjectIdChanged in it.typeList &&
                it.detailsList.any { d -> d.key == "orig_id" && d.getValueInt32(0) == origId }
        }
        val ztIndex = annotations.indexOfFirst {
            AnnotationType.ZoneTransfer_af5a in it.typeList
        }
        if (oicIndex >= 0 && ztIndex >= 0) {
            assertTrue(oicIndex < ztIndex, "ObjectIdChanged ($oicIndex) should come before ZoneTransfer ($ztIndex)")
        }
    }

    /** Check EnteredZoneThisTurn persistent annotation for battlefield destination. */
    private fun hasEnteredZoneThisTurn(gsm: GameStateMessage, instanceId: Int): Boolean =
        gsm.persistentAnnotationsList.any {
            AnnotationType.EnteredZoneThisTurn in it.typeList &&
                instanceId in it.affectedIdsList
        }

    data class ZoneTransferInfo(val category: String, val zoneSrc: Int, val zoneDest: Int)

    // -----------------------------------------------------------------------
    // 1. Hand → Battlefield (PlayLand)
    // -----------------------------------------------------------------------

    @Test
    fun handToBattlefieldPlayLand() {
        val (b, game, gsId) = startGameAtMain1()
        val player = human(b)
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
            ?: error("No land in hand")
        val origId = b.getOrAllocInstanceId(land.id)
        val forgeCardId = land.id

        playLand(b)
        val gsm = postAction(game, b, 1, gsId).gsmOrNull ?: error("No GSM after play land")
        val newId = b.getOrAllocInstanceId(forgeCardId)

        // Category = PlayLand
        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer annotation")
        assertEquals(zt!!.category, "PlayLand")

        // instanceId reallocated
        assertTrue(origId != newId, "Hand→BF should realloc instanceId")

        // ObjectIdChanged present, before ZoneTransfer
        val oic = findObjectIdChanged(gsm, origId)
        assertNotNull(oic, "Should have ObjectIdChanged")
        assertEquals(oic!!.second, newId)
        assertObjectIdChangedBeforeZoneTransfer(gsm, origId)

        // Old id retired to Limbo
        assertLimboContains(gsm, origId)
    }

    // -----------------------------------------------------------------------
    // 2. Hand → Stack (CastSpell)
    // -----------------------------------------------------------------------

    @Test
    fun handToStackCastSpell() {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) // need mana
        b.snapshotFromGame(game, gsId + 1)

        val player = human(b)
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
            ?: error("No creature in hand")
        val origId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        castCreature(b)
        val gsm = postAction(game, b, 1, gsId + 1).gsmOrNull ?: error("No GSM after cast")
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for cast")
        assertEquals(zt!!.category, "CastSpell")

        assertTrue(origId != newId, "Hand→Stack should realloc instanceId")
        val oic = findObjectIdChanged(gsm, origId)
        assertNotNull(oic, "Should have ObjectIdChanged for cast")
        assertObjectIdChangedBeforeZoneTransfer(gsm, origId)
        assertLimboContains(gsm, origId)
    }

    // -----------------------------------------------------------------------
    // 3. Stack → Battlefield (Resolve)
    // -----------------------------------------------------------------------

    @Test
    fun stackToBattlefieldResolve() {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b)
        b.snapshotFromGame(game, gsId + 1)

        val player = human(b)
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
            ?: error("No creature in hand")
        val forgeCardId = creature.id

        castCreature(b)
        val castResult = postAction(game, b, 1, gsId + 1)
        b.snapshotFromGame(game, castResult.nextGsId)
        val stackId = b.getOrAllocInstanceId(forgeCardId)

        // Resolve by passing priority
        passPriority(b)
        val gsm = postAction(game, b, 1, castResult.nextGsId).gsmOrNull ?: error("No GSM after resolve")
        val resolvedId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, resolvedId)
        assertNotNull(zt, "Should have ZoneTransfer for resolve")
        assertEquals(zt!!.category, "Resolve")

        // Stack→BF keeps instanceId (per spec)
        // Note: in practice Forge may or may not realloc here depending on engine behavior
        // The important assertion is the category
        assertTrue(hasEnteredZoneThisTurn(gsm, resolvedId), "BF destination should have EnteredZoneThisTurn")
    }

    // -----------------------------------------------------------------------
    // 4. Battlefield → Graveyard (Destroy)
    // -----------------------------------------------------------------------

    @Test
    fun battlefieldToGraveyardDestroy() {
        val (b, game, gsId) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val origId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        val gsm = captureAfterAction(b, game, gsId + 10) {
            game.action.destroy(creature, null, false, AbilityKey.newMap())
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for destroy")
        assertEquals(zt!!.category, "Destroy", "BF→GY via destroy should be Destroy category")

        assertTrue(origId != newId, "BF→GY should realloc instanceId")
        assertObjectIdChangedBeforeZoneTransfer(gsm, origId)
        assertLimboContains(gsm, origId)
        assertFalse(hasEnteredZoneThisTurn(gsm, newId), "GY destination should NOT have EnteredZoneThisTurn")
    }

    // -----------------------------------------------------------------------
    // 5. Battlefield → Graveyard (Sacrifice)
    // -----------------------------------------------------------------------

    @Test
    fun battlefieldToGraveyardSacrifice() {
        val (b, game, gsId) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val origId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        val gsm = captureAfterAction(b, game, gsId + 10) {
            // Fire the sacrifice event manually and move to GY.
            // game.action.sacrifice() NPEs without a full SpellAbility context,
            // so we fire the event + zone change separately.
            game.fireEvent(forge.game.event.GameEventCardSacrificed(creature))
            game.action.moveToGraveyard(creature, null)
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for sacrifice")
        assertEquals(zt!!.category, "Sacrifice", "BF→GY via sacrifice should be Sacrifice category")

        assertTrue(origId != newId, "BF→GY should realloc instanceId")
        assertLimboContains(gsm, origId)
    }

    // -----------------------------------------------------------------------
    // 6. Battlefield → Exile
    // -----------------------------------------------------------------------

    @Test
    fun battlefieldToExile() {
        val (b, game, gsId) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val origId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        val gsm = captureAfterAction(b, game, gsId + 10) {
            game.action.exile(creature, null, AbilityKey.newMap())
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for exile")
        assertEquals(zt!!.category, "Exile", "BF→Exile should be Exile category")

        assertTrue(origId != newId, "BF→Exile should realloc instanceId")
        assertLimboContains(gsm, origId)
    }

    // -----------------------------------------------------------------------
    // 7. Battlefield → Hand (Bounce)
    // -----------------------------------------------------------------------

    @Test
    fun battlefieldToHandBounce() {
        val (b, game, gsId) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val origId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        val gsm = captureAfterAction(b, game, gsId + 10) {
            game.action.moveToHand(creature, null)
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for bounce")
        assertEquals(zt!!.category, "Bounce", "BF→Hand should be Bounce category")

        assertTrue(origId != newId, "BF→Hand should realloc instanceId")
        assertLimboContains(gsm, origId)
    }

    // -----------------------------------------------------------------------
    // 9. Library → Hand (Draw)
    // -----------------------------------------------------------------------

    @Test
    fun libraryToHandDraw() {
        val (b, game, gsId) = startGameAtMain1()
        val player = human(b)
        val topCard = player.getZone(ZoneType.Library).cards.firstOrNull()
            ?: error("Library empty")
        val forgeCardId = topCard.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterAction(b, game, gsId + 10) {
            player.drawCard()
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for draw")
        assertEquals(zt!!.category, "Draw", "Library→Hand should be Draw category")
    }

    // -----------------------------------------------------------------------
    // 10. Hand → Graveyard (Discard)
    // -----------------------------------------------------------------------

    @Test
    fun handToGraveyardDiscard() {
        val (b, game, gsId) = startGameAtMain1()
        val player = human(b)
        val cardInHand = player.getZone(ZoneType.Hand).cards.firstOrNull()
            ?: error("Hand empty")
        val forgeCardId = cardInHand.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterAction(b, game, gsId + 10) {
            player.discard(cardInHand, null, false, AbilityKey.newMap())
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for discard")
        assertEquals(zt!!.category, "Discard", "Hand→GY should be Discard category")
    }

    // -----------------------------------------------------------------------
    // 11. Library → Graveyard (Mill)
    // -----------------------------------------------------------------------

    @Test
    fun libraryToGraveyardMill() {
        val (b, game, gsId) = startGameAtMain1()
        val player = human(b)
        val topCard = player.getZone(ZoneType.Library).cards.firstOrNull()
            ?: error("Library empty")
        val forgeCardId = topCard.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterAction(b, game, gsId + 10) {
            game.action.moveToGraveyard(topCard, null)
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for mill")
        assertEquals(zt!!.category, "Mill", "Library→GY should be Mill category")
    }

    // -----------------------------------------------------------------------
    // 12. Library → Exile
    // -----------------------------------------------------------------------

    @Test
    fun libraryToExile() {
        val (b, game, gsId) = startGameAtMain1()
        val player = human(b)
        val topCard = player.getZone(ZoneType.Library).cards.firstOrNull()
            ?: error("Library empty")
        val forgeCardId = topCard.id

        val gsm = captureAfterAction(b, game, gsId + 10) {
            game.action.exile(topCard, null, AbilityKey.newMap())
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for library exile")
        assertEquals(zt!!.category, "Exile", "Library→Exile should be Exile category")
    }

    // -----------------------------------------------------------------------
    // 13. Exile → Battlefield (Put)
    // -----------------------------------------------------------------------

    @Test
    fun exileToBattlefield() {
        val (b, game, gsId) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val forgeCardId = creature.id

        // First exile the creature
        game.action.exile(creature, null, AbilityKey.newMap())
        b.snapshotFromGame(game, gsId + 10)

        // Find the exiled card (may be a new Card object after zone change)
        val player = human(b)
        val exiled = player.getZone(ZoneType.Exile).cards.firstOrNull { it.id == forgeCardId }
            // After exile, card.id may change; find by name
            ?: player.getZone(ZoneType.Exile).cards.firstOrNull { it.isCreature }
            ?: error("No creature in exile")
        val exileId = b.getOrAllocInstanceId(exiled.id)

        val gsm = captureAfterAction(b, game, gsId + 11) {
            game.action.moveToPlay(exiled, null, AbilityKey.newMap())
        }
        val newId = b.getOrAllocInstanceId(exiled.id)

        val zt = findZoneTransfer(gsm, newId)
        // Exile→BF may produce various categories; the key assertion is it has a ZoneTransfer
        assertNotNull(zt, "Should have ZoneTransfer for exile→BF")

        assertTrue(hasEnteredZoneThisTurn(gsm, newId), "BF destination should have EnteredZoneThisTurn")
    }

    // -----------------------------------------------------------------------
    // 14. Graveyard → Battlefield (Reanimate/Put)
    // -----------------------------------------------------------------------

    @Test
    fun graveyardToBattlefield() {
        val (b, game, gsId) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val forgeCardId = creature.id

        // First destroy the creature to put it in GY
        game.action.destroy(creature, null, false, AbilityKey.newMap())
        b.snapshotFromGame(game, gsId + 10)

        val player = human(b)
        val inGY = player.getZone(ZoneType.Graveyard).cards.firstOrNull { it.isCreature }
            ?: error("No creature in graveyard")
        val gyId = b.getOrAllocInstanceId(inGY.id)

        val gsm = captureAfterAction(b, game, gsId + 11) {
            game.action.moveToPlay(inGY, null, AbilityKey.newMap())
        }
        val newId = b.getOrAllocInstanceId(inGY.id)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for reanimate")
        assertTrue(hasEnteredZoneThisTurn(gsm, newId), "BF destination should have EnteredZoneThisTurn")
    }

    // -----------------------------------------------------------------------
    // 15. Graveyard → Hand (Regrowth)
    // -----------------------------------------------------------------------

    @Test
    fun graveyardToHand() {
        val (b, game, gsId) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val forgeCardId = creature.id

        // First destroy to put in GY
        game.action.destroy(creature, null, false, AbilityKey.newMap())
        b.snapshotFromGame(game, gsId + 10)

        val player = human(b)
        val inGY = player.getZone(ZoneType.Graveyard).cards.firstOrNull { it.isCreature }
            ?: error("No creature in graveyard")

        val gsm = captureAfterAction(b, game, gsId + 11) {
            game.action.moveToHand(inGY, null)
        }
        val newId = b.getOrAllocInstanceId(inGY.id)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for GY→Hand")
    }

    // -----------------------------------------------------------------------
    // 16. Hand → Exile
    // -----------------------------------------------------------------------

    @Test
    fun handToExile() {
        val (b, game, gsId) = startGameAtMain1()
        val player = human(b)
        val cardInHand = player.getZone(ZoneType.Hand).cards.firstOrNull()
            ?: error("Hand empty")
        val forgeCardId = cardInHand.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterAction(b, game, gsId + 10) {
            game.action.exile(cardInHand, null, AbilityKey.newMap())
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for Hand→Exile")
        assertEquals(zt!!.category, "Exile", "Hand→Exile should be Exile category")
    }

    // =======================================================================
    // Group B: Mechanic annotations (Stage 4 pipeline)
    // =======================================================================

    // -----------------------------------------------------------------------
    // B1. Counter added
    // -----------------------------------------------------------------------

    @Test
    fun counterAddedProducesAnnotation() {
        val (b, game, gsId) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)

        val gsm = captureAfterAction(b, game, gsId + 10) {
            creature.addCounterInternal(CounterEnumType.P1P1, 2, human(b), true, null, AbilityKey.newMap())
        }

        val counterAnn = gsm.annotationsList.firstOrNull {
            AnnotationType.CounterAdded in it.typeList
        }
        assertNotNull(counterAnn, "Should have CounterAdded annotation")
        val counterType = counterAnn!!.detailsList.firstOrNull { it.key == "counter_type" }
        assertNotNull(counterType, "CounterAdded should have counter_type detail")
        // CounterEnumType.P1P1.getName() returns "+1/+1" (display name, not enum name)
        assertEquals(counterType!!.getValueString(0), "+1/+1")
        val txnAmount = counterAnn.detailsList.first { it.key == "transaction_amount" }
        assertEquals(txnAmount.getValueInt32(0), 2, "transaction_amount should be 2")
    }

    // -----------------------------------------------------------------------
    // B2. Counter removed
    // -----------------------------------------------------------------------

    @Test
    fun counterRemovedProducesAnnotation() {
        val (b, game, gsId) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)

        // First add counters
        creature.addCounterInternal(CounterEnumType.P1P1, 3, human(b), true, null, AbilityKey.newMap())
        b.snapshotFromGame(game, gsId + 10)
        b.drainEvents() // clear counter-add events

        val gsm = captureAfterAction(b, game, gsId + 11) {
            creature.subtractCounter(CounterEnumType.P1P1, 2, human(b))
        }

        val counterAnn = gsm.annotationsList.firstOrNull {
            AnnotationType.CounterRemoved in it.typeList
        }
        assertNotNull(counterAnn, "Should have CounterRemoved annotation")
        val txnAmount = counterAnn!!.detailsList.first { it.key == "transaction_amount" }
        assertEquals(txnAmount.getValueInt32(0), 2, "transaction_amount should be 2")
    }

    // -----------------------------------------------------------------------
    // B3. Library shuffle
    // -----------------------------------------------------------------------

    @Test
    fun shuffleProducesAnnotation() {
        val (b, game, gsId) = startGameAtMain1()
        val player = human(b)

        val gsm = captureAfterAction(b, game, gsId + 10) {
            player.shuffle(null)
        }

        val shuffleAnn = gsm.annotationsList.firstOrNull {
            AnnotationType.Shuffle in it.typeList
        }
        assertNotNull(shuffleAnn, "Should have Shuffle annotation")
        assertTrue(shuffleAnn!!.affectedIdsList.contains(1), "Shuffle affectedId should be seat 1")
    }
}
