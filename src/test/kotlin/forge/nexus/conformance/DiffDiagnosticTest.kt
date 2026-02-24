package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import forge.nexus.game.ZoneIds
import forge.nexus.game.snapshotFromGame
import org.testng.Assert.*
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Diagnostic tests tracing exact diff contents for each game action.
 *
 * Accumulator consistency (zone-object refs, action instanceIds, no duplicates)
 * is now automatic via [ValidatingMessageSink]. What remains here are structural
 * assertions about diff contents — which zones appear, annotation types, field values.
 */
@Test(groups = ["integration", "conformance"])
class DiffDiagnosticTest : ConformanceTestBase() {

    @Test(description = "Diff after land play has correct GSM type, zones, and annotations")
    fun landPlayDiffStructure() {
        val (b, game, counter) = startGameAtMain1()

        gameStart(game, b, counter)
        b.snapshotFromGame(game)
        postAction(game, b, counter)

        playLand(b) ?: error("playLand failed at seed 42")

        val result = postAction(game, b, counter)
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

    @Test(description = "Cast creature -> pass -> resolve tracks zone placement correctly")
    fun castCreatureResolveFlow() {
        val (b, game, counter) = startGameAtMain1()
        val acc = ClientAccumulator()
        acc.seedFull(handshakeFull(game, b, counter.currentGsId()))

        val startResult = gameStart(game, b, counter)
        acc.processAll(startResult.messages)
        b.snapshotFromGame(game)

        playLand(b) ?: error("playLand failed at seed 42")
        val afterLand = postAction(game, b, counter)
        acc.processAll(afterLand.messages)

        val player = b.getPlayer(1)!!
        val creature = player.getZone(ForgeZoneType.Hand).cards.firstOrNull { it.isCreature }
            ?: error("No creature in hand at seed 42")
        val creatureForgeId = creature.id

        castCreature(b) ?: error("castCreature failed at seed 42")
        val afterCast = postAction(game, b, counter)
        acc.processAll(afterCast.messages)

        val creatureNewId = b.getOrAllocInstanceId(creatureForgeId)
        val creatureObj = checkNotNull(acc.objects[creatureNewId]) {
            "Creature should exist in accumulated objects with instanceId $creatureNewId"
        }

        if (game.stack.isEmpty) {
            assertEquals(creatureObj.zoneId, ZoneIds.BATTLEFIELD, "Creature should be on BF after resolution")
        } else {
            assertEquals(creatureObj.zoneId, ZoneIds.STACK, "Creature should be on Stack while on stack")

            passPriority(b)
            val afterPass = postAction(game, b, counter)
            acc.processAll(afterPass.messages)

            val resolved = checkNotNull(acc.objects[creatureNewId]) { "Creature should still exist after resolve" }
            assertEquals(resolved.zoneId, ZoneIds.BATTLEFIELD, "Creature should be on BF after resolution")

            val bfAfter = acc.zones[ZoneIds.BATTLEFIELD]!!
            assertTrue(
                bfAfter.objectInstanceIdsList.contains(creatureNewId),
                "BF should contain creature $creatureNewId after resolution",
            )
        }
    }

    @Test(description = "Resolve (Stack->BF) does NOT realloc instanceId")
    fun resolveKeepsInstanceId() {
        val (b, game, counter) = startGameAtMain1()

        playLand(b) ?: error("playLand failed at seed 42")
        postAction(game, b, counter)

        val player = b.getPlayer(1)!!
        val creature = player.getZone(ForgeZoneType.Hand).cards.firstOrNull { it.isCreature }
            ?: error("No creature in hand at seed 42")
        val creatureForgeId = creature.id

        castCreature(b) ?: error("castCreature failed at seed 42")
        postAction(game, b, counter)
        val castId = b.getOrAllocInstanceId(creatureForgeId)

        if (!game.stack.isEmpty) {
            passPriority(b)
            postAction(game, b, counter)
        }

        val resolvedId = b.getOrAllocInstanceId(creatureForgeId)
        assertEquals(castId, resolvedId, "Resolve should NOT change instanceId (Stack->BF keeps same ID)")
    }

    @Test(description = "aiActionDiff produces BF objects for AI land play")
    fun aiActionDiffContainsBattlefieldObjects() {
        val (b, game, counter) = startGameAtMain1()

        gameStart(game, b, counter)
        b.snapshotFromGame(game)

        playLand(b) ?: error("playLand failed at seed 42")

        val aiResult = BundleBuilder.aiActionDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
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
}
