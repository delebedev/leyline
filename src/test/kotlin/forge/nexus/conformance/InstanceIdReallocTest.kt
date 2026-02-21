package forge.nexus.conformance

import forge.nexus.game.ZoneIds
import forge.nexus.game.awaitFreshPending
import forge.nexus.game.snapshotFromGame
import forge.web.game.PlayerAction
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/**
 * Tests for instanceId reallocation on zone transfer and Limbo zone accumulation.
 *
 * Real client server allocates a new instanceId every time a card changes zones
 * (except Stack->Battlefield on resolve). The old instanceId is retired to Limbo
 * via objectInstanceIds in the Limbo ZoneInfo — no GameObjectInfo is emitted for it.
 *
 * Limbo is monotonically growing -- retired IDs are never removed. Each subsequent
 * buildFromGame must include the full retirement history.
 */
@Test(groups = ["integration", "conformance"])
class InstanceIdReallocTest : ConformanceTestBase() {

    // ===== PlayLand realloc =====

    @Test(description = "PlayLand: instanceId changes on zone transfer (Hand -> Battlefield)")
    fun playLandReallocsInstanceId() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: error("Player 1 not found")
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
        val origInstanceId = b.getOrAllocInstanceId(land.id)
        val forgeCardId = land.id

        playLand(b) ?: error("playLand failed at seed 42")
        postAction(game, b, 1, gsId)

        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)
        assertNotEquals(origInstanceId, newInstanceId, "PlayLand should allocate new instanceId on zone transfer")
    }

    @Test(description = "PlayLand: old instanceId retired to Limbo in bridge")
    fun playLandRetiresToLimbo() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: error("Player 1 not found")
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
        val origInstanceId = b.getOrAllocInstanceId(land.id)

        playLand(b) ?: error("playLand failed at seed 42")
        postAction(game, b, 1, gsId)

        assertTrue(
            b.getLimboInstanceIds().contains(origInstanceId),
            "Bridge Limbo should contain retired instanceId $origInstanceId, got: ${b.getLimboInstanceIds()}",
        )
    }

    @Test(description = "PlayLand: retired instanceId tracked in Limbo zone, no GameObjectInfo")
    fun playLandLimboZoneTracking() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: error("Player 1 not found")
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
        val origInstanceId = b.getOrAllocInstanceId(land.id)

        playLand(b) ?: error("playLand failed at seed 42")
        val gsm = postAction(game, b, 1, gsId).gsm

        // Limbo zone should contain the retired instanceId
        assertLimboContains(gsm, origInstanceId)

        // Real server doesn't send GameObjectInfo for Limbo objects
        val limboObjects = gsm.gameObjectsList.filter { it.zoneId == ZoneIds.LIMBO }
        assertTrue(limboObjects.isEmpty(), "Diff should not contain GameObjectInfo for Limbo objects, got: ${limboObjects.map { "iid=${it.instanceId}" }}")
    }

    // ===== CastSpell realloc =====

    @Test(description = "CastSpell: instanceId changes on zone transfer (Hand -> Stack)")
    fun castSpellReallocsInstanceId() {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: error("playLand failed at seed 42")
        b.snapshotFromGame(game)

        val player = b.getPlayer(1) ?: error("Player 1 not found")
        val creature = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: error("No creature in hand at seed 42")
        val origInstanceId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        castCreature(b) ?: error("castCreature failed at seed 42")
        postAction(game, b, 1, gsId + 2)

        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)
        assertNotEquals(origInstanceId, newInstanceId, "CastSpell should allocate new instanceId on zone transfer")
    }

    // ===== Resolve: no realloc =====

    @Test(description = "Resolve: instanceId NOT reallocated (Stack -> Battlefield keeps same ID)")
    fun resolveKeepsSameInstanceId() {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: error("playLand failed at seed 42")
        b.snapshotFromGame(game)
        var nextGsId = gsId + 2

        val player = b.getPlayer(1) ?: error("Player 1 not found")
        val creature = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: error("No creature in hand at seed 42")
        val forgeCardId = creature.id

        castCreature(b) ?: error("castCreature failed at seed 42")
        val castResult = postAction(game, b, 1, nextGsId)
        nextGsId = castResult.nextGsId

        val stackInstanceId = b.getOrAllocInstanceId(forgeCardId)
        b.snapshotFromGame(game)

        passPriority(b)
        postAction(game, b, 1, nextGsId)

        val bfInstanceId = b.getOrAllocInstanceId(forgeCardId)
        assertEquals(
            bfInstanceId,
            stackInstanceId,
            "Resolve (Stack->Battlefield) should keep same instanceId: stack=$stackInstanceId bf=$bfInstanceId",
        )
    }

    // ===== Limbo accumulation =====

    @Test(description = "Limbo grows monotonically: 2 land plays -> 2 entries in Limbo")
    fun limboGrowsAcrossMultiplePlays() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: error("Player 1 not found")
        val lands = player.getZone(forge.game.zone.ZoneType.Hand).cards.filter { it.isLand }
        if (lands.size < 2) return

        val land1 = lands[0]
        val origId1 = b.getOrAllocInstanceId(land1.id)
        playLand(b) ?: error("playLand failed at seed 42")
        postAction(game, b, 1, gsId)
        b.snapshotFromGame(game)

        assertEquals(b.getLimboInstanceIds().size, 1, "After 1 land play, Limbo should have 1 entry")
        assertTrue(b.getLimboInstanceIds().contains(origId1), "Limbo should contain first retired ID")

        advanceToNextMainPhase(b)

        val player2 = b.getPlayer(1) ?: error("Player 1 not found")
        val land2 = player2.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
        val origId2 = b.getOrAllocInstanceId(land2.id)

        val pending = awaitFreshPending(b, null) ?: error("No pending action available")
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(land2.id))
        awaitFreshPending(b, pending.actionId)

        postAction(game, b, 1, gsId + 10)

        val limbo = b.getLimboInstanceIds()
        assertTrue(limbo.size >= 2, "After 2 land plays, Limbo should have >= 2 entries, got: ${limbo.size}")
        assertTrue(limbo.contains(origId1), "Limbo should still contain first retired ID $origId1")
        assertTrue(limbo.contains(origId2), "Limbo should contain second retired ID $origId2")
    }

    @Test(description = "Limbo zone in GSM contains all accumulated retired instanceIds")
    fun limboZoneInGsmContainsAllRetiredIds() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: error("Player 1 not found")
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
        val origInstanceId = b.getOrAllocInstanceId(land.id)

        playLand(b) ?: error("playLand failed at seed 42")
        val gsm = postAction(game, b, 1, gsId).gsm

        assertLimboContains(gsm, origInstanceId)
    }

    @Test(description = "diffDeletedInstanceIds does NOT contain the retired ID immediately")
    fun noDiffDeletedOnRetirement() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: error("Player 1 not found")
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
        val origInstanceId = b.getOrAllocInstanceId(land.id)

        playLand(b) ?: error("playLand failed at seed 42")
        val gsm = postAction(game, b, 1, gsId).gsm

        assertTrue(
            !gsm.diffDeletedInstanceIdsList.contains(origInstanceId),
            "diffDeletedInstanceIds should NOT contain origId $origInstanceId immediately",
        )
    }

    // ===== ObjectIdChanged consistency =====

    @Test(description = "ObjectIdChanged orig_id matches pre-play instanceId, new_id matches post-play")
    fun objectIdChangedConsistency() {
        val (b, game, gsId) = startGameAtMain1()

        val player = b.getPlayer(1) ?: error("Player 1 not found")
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
        val origInstanceId = b.getOrAllocInstanceId(land.id)
        val forgeCardId = land.id

        playLand(b) ?: error("playLand failed at seed 42")
        val gsm = postAction(game, b, 1, gsId).gsm
        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)

        val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
        assertEquals(oic.detailInt("orig_id"), origInstanceId, "orig_id should match pre-play instanceId")
        assertEquals(oic.detailInt("new_id"), newInstanceId, "new_id should match post-play instanceId")
        assertTrue(oic.affectedIdsList.contains(origInstanceId), "affectedIds should contain orig instanceId")
    }

    // ===== Helpers =====

    private fun advanceToNextMainPhase(b: forge.nexus.game.GameBridge) {
        val game = b.getGame()!!
        var lastId: String? = null
        repeat(80) {
            if (game.isGameOver) return
            val pending = awaitFreshPending(b, lastId, timeoutMs = 5_000) ?: return
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
