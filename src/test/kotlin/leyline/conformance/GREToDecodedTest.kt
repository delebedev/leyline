package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.recording.GREToDecoded
import wotc.mtgo.gre.external.messaging.Messages.*

class GREToDecodedTest :
    FunSpec({

        tags(UnitTag)

        // ── helpers ──────────────────────────────────────────────────────

        fun zoneBuilder(
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

        fun objectBuilder(
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

        fun annotationBuilder(
            id: Int,
            types: List<AnnotationType>,
            affectorId: Int = 0,
            affectedIds: List<Int> = emptyList(),
        ): AnnotationInfo.Builder = AnnotationInfo.newBuilder()
            .setId(id)
            .addAllType(types)
            .setAffectorId(affectorId)
            .addAllAffectedIds(affectedIds)

        fun turnInfoBuilder(
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

        fun playerBuilder(seat: Int, life: Int, status: PlayerStatus): PlayerInfo.Builder =
            PlayerInfo.newBuilder()
                .setSystemSeatNumber(seat)
                .setLifeTotal(life)
                .setStatus(status)

        // ── test: Full GameStateMessage with all fields ─────────────────

        test("Full GSM: zones, objects, annotations, turnInfo, players, actions") {
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

            decoded.index shouldBe 0
            decoded.file shouldBe "live"
            decoded.greType shouldBe "GameStateMessage"
            decoded.msgId shouldBe 5
            decoded.gsId shouldBe 10
            decoded.gsmType shouldBe "Full"
            decoded.updateType shouldBe "SendAndRecord"
            decoded.prevGsId.shouldBeNull()

            // zones
            decoded.zones.size shouldBe 1
            val zone = decoded.zones[0]
            zone.zoneId shouldBe 1
            zone.type shouldBe "Hand"
            zone.owner shouldBe 1
            zone.objectIds shouldBe listOf(100, 101)
            zone.viewers shouldBe listOf(1)

            // objects
            decoded.objects.size shouldBe 1
            val obj = decoded.objects[0]
            obj.instanceId shouldBe 100
            obj.grpId shouldBe 70000
            obj.zoneId shouldBe 1
            obj.type shouldBe "Card"
            obj.isTapped.shouldBeTrue()
            obj.power shouldBe 3
            obj.toughness shouldBe 4
            obj.superTypes shouldBe listOf("Basic")
            obj.cardTypes shouldBe listOf("Creature")
            obj.subtypes shouldBe listOf("Goblin")

            // annotations
            decoded.annotations.size shouldBe 1
            decoded.annotations[0].id shouldBe 1
            decoded.annotations[0].types shouldBe listOf("ZoneTransfer")
            decoded.annotations[0].affectorId shouldBe 100
            decoded.annotations[0].affectedIds shouldBe listOf(100)

            // actions (from GSM)
            decoded.actions.size shouldBe 1
            decoded.actions[0].type shouldBe "Play"
            decoded.actions[0].instanceId shouldBe 100
            decoded.actions[0].grpId shouldBe 70000
            decoded.actions[0].seatId shouldBe 1

            // players
            decoded.players.size shouldBe 2
            decoded.players[0].seat shouldBe 1
            decoded.players[0].life shouldBe 20

            // turnInfo
            val turnInfo = checkNotNull(decoded.turnInfo)
            turnInfo.turn shouldBe 1
            turnInfo.activePlayer shouldBe 1
            turnInfo.phase shouldBe "Beginning"
            turnInfo.step shouldBe "Upkeep"

            // systemSeatIds
            decoded.systemSeatIds shouldBe listOf(1)

            // req flags
            decoded.hasActionsAvailableReq.shouldBeFalse()
            decoded.hasMulliganReq.shouldBeFalse()
            decoded.hasIntermissionReq.shouldBeFalse()
        }

        // ── test: Diff with prevGsId ────────────────────────────────────

        test("Diff GSM populates prevGsId and diffDeletedInstanceIds") {
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

            decoded.gsmType shouldBe "Diff"
            decoded.prevGsId shouldBe 10
            decoded.diffDeletedInstanceIds shouldBe listOf(200, 201)
        }

        // ── test: ActionsAvailableReq ───────────────────────────────────

        test("ActionsAvailableReq actions are extracted") {
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

            decoded.hasActionsAvailableReq.shouldBeTrue()
            decoded.actions.size shouldBe 2
            decoded.actions[0].type shouldBe "Play"
            decoded.actions[0].instanceId shouldBe 300
            decoded.actions[0].seatId.shouldBeNull()
            decoded.actions[1].type shouldBe "Activate"
            decoded.actions[1].instanceId shouldBe 301
        }

        // ── test: annotation details (string, int32, uint32) ────────────

        test("Annotation details: string, int32, uint32 values") {
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

            details["category"] shouldBe "Draw"
            details["zone_src"] shouldBe 1
            details["zone_dst"] shouldBe 2
        }

        // ── test: custom source label ───────────────────────────────────

        test("Custom source label populates file field") {
            val gre = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .build()

            val decoded = GREToDecoded.convert(gre, index = 0, source = "engine-test")
            decoded.file shouldBe "engine-test"
        }
    })
