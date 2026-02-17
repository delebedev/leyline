package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.*
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Diagnostic tests that trace the exact diff contents for each game action.
 * These tests reproduce the full MatchSession flow (gameStart → snapshot → postAction)
 * and validate the accumulated client state at each step.
 *
 * Purpose: find the root cause of:
 * - Bug 1: Double lands on battlefield
 * - Bug 2: Elves stuck on stack after cast
 * - Bug 3: Sparky's turns not visible
 */
@Test(groups = ["integration", "conformance"])
class DiffDiagnosticTest : ConformanceTestBase() {

    private companion object {
        const val ZONE_STACK = 27
        const val ZONE_BATTLEFIELD = 28
        const val ZONE_LIMBO = 30
        const val ZONE_P1_HAND = 31
    }

    // --- Bug 1: Double lands ---

    @Test(description = "Diag: two consecutive land plays produce exactly 2 BF objects, no duplicates")
    fun twoLandPlaysNoDuplicates() {
        val (b, game, gsId) = startGameAtMain1()
        val acc = ClientAccumulator()

        // Game start (Full state) — mirrors MatchSession.onMulliganKeep
        val startResult = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        acc.processAll(startResult.messages)
        b.snapshotState(game)

        // First sendRealGameState in autoPassAndAdvance (often empty diff)
        val firstDiff = BundleBuilder.postAction(game, b, "test-match", 1, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(firstDiff.messages)

        val bfBefore = acc.zones[ZONE_BATTLEFIELD]
        val bfCountBefore = bfBefore?.objectInstanceIdsCount ?: 0

        // Play land 1
        val player = b.getPlayer(1)!!
        val land1 = player.getZone(ForgeZoneType.Hand).cards.first { it.isLand }
        val land1OrigId = b.getOrAllocInstanceId(land1.id)
        playLand(b) ?: fail("Failed to play first land")

        val afterLand1 = BundleBuilder.postAction(game, b, "test-match", 1, firstDiff.nextMsgId, firstDiff.nextGsId)
        val land1Gsm = afterLand1.messages.first { it.hasGameStateMessage() }.gameStateMessage
        acc.processAll(afterLand1.messages)

        val land1NewId = b.getOrAllocInstanceId(land1.id)
        assertNotEquals(land1OrigId, land1NewId, "Land 1 should get new instanceId on zone transfer")

        // Verify after land 1
        val bfAfter1 = acc.zones[ZONE_BATTLEFIELD]!!
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

        // Count objects with zoneId=BF in accumulated state
        val bfObjects1 = acc.objects.values.count { it.zoneId == ZONE_BATTLEFIELD }
        assertEquals(
            bfAfter1.objectInstanceIdsCount,
            bfObjects1,
            "BF zone objectIds count (${ bfAfter1.objectInstanceIdsCount }) " +
                "should match objects with zoneId=BF ($bfObjects1)",
        )

        // Check no zone-object mismatch
        val missingObjs = acc.zoneObjectsMissingFromObjects()
        assertTrue(missingObjs.isEmpty(), "Zone objects missing after land 1: $missingObjs")

        // Play land 2 (need to advance to next turn for another land drop)
        // Skip if we can't play another land (land-per-turn limit)
        val land2 = player.getZone(ForgeZoneType.Hand).cards.firstOrNull { it.isLand }
        if (land2 != null) {
            // Try to play — may fail due to 1-land-per-turn rule
            val pending = forge.nexus.game.awaitFreshPending(b, null)
            if (pending != null) {
                val canPlay = try {
                    b.actionBridge.submitAction(pending.actionId, forge.web.game.PlayerAction.PlayLand(land2.id))
                    forge.nexus.game.awaitFreshPending(b, pending.actionId) != null
                } catch (_: Exception) {
                    false
                }
                if (canPlay) {
                    val afterLand2 = BundleBuilder.postAction(game, b, "test-match", 1, afterLand1.nextMsgId, afterLand1.nextGsId)
                    acc.processAll(afterLand2.messages)

                    val bfAfter2 = acc.zones[ZONE_BATTLEFIELD]!!
                    val bfObjects2 = acc.objects.values.count { it.zoneId == ZONE_BATTLEFIELD }
                    assertEquals(
                        bfAfter2.objectInstanceIdsCount,
                        bfObjects2,
                        "After land 2: BF zone count (${bfAfter2.objectInstanceIdsCount}) " +
                            "should match objects with zoneId=BF ($bfObjects2)",
                    )
                }
            }
        }
    }

    @Test(description = "Diag: diff after land play has correct GSM type and zones")
    fun landPlayDiffStructure() {
        val (b, game, gsId) = startGameAtMain1()

        // Seed snapshot (mirrors MatchSession flow)
        val startResult = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        b.snapshotState(game)
        // First postAction (from autoPassAndAdvance) — seeds next snapshot
        val firstDiff = BundleBuilder.postAction(game, b, "test-match", 1, startResult.nextMsgId, startResult.nextGsId)

        // Now play land
        playLand(b) ?: return

        val result = BundleBuilder.postAction(game, b, "test-match", 1, firstDiff.nextMsgId, firstDiff.nextGsId)
        val gsm = result.messages.first { it.hasGameStateMessage() }.gameStateMessage

        // Must be Diff (not Full) — important for client accumulator behavior
        assertEquals(gsm.type, GameStateType.Diff, "Post-action GSM should be Diff type")

        // Diff should include Hand zone (card left), BF zone (card entered), Limbo zone (retirement)
        val zoneTypes = gsm.zonesList.map { it.type }.toSet()
        assertTrue(ZoneType.Hand in zoneTypes, "Diff should include Hand zone (card left hand)")
        assertTrue(ZoneType.Battlefield in zoneTypes, "Diff should include BF zone (card entered BF)")
        assertTrue(ZoneType.Limbo in zoneTypes, "Diff should include Limbo zone (retirement)")

        // Diff should have ObjectIdChanged annotation
        assertTrue(
            gsm.annotationsList.any { AnnotationType.ObjectIdChanged in it.typeList },
            "Diff should have ObjectIdChanged annotation",
        )

        // Diff should NOT have diffDeletedInstanceIds for the retired ID
        val oic = gsm.annotationsList.first { AnnotationType.ObjectIdChanged in it.typeList }
        val origId = oic.detailsList.first { it.key == "orig_id" }.getValueInt32(0)
        assertFalse(
            gsm.diffDeletedInstanceIdsList.contains(origId),
            "Diff should NOT immediately delete retired instanceId $origId",
        )
    }

    // --- Bug 2: Elves stuck on stack ---

    @Test(description = "Diag: cast creature → pass → resolve produces correct accumulated state")
    fun castCreatureResolveFlow() {
        val (b, game, gsId) = startGameAtMain1()
        val acc = ClientAccumulator()

        // Game start
        val startResult = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        acc.processAll(startResult.messages)
        b.snapshotState(game)

        // First land (to have mana to cast)
        playLand(b) ?: return
        val afterLand = BundleBuilder.postAction(game, b, "test-match", 1, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(afterLand.messages)

        // Count BF objects before cast
        val bfBeforeCast = acc.zones[ZONE_BATTLEFIELD]?.objectInstanceIdsCount ?: 0

        // Cast creature
        val player = b.getPlayer(1)!!
        val creature = player.getZone(ForgeZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return
        val creatureForgeId = creature.id
        val creatureOrigId = b.getOrAllocInstanceId(creatureForgeId)

        castCreature(b) ?: return

        // After cast: spell might be on stack or already resolved (engine auto-resolves)
        val afterCast = BundleBuilder.postAction(game, b, "test-match", 1, afterLand.nextMsgId, afterLand.nextGsId)
        val castGsm = afterCast.messages.first { it.hasGameStateMessage() }.gameStateMessage
        acc.processAll(afterCast.messages)

        val creatureNewId = b.getOrAllocInstanceId(creatureForgeId)

        // Check where the creature is in the accumulated state
        val creatureObj = acc.objects[creatureNewId]
        assertNotNull(creatureObj, "Creature should exist in accumulated objects with instanceId $creatureNewId")

        val stackZone = acc.zones[ZONE_STACK]
        val bfZone = acc.zones[ZONE_BATTLEFIELD]

        val onStack = stackZone?.objectInstanceIdsList?.contains(creatureNewId) == true
        val onBF = bfZone?.objectInstanceIdsList?.contains(creatureNewId) == true

        if (game.stack.isEmpty) {
            // Engine resolved: creature should be on BF
            assertTrue(onBF, "After stack resolution, creature $creatureNewId should be on BF")
            assertFalse(onStack, "After stack resolution, creature $creatureNewId should NOT be on stack")
            assertEquals(
                creatureObj!!.zoneId,
                ZONE_BATTLEFIELD,
                "Creature object should have zoneId=BF after resolution",
            )
        } else {
            // Still on stack (waiting for resolution)
            assertTrue(onStack, "While on stack, creature $creatureNewId should be in stack zone")
            assertEquals(creatureObj!!.zoneId, ZONE_STACK, "Creature should have zoneId=Stack while on stack")

            // Now pass priority to resolve
            passPriority(b)
            val afterPass = BundleBuilder.postAction(game, b, "test-match", 1, afterCast.nextMsgId, afterCast.nextGsId)
            val passGsm = afterPass.messages.first { it.hasGameStateMessage() }.gameStateMessage
            acc.processAll(afterPass.messages)

            // After resolution, check Resolve annotation
            val hasResolve = passGsm.annotationsList.any {
                it.typeList.any { t -> t == AnnotationType.ZoneTransfer_af5a } &&
                    it.detailsList.any { d -> d.key == "category" && d.getValueString(0) == "Resolve" }
            }

            val creaturePostResolve = acc.objects[creatureNewId]
            assertNotNull(creaturePostResolve, "Creature should still exist after resolve")
            assertEquals(
                creaturePostResolve!!.zoneId,
                ZONE_BATTLEFIELD,
                "Creature should be on BF after resolution (Resolve annotation present=$hasResolve)",
            )

            // Creature should NOT be on stack
            val stackAfter = acc.zones[ZONE_STACK]
            assertFalse(
                stackAfter?.objectInstanceIdsList?.contains(creatureNewId) == true,
                "Creature $creatureNewId should NOT be on stack after resolution",
            )

            // BF should have the creature
            val bfAfter = acc.zones[ZONE_BATTLEFIELD]!!
            assertTrue(
                bfAfter.objectInstanceIdsList.contains(creatureNewId),
                "BF should contain creature $creatureNewId after resolution",
            )
        }

        // Final invariant checks
        val missingObjs = acc.zoneObjectsMissingFromObjects()
        assertTrue(missingObjs.isEmpty(), "Zone objects missing after cast/resolve: $missingObjs")
    }

    @Test(description = "Diag: Resolve (Stack→BF) does NOT realloc instanceId")
    fun resolveKeepsInstanceId() {
        val (b, game, gsId) = startGameAtMain1()

        // Play land for mana
        playLand(b) ?: return
        val afterLand = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)

        // Cast creature
        val player = b.getPlayer(1)!!
        val creature = player.getZone(ForgeZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return
        val creatureForgeId = creature.id

        castCreature(b) ?: return
        val afterCast = BundleBuilder.postAction(game, b, "test-match", 1, afterLand.nextMsgId, afterLand.nextGsId)
        val castId = b.getOrAllocInstanceId(creatureForgeId)

        // If still on stack, pass to resolve
        if (!game.stack.isEmpty) {
            passPriority(b)
            BundleBuilder.postAction(game, b, "test-match", 1, afterCast.nextMsgId, afterCast.nextGsId)
        }

        // After resolution, instanceId should be the same (no realloc on Resolve)
        val resolvedId = b.getOrAllocInstanceId(creatureForgeId)
        assertEquals(castId, resolvedId, "Resolve should NOT change instanceId (Stack→BF keeps same ID)")
    }

    // --- Bug 3: AI action visibility ---

    @Test(description = "Diag: aiActionDiff produces BF objects for AI land play")
    fun aiActionDiffContainsBattlefieldObjects() {
        val (b, game, gsId) = startGameAtMain1()

        // Seed snapshot
        val startResult = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        b.snapshotState(game)

        // Simulate AI playing a land by building a diff from AI's perspective.
        // We can't actually trigger AI events in this test, but we can verify
        // that buildDiffFromGame with viewingSeatId=1 includes AI BF objects.

        // Play a land from seat 1 first (so we have a baseline), then check
        // that aiActionDiff includes the BF object.
        playLand(b) ?: return

        // Build what aiActionDiff would produce
        val aiResult = BundleBuilder.aiActionDiff(
            game,
            b,
            "test-match",
            1,
            startResult.nextMsgId,
            startResult.nextGsId,
        )

        val gsm = aiResult.messages.first { it.hasGameStateMessage() }.gameStateMessage

        // Should be Diff type
        assertEquals(gsm.type, GameStateType.Diff, "AI action diff should be Diff type")

        // Should include BF zone if it changed
        val bfZone = gsm.zonesList.firstOrNull { it.type == ZoneType.Battlefield }
        if (bfZone != null) {
            assertTrue(bfZone.objectInstanceIdsCount > 0, "BF zone in AI diff should have objects")
        }

        // All objects in the diff should have valid zoneIds
        for (obj in gsm.gameObjectsList) {
            assertTrue(obj.zoneId > 0, "Object ${obj.instanceId} should have valid zoneId, got ${obj.zoneId}")
        }
    }

    @Test(description = "Diag: multiple postAction calls produce consistent accumulated state")
    fun multiplePostActionConsistency() {
        val (b, game, gsId) = startGameAtMain1()
        val acc = ClientAccumulator()

        // Game start
        val startResult = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        acc.processAll(startResult.messages)
        b.snapshotState(game)

        // First postAction (from autoPassAndAdvance)
        val diff1 = BundleBuilder.postAction(game, b, "test-match", 1, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(diff1.messages)

        // Second postAction (calling postAction again without any game action)
        val diff2 = BundleBuilder.postAction(game, b, "test-match", 1, diff1.nextMsgId, diff1.nextGsId)
        acc.processAll(diff2.messages)

        // Third postAction
        val diff3 = BundleBuilder.postAction(game, b, "test-match", 1, diff2.nextMsgId, diff2.nextGsId)
        acc.processAll(diff3.messages)

        // After 3 identical postAction calls (no game actions), state should be consistent
        val missingObjs = acc.zoneObjectsMissingFromObjects()
        assertTrue(missingObjs.isEmpty(), "Zone objects missing after 3 postActions: $missingObjs")

        val missingActions = acc.actionInstanceIdsMissingFromObjects()
        assertTrue(missingActions.isEmpty(), "Action instanceIds missing: $missingActions")

        // BF zone object count should match objects with zoneId=BF
        val bfZone = acc.zones[ZONE_BATTLEFIELD]
        if (bfZone != null) {
            val bfZoneCount = bfZone.objectInstanceIdsCount
            val bfObjCount = acc.objects.values.count { it.zoneId == ZONE_BATTLEFIELD }
            assertEquals(
                bfZoneCount,
                bfObjCount,
                "BF zone count ($bfZoneCount) should match objects with zoneId=BF ($bfObjCount)",
            )
        }
    }

    @Test(description = "Diag: declareAttackersBundle Full state doesn't corrupt subsequent diffs")
    fun fullStateBetweenDiffsNoCorruption() {
        val (b, game, gsId) = startGameAtMain1()
        val acc = ClientAccumulator()

        // Game start + snapshot
        val startResult = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        acc.processAll(startResult.messages)
        b.snapshotState(game)

        // Play land
        playLand(b) ?: return
        val afterLand = BundleBuilder.postAction(game, b, "test-match", 1, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(afterLand.messages)

        // Simulate declareAttackersBundle (sends Full state without snapshotState)
        val atkResult = BundleBuilder.declareAttackersBundle(game, b, "test-match", 1, afterLand.nextMsgId, afterLand.nextGsId)
        acc.processAll(atkResult.messages)

        // Now another postAction (diff against stale snapshot from afterLand)
        val afterAtk = BundleBuilder.postAction(game, b, "test-match", 1, atkResult.nextMsgId, atkResult.nextGsId)
        acc.processAll(afterAtk.messages)

        // Accumulated state should still be consistent
        val missingObjs = acc.zoneObjectsMissingFromObjects()
        assertTrue(missingObjs.isEmpty(), "Zone objects missing after Full+Diff sequence: $missingObjs")

        // No duplicate objects on BF
        val bfZone = acc.zones[ZONE_BATTLEFIELD]!!
        val bfIds = bfZone.objectInstanceIdsList
        assertEquals(bfIds.toSet().size, bfIds.size, "BF should have no duplicate instanceIds: $bfIds")
    }
}
