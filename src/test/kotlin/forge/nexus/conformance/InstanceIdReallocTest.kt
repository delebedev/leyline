package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import forge.nexus.game.awaitFreshPending
import forge.web.game.PlayerAction
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/**
 * Tests for instanceId reallocation on zone transfer and Limbo zone accumulation.
 *
 * Real Arena server allocates a new instanceId every time a card changes zones
 * (except Stack→Battlefield on resolve). The old instanceId is retired to Limbo
 * with a Private gameObject so the client moves the card out of its old zone.
 *
 * Limbo is monotonically growing — retired IDs are never removed. Each subsequent
 * buildFromGame must include the full retirement history.
 */
@Test(groups = ["integration", "conformance"])
class InstanceIdReallocTest : ConformanceTestBase() {

    private companion object {
        const val ZONE_BATTLEFIELD = 28
        const val ZONE_LIMBO = 30
        const val ZONE_P1_HAND = 31
    }

    // ===== PlayLand realloc =====

    @Test(description = "PlayLand: instanceId changes on zone transfer (Hand → Battlefield)")
    fun playLandReallocsInstanceId() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: return
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return
        val origInstanceId = b.getOrAllocInstanceId(land.id)
        val forgeCardId = land.id

        playLand(b) ?: return
        BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)

        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)
        assertNotEquals(origInstanceId, newInstanceId, "PlayLand should allocate new instanceId on zone transfer")
    }

    @Test(description = "PlayLand: old instanceId retired to Limbo in bridge")
    fun playLandRetiresToLimbo() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: return
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return
        val origInstanceId = b.getOrAllocInstanceId(land.id)

        playLand(b) ?: return
        BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)

        assertTrue(
            b.getLimboInstanceIds().contains(origInstanceId),
            "Bridge Limbo should contain retired instanceId $origInstanceId, got: ${b.getLimboInstanceIds()}",
        )
    }

    @Test(description = "PlayLand: Limbo gameObject has Private visibility and viewers=[owner]")
    fun playLandLimboGameObjectVisibility() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: return
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return
        val origInstanceId = b.getOrAllocInstanceId(land.id)

        playLand(b) ?: return
        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val gsm = result.messages.first { it.hasGameStateMessage() }.gameStateMessage

        val limboObj = gsm.gameObjectsList.firstOrNull { it.instanceId == origInstanceId }
        assertTrue(limboObj != null, "Should have Limbo gameObject for retired ID $origInstanceId")
        assertEquals(limboObj!!.visibility, Visibility.Private, "Limbo gameObject should be Private")
        assertEquals(limboObj.zoneId, ZONE_LIMBO, "Limbo gameObject should be in Limbo zone")
        assertTrue(limboObj.viewersList.isNotEmpty(), "Limbo gameObject should have viewers")
        assertEquals(limboObj.getViewers(0), 1, "Limbo gameObject viewers should include owner seat (1)")
    }

    // ===== CastSpell realloc =====

    @Test(description = "CastSpell: instanceId changes on zone transfer (Hand → Stack)")
    fun castSpellReallocsInstanceId() {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: return
        b.snapshotState(game)

        val player = b.getPlayer(1) ?: return
        val creature = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return
        val origInstanceId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        castCreature(b) ?: return
        BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId + 2)

        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)
        assertNotEquals(origInstanceId, newInstanceId, "CastSpell should allocate new instanceId on zone transfer")
    }

    // ===== Resolve: no realloc =====

    @Test(description = "Resolve: instanceId NOT reallocated (Stack → Battlefield keeps same ID)")
    fun resolveKeepsSameInstanceId() {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: return
        b.snapshotState(game)
        var nextGsId = gsId + 2

        // Cast creature → on stack
        val player = b.getPlayer(1) ?: return
        val creature = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return
        val forgeCardId = creature.id

        castCreature(b) ?: return
        val castResult = BundleBuilder.postAction(game, b, "test-match", 1, 1, nextGsId)
        nextGsId = castResult.nextGsId

        // After cast, get the current instanceId (post-realloc for Hand→Stack)
        val stackInstanceId = b.getOrAllocInstanceId(forgeCardId)
        b.snapshotState(game)

        // Pass to resolve
        passPriority(b)
        BundleBuilder.postAction(game, b, "test-match", 1, 1, nextGsId)

        val bfInstanceId = b.getOrAllocInstanceId(forgeCardId)
        assertEquals(
            bfInstanceId,
            stackInstanceId,
            "Resolve (Stack→Battlefield) should keep same instanceId: stack=$stackInstanceId bf=$bfInstanceId",
        )
    }

    // ===== Limbo accumulation =====

    @Test(description = "Limbo grows monotonically: 2 land plays → 2 entries in Limbo")
    fun limboGrowsAcrossMultiplePlays() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: return
        val lands = player.getZone(forge.game.zone.ZoneType.Hand).cards.filter { it.isLand }
        if (lands.size < 2) return // need 2 lands

        // Play land 1
        val land1 = lands[0]
        val origId1 = b.getOrAllocInstanceId(land1.id)
        playLand(b) ?: return
        BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        b.snapshotState(game)

        assertEquals(b.getLimboInstanceIds().size, 1, "After 1 land play, Limbo should have 1 entry")
        assertTrue(b.getLimboInstanceIds().contains(origId1), "Limbo should contain first retired ID")

        // We need to advance to the next turn to play a second land
        // (only 1 land per turn). Pass priority repeatedly.
        advanceToNextMainPhase(b)

        val player2 = b.getPlayer(1) ?: return
        val land2 = player2.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return
        val origId2 = b.getOrAllocInstanceId(land2.id)

        val pending = awaitFreshPending(b, null) ?: return
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(land2.id))
        awaitFreshPending(b, pending.actionId)

        BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId + 10)

        val limbo = b.getLimboInstanceIds()
        assertTrue(limbo.size >= 2, "After 2 land plays, Limbo should have >= 2 entries, got: ${limbo.size}")
        assertTrue(limbo.contains(origId1), "Limbo should still contain first retired ID $origId1")
        assertTrue(limbo.contains(origId2), "Limbo should contain second retired ID $origId2")
    }

    @Test(description = "Limbo zone in GSM contains all accumulated retired instanceIds")
    fun limboZoneInGsmContainsAllRetiredIds() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: return
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return
        val origInstanceId = b.getOrAllocInstanceId(land.id)

        playLand(b) ?: return
        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val gsm = result.messages.first { it.hasGameStateMessage() }.gameStateMessage

        val limboZone = gsm.zonesList.firstOrNull { it.type == ZoneType.Limbo }
        assertTrue(limboZone != null, "GSM should have Limbo zone")
        assertTrue(
            limboZone!!.objectInstanceIdsList.contains(origInstanceId),
            "Limbo zone in GSM should contain retired ID $origInstanceId",
        )
    }

    @Test(description = "diffDeletedInstanceIds does NOT contain the retired ID immediately")
    fun noDiffDeletedOnRetirement() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: return
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return
        val origInstanceId = b.getOrAllocInstanceId(land.id)

        playLand(b) ?: return
        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val gsm = result.messages.first { it.hasGameStateMessage() }.gameStateMessage

        assertTrue(
            !gsm.diffDeletedInstanceIdsList.contains(origInstanceId),
            "diffDeletedInstanceIds should NOT contain origId $origInstanceId immediately",
        )
    }

    // ===== ObjectIdChanged consistency =====

    @Test(description = "ObjectIdChanged orig_id matches pre-play instanceId, new_id matches post-play")
    fun objectIdChangedConsistency() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: return
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return
        val origInstanceId = b.getOrAllocInstanceId(land.id)
        val forgeCardId = land.id

        playLand(b) ?: return
        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val gsm = result.messages.first { it.hasGameStateMessage() }.gameStateMessage
        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)

        val oic = gsm.annotationsList.first { AnnotationType.ObjectIdChanged in it.typeList }
        val origDetail = oic.detailsList.first { it.key == "orig_id" }
        val newDetail = oic.detailsList.first { it.key == "new_id" }

        assertEquals(origDetail.getValueInt32(0), origInstanceId, "orig_id should match pre-play instanceId")
        assertEquals(newDetail.getValueInt32(0), newInstanceId, "new_id should match post-play instanceId")
        assertTrue(oic.affectedIdsList.contains(origInstanceId), "affectedIds should contain orig instanceId")
    }

    // ===== Helpers =====

    /** Advance through priority stops until we reach a Main phase on player's turn. */
    private fun advanceToNextMainPhase(b: forge.nexus.game.GameBridge) {
        val game = b.getGame()!!
        var lastId: String? = null
        repeat(80) {
            if (game.isGameOver) return
            val pending = awaitFreshPending(b, lastId, timeoutMs = 5_000) ?: return
            // Check if we're at MAIN1 or MAIN2 on a DIFFERENT turn than turn 1
            if ((pending.state.phase == "MAIN1" || pending.state.phase == "MAIN2") &&
                game.phaseHandler.turn > 1 &&
                game.phaseHandler.playerTurn == b.getPlayer(1)
            ) {
                return
            }
            b.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            lastId = pending.actionId
        }
    }
}
