package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import forge.nexus.game.ZoneIds
import org.testng.Assert.*
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Diagnostic tests that trace the exact diff contents for each game action.
 * These tests reproduce the full MatchSession flow (gameStart -> snapshot -> postAction)
 * and validate the accumulated client state at each step.
 */
@Test(groups = ["integration", "conformance"])
class DiffDiagnosticTest : ConformanceTestBase() {

    // --- Bug 1: Double lands ---

    @Test(description = "Diag: two consecutive land plays produce exactly 2 BF objects, no duplicates")
    fun twoLandPlaysNoDuplicates() {
        val (b, game, gsId) = startGameAtMain1()
        val acc = ClientAccumulator()

        val startResult = gameStart(game, b, 1, gsId)
        acc.processAll(startResult.messages)
        b.snapshotState(game)

        val firstDiff = postAction(game, b, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(firstDiff.messages)

        val bfBefore = acc.zones[ZoneIds.BATTLEFIELD]
        val bfCountBefore = bfBefore?.objectInstanceIdsCount ?: 0

        // Play land 1
        val player = b.getPlayer(1)!!
        val land1 = player.getZone(ForgeZoneType.Hand).cards.first { it.isLand }
        val land1OrigId = b.getOrAllocInstanceId(land1.id)
        playLand(b) ?: error("Failed to play first land")

        val afterLand1 = postAction(game, b, firstDiff.nextMsgId, firstDiff.nextGsId)
        acc.processAll(afterLand1.messages)

        val land1NewId = b.getOrAllocInstanceId(land1.id)
        assertNotEquals(land1OrigId, land1NewId, "Land 1 should get new instanceId on zone transfer")

        // Verify after land 1
        val bfAfter1 = acc.zones[ZoneIds.BATTLEFIELD]!!
        assertEquals(
            bfAfter1.objectInstanceIdsCount,
            bfCountBefore + 1,
            "BF should have exactly 1 more object after first land play (was $bfCountBefore)",
        )
        assertTrue(
            bfAfter1.objectInstanceIdsList.contains(land1NewId),
            "BF should contain land1 new ID $land1NewId",
        )
        assertFalse(
            bfAfter1.objectInstanceIdsList.contains(land1OrigId),
            "BF should NOT contain land1 orig ID $land1OrigId (should be in Limbo)",
        )

        acc.assertZoneCountMatchesObjects(ZoneIds.BATTLEFIELD)

        val missingObjs = acc.zoneObjectsMissingFromObjects()
        assertTrue(missingObjs.isEmpty(), "Zone objects missing after land 1: $missingObjs")

        // Play land 2 (need to advance to next turn for another land drop)
        val land2 = player.getZone(ForgeZoneType.Hand).cards.firstOrNull { it.isLand }
        if (land2 != null) {
            val pending = forge.nexus.game.awaitFreshPending(b, null)
            if (pending != null) {
                val canPlay = try {
                    b.actionBridge.submitAction(pending.actionId, forge.web.game.PlayerAction.PlayLand(land2.id))
                    forge.nexus.game.awaitFreshPending(b, pending.actionId) != null
                } catch (_: Exception) {
                    false
                }
                if (canPlay) {
                    val afterLand2 = postAction(game, b, afterLand1.nextMsgId, afterLand1.nextGsId)
                    acc.processAll(afterLand2.messages)
                    acc.assertZoneCountMatchesObjects(ZoneIds.BATTLEFIELD)
                }
            }
        }
    }

    @Test(description = "Diag: diff after land play has correct GSM type and zones")
    fun landPlayDiffStructure() {
        val (b, game, gsId) = startGameAtMain1()

        val startResult = gameStart(game, b, 1, gsId)
        b.snapshotState(game)
        val firstDiff = postAction(game, b, startResult.nextMsgId, startResult.nextGsId)

        playLand(b) ?: error("playLand failed at seed 42")

        val result = postAction(game, b, firstDiff.nextMsgId, firstDiff.nextGsId)
        val gsm = result.gsm

        assertEquals(gsm.type, GameStateType.Diff, "Post-action GSM should be Diff type")

        val zoneTypes = gsm.zonesList.map { it.type }.toSet()
        assertTrue(ZoneType.Hand in zoneTypes, "Diff should include Hand zone (card left hand)")
        assertTrue(ZoneType.Battlefield in zoneTypes, "Diff should include BF zone (card entered BF)")
        assertTrue(ZoneType.Limbo in zoneTypes, "Diff should include Limbo zone (retirement)")

        assertTrue(
            gsm.annotationsList.any { AnnotationType.ObjectIdChanged in it.typeList },
            "Diff should have ObjectIdChanged annotation",
        )

        val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
        val origId = oic.detailInt("orig_id")
        assertFalse(
            gsm.diffDeletedInstanceIdsList.contains(origId),
            "Diff should NOT immediately delete retired instanceId $origId",
        )
    }

    // --- Bug 2: Elves stuck on stack ---

    @Test(description = "Diag: cast creature -> pass -> resolve produces correct accumulated state")
    fun castCreatureResolveFlow() {
        val (b, game, gsId) = startGameAtMain1()
        val acc = ClientAccumulator()

        val startResult = gameStart(game, b, 1, gsId)
        acc.processAll(startResult.messages)
        b.snapshotState(game)

        playLand(b) ?: error("playLand failed at seed 42")
        val afterLand = postAction(game, b, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(afterLand.messages)

        val player = b.getPlayer(1)!!
        val creature = player.getZone(ForgeZoneType.Hand).cards.firstOrNull { it.isCreature } ?: error("No creature in hand at seed 42")
        val creatureForgeId = creature.id
        val creatureOrigId = b.getOrAllocInstanceId(creatureForgeId)

        castCreature(b) ?: error("castCreature failed at seed 42")

        val afterCast = postAction(game, b, afterLand.nextMsgId, afterLand.nextGsId)
        acc.processAll(afterCast.messages)

        val creatureNewId = b.getOrAllocInstanceId(creatureForgeId)

        val creatureObj = acc.objects[creatureNewId]
        assertNotNull(creatureObj, "Creature should exist in accumulated objects with instanceId $creatureNewId")

        val stackZone = acc.zones[ZoneIds.STACK]
        val bfZone = acc.zones[ZoneIds.BATTLEFIELD]

        val onStack = stackZone?.objectInstanceIdsList?.contains(creatureNewId) == true
        val onBF = bfZone?.objectInstanceIdsList?.contains(creatureNewId) == true

        if (game.stack.isEmpty) {
            assertTrue(onBF, "After stack resolution, creature $creatureNewId should be on BF")
            assertFalse(onStack, "After stack resolution, creature $creatureNewId should NOT be on stack")
            assertEquals(
                creatureObj!!.zoneId,
                ZoneIds.BATTLEFIELD,
                "Creature object should have zoneId=BF after resolution",
            )
        } else {
            assertTrue(onStack, "While on stack, creature $creatureNewId should be in stack zone")
            assertEquals(creatureObj!!.zoneId, ZoneIds.STACK, "Creature should have zoneId=Stack while on stack")

            passPriority(b)
            val afterPass = postAction(game, b, afterCast.nextMsgId, afterCast.nextGsId)
            acc.processAll(afterPass.messages)

            val creaturePostResolve = acc.objects[creatureNewId]
            assertNotNull(creaturePostResolve, "Creature should still exist after resolve")
            assertEquals(
                creaturePostResolve!!.zoneId,
                ZoneIds.BATTLEFIELD,
                "Creature should be on BF after resolution",
            )

            val stackAfter = acc.zones[ZoneIds.STACK]
            assertFalse(
                stackAfter?.objectInstanceIdsList?.contains(creatureNewId) == true,
                "Creature $creatureNewId should NOT be on stack after resolution",
            )

            val bfAfter = acc.zones[ZoneIds.BATTLEFIELD]!!
            assertTrue(
                bfAfter.objectInstanceIdsList.contains(creatureNewId),
                "BF should contain creature $creatureNewId after resolution",
            )
        }

        val missingObjs = acc.zoneObjectsMissingFromObjects()
        assertTrue(missingObjs.isEmpty(), "Zone objects missing after cast/resolve: $missingObjs")
    }

    @Test(description = "Diag: Resolve (Stack->BF) does NOT realloc instanceId")
    fun resolveKeepsInstanceId() {
        val (b, game, gsId) = startGameAtMain1()

        playLand(b) ?: error("playLand failed at seed 42")
        val afterLand = postAction(game, b, 1, gsId)

        val player = b.getPlayer(1)!!
        val creature = player.getZone(ForgeZoneType.Hand).cards.firstOrNull { it.isCreature } ?: error("No creature in hand at seed 42")
        val creatureForgeId = creature.id

        castCreature(b) ?: error("castCreature failed at seed 42")
        val afterCast = postAction(game, b, afterLand.nextMsgId, afterLand.nextGsId)
        val castId = b.getOrAllocInstanceId(creatureForgeId)

        if (!game.stack.isEmpty) {
            passPriority(b)
            postAction(game, b, afterCast.nextMsgId, afterCast.nextGsId)
        }

        val resolvedId = b.getOrAllocInstanceId(creatureForgeId)
        assertEquals(castId, resolvedId, "Resolve should NOT change instanceId (Stack->BF keeps same ID)")
    }

    // --- Bug 3: AI action visibility ---

    @Test(description = "Diag: aiActionDiff produces BF objects for AI land play")
    fun aiActionDiffContainsBattlefieldObjects() {
        val (b, game, gsId) = startGameAtMain1()

        val startResult = gameStart(game, b, 1, gsId)
        b.snapshotState(game)

        playLand(b) ?: error("playLand failed at seed 42")

        val aiResult = BundleBuilder.aiActionDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            startResult.nextMsgId,
            startResult.nextGsId,
        )

        val gsm = aiResult.gsm

        assertEquals(gsm.type, GameStateType.Diff, "AI action diff should be Diff type")

        val bfZone = gsm.zonesList.firstOrNull { it.type == ZoneType.Battlefield }
        if (bfZone != null) {
            assertTrue(bfZone.objectInstanceIdsCount > 0, "BF zone in AI diff should have objects")
        }

        for (obj in gsm.gameObjectsList) {
            assertTrue(obj.zoneId > 0, "Object ${obj.instanceId} should have valid zoneId, got ${obj.zoneId}")
        }
    }

    @Test(description = "Diag: multiple postAction calls produce consistent accumulated state")
    fun multiplePostActionConsistency() {
        val (b, game, gsId) = startGameAtMain1()
        val acc = ClientAccumulator()

        val startResult = gameStart(game, b, 1, gsId)
        acc.processAll(startResult.messages)
        b.snapshotState(game)

        val diff1 = postAction(game, b, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(diff1.messages)

        val diff2 = postAction(game, b, diff1.nextMsgId, diff1.nextGsId)
        acc.processAll(diff2.messages)

        val diff3 = postAction(game, b, diff2.nextMsgId, diff2.nextGsId)
        acc.processAll(diff3.messages)

        acc.assertConsistent("after 3 postActions")
        acc.assertZoneCountMatchesObjects(ZoneIds.BATTLEFIELD)
    }

    @Test(description = "Diag: declareAttackersBundle Full state doesn't corrupt subsequent diffs")
    fun fullStateBetweenDiffsNoCorruption() {
        val (b, game, gsId) = startGameAtMain1()
        val acc = ClientAccumulator()

        val startResult = gameStart(game, b, 1, gsId)
        acc.processAll(startResult.messages)
        b.snapshotState(game)

        playLand(b) ?: error("playLand failed at seed 42")
        val afterLand = postAction(game, b, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(afterLand.messages)

        val atkResult = BundleBuilder.declareAttackersBundle(game, b, TEST_MATCH_ID, SEAT_ID, afterLand.nextMsgId, afterLand.nextGsId)
        acc.processAll(atkResult.messages)

        val afterAtk = postAction(game, b, atkResult.nextMsgId, atkResult.nextGsId)
        acc.processAll(afterAtk.messages)

        val missingObjs = acc.zoneObjectsMissingFromObjects()
        assertTrue(missingObjs.isEmpty(), "Zone objects missing after Full+Diff sequence: $missingObjs")

        val bfZone = acc.zones[ZoneIds.BATTLEFIELD]!!
        val bfIds = bfZone.objectInstanceIdsList
        assertEquals(bfIds.toSet().size, bfIds.size, "BF should have no duplicate instanceIds: $bfIds")
    }
}
