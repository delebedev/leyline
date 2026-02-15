package forge.nexus.conformance

import org.testng.Assert.*
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

@Test(groups = ["unit"])
class ClientAccumulatorTest {

    @Test
    fun fullStateReplacesAllObjects() {
        val acc = ClientAccumulator()

        val gs = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(1)
            .addGameObjects(
                GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
            )
            .addGameObjects(
                GameObjectInfo.newBuilder().setInstanceId(101).setType(GameObjectType.Card),
            )
            .build()
        val gre = makeGsGRE(gs, 1)
        acc.process(gre)

        assertEquals(acc.objects.size, 2)
        assertTrue(acc.objects.containsKey(100))
        assertTrue(acc.objects.containsKey(101))
        assertEquals(acc.latestGsId, 1)
    }

    @Test
    fun diffMergesIntoExistingState() {
        val acc = ClientAccumulator()

        val full = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(1)
            .addGameObjects(
                GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
            )
            .build()
        acc.process(makeGsGRE(full, 1))

        val diff = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(2)
            .addGameObjects(
                GameObjectInfo.newBuilder().setInstanceId(101).setType(GameObjectType.Card),
            )
            .build()
        acc.process(makeGsGRE(diff, 2))

        assertEquals(acc.objects.size, 2, "Diff should ADD, not replace")
        assertTrue(acc.objects.containsKey(100))
        assertTrue(acc.objects.containsKey(101))
        assertEquals(acc.latestGsId, 2)
    }

    @Test
    fun fullStateResetsObjects() {
        val acc = ClientAccumulator()

        val full1 = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(1)
            .addGameObjects(
                GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
            )
            .build()
        acc.process(makeGsGRE(full1, 1))

        val full2 = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(2)
            .addGameObjects(
                GameObjectInfo.newBuilder().setInstanceId(200).setType(GameObjectType.Card),
            )
            .build()
        acc.process(makeGsGRE(full2, 2))

        assertEquals(acc.objects.size, 1, "Second Full should replace all objects")
        assertFalse(acc.objects.containsKey(100))
        assertTrue(acc.objects.containsKey(200))
    }

    @Test
    fun zonesTrackedFromState() {
        val acc = ClientAccumulator()

        val gs = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(1)
            .addZones(
                ZoneInfo.newBuilder().setZoneId(10).setType(ZoneType.Hand)
                    .addObjectInstanceIds(100).addObjectInstanceIds(101),
            )
            .addZones(
                ZoneInfo.newBuilder().setZoneId(20).setType(ZoneType.Library)
                    .addObjectInstanceIds(200),
            )
            .build()
        acc.process(makeGsGRE(gs, 1))

        assertEquals(acc.zones.size, 2)
        assertEquals(acc.zones[10]!!.objectInstanceIdsList.size, 2)
    }

    @Test
    fun gsIdMonotonicInvariant() {
        val acc = ClientAccumulator()

        val gs1 = GameStateMessage.newBuilder()
            .setType(GameStateType.Full).setGameStateId(5).build()
        acc.process(makeGsGRE(gs1, 1))
        assertEquals(acc.latestGsId, 5)

        val gs2 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff).setGameStateId(3).build()
        acc.process(makeGsGRE(gs2, 2))

        assertEquals(acc.latestGsId, 5, "latestGsId should track high-water mark")
    }

    @Test
    fun actionsAvailableReqTracked() {
        val acc = ClientAccumulator()

        val actions = ActionsAvailableReq.newBuilder()
            .addActions(
                Action.newBuilder()
                    .setActionType(ActionType.Play_add3)
                    .setInstanceId(100),
            )
            .build()
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.ActionsAvailableReq_695e)
            .setMsgId(1)
            .setGameStateId(1)
            .addSystemSeatIds(1)
            .setActionsAvailableReq(actions)
            .build()
        acc.process(gre)

        assertNotNull(acc.actions)
        assertEquals(acc.actions!!.actionsCount, 1)
    }

    @Test
    fun actionInstanceIdsMissingFromObjectsDetectsMissing() {
        val acc = ClientAccumulator()

        // Full state with object 100 only
        val gs = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(1)
            .addGameObjects(
                GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
            )
            .build()
        acc.process(makeGsGRE(gs, 1))

        // Actions referencing iid 100 (exists) and 200 (missing)
        val actions = ActionsAvailableReq.newBuilder()
            .addActions(Action.newBuilder().setActionType(ActionType.Play_add3).setInstanceId(100))
            .addActions(Action.newBuilder().setActionType(ActionType.Cast).setInstanceId(200))
            .build()
        val actionsGre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.ActionsAvailableReq_695e)
            .setMsgId(2).setGameStateId(1).addSystemSeatIds(1)
            .setActionsAvailableReq(actions)
            .build()
        acc.process(actionsGre)

        val missing = acc.actionInstanceIdsMissingFromObjects()
        assertEquals(missing.size, 1)
        assertEquals(missing[0], 200)
    }

    private fun makeGsGRE(gs: GameStateMessage, msgId: Int): GREToClientMessage =
        GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(msgId)
            .setGameStateId(gs.gameStateId)
            .addSystemSeatIds(1)
            .setGameStateMessage(gs)
            .build()
}
