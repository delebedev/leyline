package leyline.conformance

import leyline.recording.GREToDecoded
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

@Test(groups = ["unit"])
class GREToDecodedTest {

    // ── helpers ──────────────────────────────────────────────────────

    private fun zoneBuilder(
        id: Int,
        type: ZoneType,
        owner: Int,
        visibility: Visibility,
        objectIds: List<Int> = emptyList(),
        viewers: List<Int> = emptyList(),
    ): ZoneInfo.Builder = ZoneInfo.newBuilder()
        .setZoneId(id)
        .setType(type)
        .setOwnerSeatId(owner)
        .setVisibility(visibility)
        .addAllObjectInstanceIds(objectIds)
        .addAllViewers(viewers)

    private fun objectBuilder(
        instanceId: Int,
        grpId: Int,
        zoneId: Int,
        type: GameObjectType,
        owner: Int = 1,
        controller: Int = 1,
    ): GameObjectInfo.Builder = GameObjectInfo.newBuilder()
        .setInstanceId(instanceId)
        .setGrpId(grpId)
        .setZoneId(zoneId)
        .setType(type)
        .setOwnerSeatId(owner)
        .setControllerSeatId(controller)
        .setVisibility(Visibility.Public)

    private fun annotationBuilder(
        id: Int,
        types: List<AnnotationType>,
        affectorId: Int = 0,
        affectedIds: List<Int> = emptyList(),
    ): AnnotationInfo.Builder = AnnotationInfo.newBuilder()
        .setId(id)
        .addAllType(types)
        .setAffectorId(affectorId)
        .addAllAffectedIds(affectedIds)

    private fun turnInfoBuilder(
        turn: Int = 1,
        active: Int = 1,
        priority: Int = 1,
        decision: Int = 1,
        phase: Phase = Phase.Beginning_a549,
        step: Step = Step.Upkeep_a2cb,
    ): TurnInfo.Builder = TurnInfo.newBuilder()
        .setTurnNumber(turn)
        .setActivePlayer(active)
        .setPriorityPlayer(priority)
        .setDecisionPlayer(decision)
        .setPhase(phase)
        .setStep(step)

    private fun playerBuilder(seat: Int, life: Int, status: PlayerStatus): PlayerInfo.Builder =
        PlayerInfo.newBuilder()
            .setSystemSeatNumber(seat)
            .setLifeTotal(life)
            .setStatus(status)

    // ── test: Full GameStateMessage with all fields ─────────────────

    @Test(description = "Full GSM: zones, objects, annotations, turnInfo, players, actions")
    fun fullGameStateMessage() {
        val gsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(10)
            .setUpdate(GameStateUpdate.SendAndRecord)
            .addZones(
                zoneBuilder(
                    id = 1,
                    type = ZoneType.Hand,
                    owner = 1,
                    visibility = Visibility.Private,
                    objectIds = listOf(100, 101),
                    viewers = listOf(1),
                ),
            )
            .addGameObjects(
                objectBuilder(100, 70000, 1, GameObjectType.Card)
                    .setIsTapped(true)
                    .setPower(Int32Value.newBuilder().setValue(3))
                    .setToughness(Int32Value.newBuilder().setValue(4))
                    .addSuperTypes(SuperType.Basic)
                    .addCardTypes(CardType.Creature)
                    .addSubtypes(SubType.Goblin),
            )
            .addAnnotations(
                annotationBuilder(
                    id = 1,
                    types = listOf(AnnotationType.ZoneTransfer_af5a),
                    affectorId = 100,
                    affectedIds = listOf(100),
                ),
            )
            .addActions(
                ActionInfo.newBuilder()
                    .setSeatId(1)
                    .setAction(
                        Action.newBuilder()
                            .setActionType(ActionType.Play_add3)
                            .setInstanceId(100)
                            .setGrpId(70000),
                    ),
            )
            .addPlayers(playerBuilder(1, 20, PlayerStatus.InGame_a1c6))
            .addPlayers(playerBuilder(2, 20, PlayerStatus.InGame_a1c6))
            .setTurnInfo(turnInfoBuilder())
            .build()

        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(5)
            .setGameStateId(10)
            .setGameStateMessage(gsm)
            .addSystemSeatIds(1)
            .build()

        val decoded = GREToDecoded.convert(gre, index = 0)

        assertEquals(decoded.index, 0)
        assertEquals(decoded.file, "live")
        assertEquals(decoded.greType, "GameStateMessage")
        assertEquals(decoded.msgId, 5)
        assertEquals(decoded.gsId, 10)
        assertEquals(decoded.gsmType, "Full")
        assertEquals(decoded.updateType, "SendAndRecord")
        assertNull(decoded.prevGsId, "Full GSM should have no prevGsId")

        // zones
        assertEquals(decoded.zones.size, 1)
        val zone = decoded.zones[0]
        assertEquals(zone.zoneId, 1)
        assertEquals(zone.type, "Hand")
        assertEquals(zone.owner, 1)
        assertEquals(zone.objectIds, listOf(100, 101))
        assertEquals(zone.viewers, listOf(1))

        // objects
        assertEquals(decoded.objects.size, 1)
        val obj = decoded.objects[0]
        assertEquals(obj.instanceId, 100)
        assertEquals(obj.grpId, 70000)
        assertEquals(obj.zoneId, 1)
        assertEquals(obj.type, "Card")
        assertTrue(obj.isTapped)
        assertEquals(obj.power, 3)
        assertEquals(obj.toughness, 4)
        assertEquals(obj.superTypes, listOf("Basic"))
        assertEquals(obj.cardTypes, listOf("Creature"))
        assertEquals(obj.subtypes, listOf("Goblin"))

        // annotations
        assertEquals(decoded.annotations.size, 1)
        assertEquals(decoded.annotations[0].id, 1)
        assertEquals(decoded.annotations[0].types, listOf("ZoneTransfer"))
        assertEquals(decoded.annotations[0].affectorId, 100)
        assertEquals(decoded.annotations[0].affectedIds, listOf(100))

        // actions (from GSM)
        assertEquals(decoded.actions.size, 1)
        assertEquals(decoded.actions[0].type, "Play")
        assertEquals(decoded.actions[0].instanceId, 100)
        assertEquals(decoded.actions[0].grpId, 70000)
        assertEquals(decoded.actions[0].seatId, 1)

        // players
        assertEquals(decoded.players.size, 2)
        assertEquals(decoded.players[0].seat, 1)
        assertEquals(decoded.players[0].life, 20)

        // turnInfo
        val turnInfo = checkNotNull(decoded.turnInfo)
        assertEquals(turnInfo.turn, 1)
        assertEquals(turnInfo.activePlayer, 1)
        assertEquals(turnInfo.phase, "Beginning")
        assertEquals(turnInfo.step, "Upkeep")

        // systemSeatIds
        assertEquals(decoded.systemSeatIds, listOf(1))

        // req flags
        assertFalse(decoded.hasActionsAvailableReq)
        assertFalse(decoded.hasMulliganReq)
        assertFalse(decoded.hasIntermissionReq)
    }

    // ── test: Diff with prevGsId ────────────────────────────────────

    @Test(description = "Diff GSM populates prevGsId and diffDeletedInstanceIds")
    fun diffWithPrevGsId() {
        val gsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(11)
            .setPrevGameStateId(10)
            .setUpdate(GameStateUpdate.SendHiFi)
            .addAllDiffDeletedInstanceIds(listOf(200, 201))
            .build()

        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(6)
            .setGameStateId(11)
            .setGameStateMessage(gsm)
            .build()

        val decoded = GREToDecoded.convert(gre, index = 1)

        assertEquals(decoded.gsmType, "Diff")
        assertEquals(decoded.prevGsId, 10)
        assertEquals(decoded.diffDeletedInstanceIds, listOf(200, 201))
    }

    // ── test: ActionsAvailableReq ───────────────────────────────────

    @Test(description = "ActionsAvailableReq actions are extracted")
    fun actionsAvailableReq() {
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.ActionsAvailableReq_695e)
            .setMsgId(7)
            .setActionsAvailableReq(
                ActionsAvailableReq.newBuilder()
                    .addActions(
                        Action.newBuilder()
                            .setActionType(ActionType.Play_add3)
                            .setInstanceId(300)
                            .setGrpId(80000),
                    )
                    .addActions(
                        Action.newBuilder()
                            .setActionType(ActionType.Activate_add3)
                            .setInstanceId(301)
                            .setGrpId(80001),
                    ),
            )
            .build()

        val decoded = GREToDecoded.convert(gre, index = 2)

        assertTrue(decoded.hasActionsAvailableReq)
        assertEquals(decoded.actions.size, 2)
        assertEquals(decoded.actions[0].type, "Play")
        assertEquals(decoded.actions[0].instanceId, 300)
        assertNull(decoded.actions[0].seatId, "ActionsAvailableReq actions have no seatId")
        assertEquals(decoded.actions[1].type, "Activate")
        assertEquals(decoded.actions[1].instanceId, 301)
    }

    // ── test: annotation details (string, int32, uint32) ────────────

    @Test(description = "Annotation details: string, int32, uint32 values")
    fun annotationDetailConversion() {
        val ann = annotationBuilder(
            id = 42,
            types = listOf(AnnotationType.ZoneTransfer_af5a),
        ).addDetails(
            KeyValuePairInfo.newBuilder()
                .setKey("category")
                .setType(KeyValuePairValueType.String)
                .addValueString("Draw"),
        ).addDetails(
            KeyValuePairInfo.newBuilder()
                .setKey("zone_src")
                .setType(KeyValuePairValueType.Int32)
                .addValueInt32(1),
        ).addDetails(
            KeyValuePairInfo.newBuilder()
                .setKey("zone_dst")
                .setType(KeyValuePairValueType.Uint32)
                .addValueUint32(2),
        ).build()

        val gsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(20)
            .addAnnotations(ann)
            .build()

        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm)
            .build()

        val decoded = GREToDecoded.convert(gre, index = 3)
        val details = decoded.annotations[0].details

        assertEquals(details["category"], "Draw")
        assertEquals(details["zone_src"], 1)
        assertEquals(details["zone_dst"], 2)
    }

    // ── test: custom source label ───────────────────────────────────

    @Test(description = "Custom source label populates file field")
    fun customSourceLabel() {
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .build()

        val decoded = GREToDecoded.convert(gre, index = 0, source = "engine-test")
        assertEquals(decoded.file, "engine-test")
    }
}
