package forge.nexus.conformance

import forge.game.Game
import forge.game.ability.AbilityKey
import forge.game.card.Card
import forge.game.card.CounterEnumType
import forge.game.player.Player
import forge.game.zone.ZoneType
import forge.nexus.game.BundleBuilder
import forge.nexus.game.GameBridge
import forge.nexus.game.MessageCounter
import forge.nexus.game.snapshotFromGame
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Tier 1 zone-pair coverage: exercises every zone transition the Arena client
 * expects, at the StateMapper level.
 *
 * Tests 1-3 (PlayLand, CastSpell, Resolve) use [startGameAtMain1] because they
 * test the play/cast action pipeline through the bridge.
 *
 * All other tests use [startWithBoard] — no threads, no bridge, ~0.01s per test.
 * Cards are placed directly into zones; transitions triggered via [forge.game.GameAction].
 * Forge events fire synchronously, annotations build inline.
 */
@Test(groups = ["integration"])
class ZoneTransitionConformanceTest : ConformanceTestBase() {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Get human player from a board-based game. */
    private fun human(game: Game): Player =
        game.players.first { it.lobbyPlayer !is forge.ai.LobbyPlayerAi }

    /** Legacy helper for bridge-based tests. */
    private fun human(b: GameBridge): Player = b.getPlayer(1)!!

    /**
     * Capture a diff after a forced zone transition.
     * Takes a snapshot, runs [action], then builds a stateOnlyDiff.
     */
    private fun captureAfterAction(
        b: GameBridge,
        game: Game,
        counter: MessageCounter,
        action: () -> Unit,
    ): GameStateMessage {
        b.snapshotFromGame(game, counter.currentGsId())
        action()
        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
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

    // =======================================================================
    // Group A: Pipeline tests (need bridge — startGameAtMain1)
    // =======================================================================

    // -----------------------------------------------------------------------
    // 1. Hand → Battlefield (PlayLand)
    // -----------------------------------------------------------------------

    @Test
    fun handToBattlefieldPlayLand() {
        val (b, game, counter) = startGameAtMain1()
        val player = human(b)
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
            ?: error("No land in hand")
        val origId = b.getOrAllocInstanceId(land.id)
        val forgeCardId = land.id

        playLand(b)
        val gsm = postAction(game, b, counter).gsmOrNull ?: error("No GSM after play land")
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer annotation")
        assertEquals(zt!!.category, "PlayLand")

        assertTrue(origId != newId, "Hand→BF should realloc instanceId")

        val oic = findObjectIdChanged(gsm, origId)
        assertNotNull(oic, "Should have ObjectIdChanged")
        assertEquals(oic!!.second, newId)
        assertObjectIdChangedBeforeZoneTransfer(gsm, origId)

        assertLimboContains(gsm, origId)
    }

    // -----------------------------------------------------------------------
    // 2. Hand → Stack (CastSpell)
    // -----------------------------------------------------------------------

    @Test
    fun handToStackCastSpell() {
        val (b, game, counter) = startGameAtMain1()
        playLand(b)
        b.snapshotFromGame(game)

        val player = human(b)
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
            ?: error("No creature in hand")
        val origId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        castCreature(b)
        val gsm = postAction(game, b, counter).gsmOrNull ?: error("No GSM after cast")
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
        val (b, game, counter) = startGameAtMain1()
        playLand(b)
        b.snapshotFromGame(game)

        val player = human(b)
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
            ?: error("No creature in hand")
        val forgeCardId = creature.id

        castCreature(b)
        postAction(game, b, counter)
        b.snapshotFromGame(game)
        val stackId = b.getOrAllocInstanceId(forgeCardId)

        passPriority(b)
        val gsm = postAction(game, b, counter).gsmOrNull ?: error("No GSM after resolve")
        val resolvedId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, resolvedId)
        assertNotNull(zt, "Should have ZoneTransfer for resolve")
        assertEquals(zt!!.category, "Resolve")

        assertTrue(hasEnteredZoneThisTurn(gsm, resolvedId), "BF destination should have EnteredZoneThisTurn")
    }

    // =======================================================================
    // Group B: Zone transition tests (no threads — startWithBoard)
    // =======================================================================

    // -----------------------------------------------------------------------
    // 4. Battlefield → Graveyard (Destroy)
    // -----------------------------------------------------------------------

    @Test
    fun battlefieldToGraveyardDestroy() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val creature = human(game).getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val origId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        val gsm = captureAfterAction(b, game, counter) {
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
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val creature = human(game).getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val origId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        val gsm = captureAfterAction(b, game, counter) {
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
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val creature = human(game).getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val origId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        val gsm = captureAfterAction(b, game, counter) {
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
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val creature = human(game).getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val origId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        val gsm = captureAfterAction(b, game, counter) {
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
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Library)
        }
        val player = human(game)
        val topCard = player.getZone(ZoneType.Library).cards.first()
        val forgeCardId = topCard.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterAction(b, game, counter) {
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
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Hand)
        }
        val player = human(game)
        val cardInHand = player.getZone(ZoneType.Hand).cards.first()
        val forgeCardId = cardInHand.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterAction(b, game, counter) {
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
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Library)
        }
        val player = human(game)
        val topCard = player.getZone(ZoneType.Library).cards.first()
        val forgeCardId = topCard.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterAction(b, game, counter) {
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
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Library)
        }
        val player = human(game)
        val topCard = player.getZone(ZoneType.Library).cards.first()
        val forgeCardId = topCard.id

        val gsm = captureAfterAction(b, game, counter) {
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
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Exile)
        }
        val player = human(game)
        val exiled = player.getZone(ZoneType.Exile).cards.first { it.isCreature }
        val exileId = b.getOrAllocInstanceId(exiled.id)

        val gsm = captureAfterAction(b, game, counter) {
            game.action.moveToPlay(exiled, null, AbilityKey.newMap())
        }
        val newId = b.getOrAllocInstanceId(exiled.id)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for exile→BF")

        assertTrue(hasEnteredZoneThisTurn(gsm, newId), "BF destination should have EnteredZoneThisTurn")
    }

    // -----------------------------------------------------------------------
    // 14. Graveyard → Battlefield (Reanimate/Put)
    // -----------------------------------------------------------------------

    @Test
    fun graveyardToBattlefield() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Graveyard)
        }
        val player = human(game)
        val inGY = player.getZone(ZoneType.Graveyard).cards.first { it.isCreature }
        val gyId = b.getOrAllocInstanceId(inGY.id)

        val gsm = captureAfterAction(b, game, counter) {
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
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Graveyard)
        }
        val player = human(game)
        val inGY = player.getZone(ZoneType.Graveyard).cards.first { it.isCreature }

        val gsm = captureAfterAction(b, game, counter) {
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
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Hand)
        }
        val player = human(game)
        val cardInHand = player.getZone(ZoneType.Hand).cards.first()
        val forgeCardId = cardInHand.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterAction(b, game, counter) {
            game.action.exile(cardInHand, null, AbilityKey.newMap())
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
        assertNotNull(zt, "Should have ZoneTransfer for Hand→Exile")
        assertEquals(zt!!.category, "Exile", "Hand→Exile should be Exile category")
    }

    // =======================================================================
    // Group C: Mechanic annotations (Stage 4 pipeline — startWithBoard)
    // =======================================================================

    // -----------------------------------------------------------------------
    // C1. Counter added
    // -----------------------------------------------------------------------

    @Test
    fun counterAddedProducesAnnotation() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val creature = human(game).getZone(ZoneType.Battlefield).cards.first { it.isCreature }

        val gsm = captureAfterAction(b, game, counter) {
            creature.addCounterInternal(CounterEnumType.P1P1, 2, human(game), true, null, AbilityKey.newMap())
        }

        val counterAnn = gsm.annotationsList.firstOrNull {
            AnnotationType.CounterAdded in it.typeList
        }
        assertNotNull(counterAnn, "Should have CounterAdded annotation")
        val counterType = counterAnn!!.detailsList.firstOrNull { it.key == "counter_type" }
        assertNotNull(counterType, "CounterAdded should have counter_type detail")
        assertEquals(counterType!!.getValueString(0), "+1/+1")
        val txnAmount = counterAnn.detailsList.first { it.key == "transaction_amount" }
        assertEquals(txnAmount.getValueInt32(0), 2, "transaction_amount should be 2")
    }

    // -----------------------------------------------------------------------
    // C2. Counter removed
    // -----------------------------------------------------------------------

    @Test
    fun counterRemovedProducesAnnotation() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val creature = human(game).getZone(ZoneType.Battlefield).cards.first { it.isCreature }

        // First add counters
        creature.addCounterInternal(CounterEnumType.P1P1, 3, human(game), true, null, AbilityKey.newMap())
        b.snapshotFromGame(game, counter.currentGsId())
        b.drainEvents()

        val gsm = captureAfterAction(b, game, counter) {
            creature.subtractCounter(CounterEnumType.P1P1, 2, human(game))
        }

        val counterAnn = gsm.annotationsList.firstOrNull {
            AnnotationType.CounterRemoved in it.typeList
        }
        assertNotNull(counterAnn, "Should have CounterRemoved annotation")
        val txnAmount = counterAnn!!.detailsList.first { it.key == "transaction_amount" }
        assertEquals(txnAmount.getValueInt32(0), 2, "transaction_amount should be 2")
    }

    // -----------------------------------------------------------------------
    // C3. Library shuffle
    // -----------------------------------------------------------------------

    /**
     * Shuffle annotation is intentionally suppressed in AnnotationPipeline —
     * the client's ShuffleAnnotationParser requires OldIds/NewIds detail keys
     * that we don't yet populate. Shuffle is cosmetic (animation only).
     *
     * This test verifies the event fires but no annotation is emitted.
     * When shuffle annotation support is added (with OldIds/NewIds), flip
     * this test to assert the annotation IS present.
     */
    @Test
    fun shuffleEventFiresButAnnotationSuppressed() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Library)
            addCard("Forest", human, ZoneType.Library)
        }
        val player = human(game)

        val gsm = captureAfterAction(b, game, counter) {
            player.shuffle(null)
        }

        val shuffleAnn = gsm.annotationsList.firstOrNull {
            AnnotationType.Shuffle in it.typeList
        }
        assertNull(shuffleAnn, "Shuffle annotation should be suppressed (no OldIds/NewIds detail keys yet)")
    }
}
