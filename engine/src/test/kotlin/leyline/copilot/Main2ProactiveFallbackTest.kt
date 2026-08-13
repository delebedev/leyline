package leyline.copilot

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.mapping.ZoneIds
import leyline.testkit.BoardTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AutoTapAction
import wotc.mtgo.gre.external.messaging.Messages.AutoTapSolution
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.PlayerInfo
import wotc.mtgo.gre.external.messaging.Messages.TurnInfo
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

private const val CONSULT_SEAT = 2
private const val SOURCE_IID = 333
private val MANA_SOURCE_IIDS = listOf(279, 284, 296)

class Main2ProactiveFallbackTest :
    BoardTest({
        test("Main2 fallback casts the payable Kiora action the normal AI declines") {
            withScenario("Kiora, the Rising Tide") { scenario ->
                val policy = ForgeAiPolicy({ scenario.bridge }, SeatId(CONSULT_SEAT))

                policy.chooseAarAction(scenario.actions).shouldBeNull()
                policy
                    .chooseMain2ProactivePermanent(scenario.actions)
                    .shouldNotBeNull()
                    .instanceId shouldBe SOURCE_IID

                val proposal = CopilotProposalService(scenario.bridge, SeatId(CONSULT_SEAT)).propose(scenario.prompt)
                assertSoftly {
                    proposal.intent shouldBe "cast"
                    proposal.card.shouldNotBeNull().instanceId shouldBe SOURCE_IID
                    proposal.responseIds shouldBe listOf(SOURCE_IID)
                }
            }
        }

        test("Main1 does not use the end-of-turn permanent fallback") {
            withScenario("Kiora, the Rising Tide", phase = Phase.Main1_a549) { scenario ->
                ForgeAiPolicy({ scenario.bridge }, SeatId(CONSULT_SEAT))
                    .chooseMain2ProactivePermanent(scenario.actions)
                    .shouldBeNull()
            }
        }

        test("instant casts do not use the Main2 permanent fallback") {
            withScenario("Counterspell") { scenario ->
                ForgeAiPolicy({ scenario.bridge }, SeatId(CONSULT_SEAT))
                    .chooseMain2ProactivePermanent(scenario.actions)
                    .shouldBeNull()
            }
        }

        test("Flash permanent casts do not use the Main2 permanent fallback") {
            withScenario("Resolute Reinforcements") { scenario ->
                ForgeAiPolicy({ scenario.bridge }, SeatId(CONSULT_SEAT))
                    .chooseMain2ProactivePermanent(scenario.actions)
                    .shouldBeNull()
            }
        }

        test("casts without a concrete non-zero payment do not use the Main2 fallback") {
            withScenario("Kiora, the Rising Tide", includeAutoTap = false) { scenario ->
                ForgeAiPolicy({ scenario.bridge }, SeatId(CONSULT_SEAT))
                    .chooseMain2ProactivePermanent(scenario.actions)
                    .shouldBeNull()
            }
            withScenario("Kiora, the Rising Tide", manaValue = 0) { scenario ->
                ForgeAiPolicy({ scenario.bridge }, SeatId(CONSULT_SEAT))
                    .chooseMain2ProactivePermanent(scenario.actions)
                    .shouldBeNull()
            }
        }

        test("land plays remain outside the Main2 cast fallback") {
            withScenario("Island") { scenario ->
                val playActions =
                    scenario.actions.map { action ->
                        if (action.actionType == ActionType.Cast) {
                            action.toBuilder().setActionType(ActionType.Play_add3).build()
                        } else {
                            action
                        }
                    }
                ForgeAiPolicy({ scenario.bridge }, SeatId(CONSULT_SEAT))
                    .chooseMain2ProactivePermanent(playActions)
                    .shouldBeNull()
            }
        }

        test("opponent priority does not use the Main2 fallback") {
            withScenario("Kiora, the Rising Tide") { scenario ->
                val opponent = scenario.bridge.getPlayer(SeatId(1)).shouldNotBeNull()
                scenario.bridge
                    .getGame()
                    .shouldNotBeNull()
                    .phaseHandler
                    .setPriority(opponent)

                ForgeAiPolicy({ scenario.bridge }, SeatId(CONSULT_SEAT))
                    .chooseMain2ProactivePermanent(scenario.actions)
                    .shouldBeNull()
            }
        }

        test("Cloudkin Seer remains a normal Forge AI cast") {
            withScenario("Cloudkin Seer") { scenario ->
                val choice =
                    ForgeAiPolicy({ scenario.bridge }, SeatId(CONSULT_SEAT))
                        .chooseAarAction(scenario.actions)
                        .shouldNotBeNull()

                assertSoftly {
                    choice.instanceId shouldBe SOURCE_IID
                    choice.actionType shouldBe ActionType.Cast
                }
            }
        }
    })

private data class FallbackScenario(
    val bridge: leyline.game.state.GameBridge,
    val actions: List<Action>,
    val prompt: GREToClientMessage,
)

private inline fun withScenario(
    cardName: String,
    phase: Phase = Phase.Main2_a549,
    includeAutoTap: Boolean = true,
    manaValue: Int = 3,
    block: (FallbackScenario) -> Unit,
) {
    val scenario = fallbackScenario(cardName, phase, includeAutoTap, manaValue)
    try {
        block(scenario)
    } finally {
        scenario.bridge.teardownResources()
    }
}

private fun fallbackScenario(
    cardName: String,
    phase: Phase,
    includeAutoTap: Boolean,
    manaValue: Int,
): FallbackScenario {
    val cardGrpId = TestCardRegistry.ensureCardRegistered(cardName)
    val islandGrpId = TestCardRegistry.ensureCardRegistered("Island")
    val handZone =
        ZoneInfo
            .newBuilder()
            .setZoneId(ZoneIds.P2_HAND)
            .setType(ZoneType.Hand)
            .setVisibility(Visibility.Private)
            .setOwnerSeatId(CONSULT_SEAT)
            .addViewers(CONSULT_SEAT)
            .addObjectInstanceIds(SOURCE_IID)
    val battlefieldZone =
        ZoneInfo
            .newBuilder()
            .setZoneId(ZoneIds.BATTLEFIELD)
            .setType(ZoneType.Battlefield)
            .setVisibility(Visibility.Public)
            .addAllObjectInstanceIds(MANA_SOURCE_IIDS)
    val gsm =
        GameStateMessage
            .newBuilder()
            .setGameStateId(201)
            .setTurnInfo(
                TurnInfo
                    .newBuilder()
                    .setPhase(phase)
                    .setTurnNumber(9)
                    .setActivePlayer(CONSULT_SEAT)
                    .setPriorityPlayer(CONSULT_SEAT)
                    .setDecisionPlayer(CONSULT_SEAT),
            ).addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(1).setLifeTotal(20))
            .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(CONSULT_SEAT).setLifeTotal(20))
            .addZones(handZone)
            .addZones(battlefieldZone)
            .addGameObjects(cardObject(SOURCE_IID, cardGrpId, ZoneIds.P2_HAND))
            .apply {
                MANA_SOURCE_IIDS.forEach { iid ->
                    addGameObjects(cardObject(iid, islandGrpId, ZoneIds.BATTLEFIELD))
                }
            }.build()
    val cast =
        Action
            .newBuilder()
            .setActionType(ActionType.Cast)
            .setGrpId(cardGrpId)
            .setInstanceId(SOURCE_IID)
            .setFacetId(SOURCE_IID)
            .apply {
                if (manaValue > 1) {
                    addManaCost(
                        ManaRequirement
                            .newBuilder()
                            .addColor(ManaColor.Generic)
                            .setCount(manaValue - 1),
                    )
                }
                if (manaValue > 0) {
                    addManaCost(
                        ManaRequirement
                            .newBuilder()
                            .addColor(ManaColor.Blue_afc9)
                            .setCount(1),
                    )
                }
                if (includeAutoTap) {
                    setAutoTapSolution(
                        AutoTapSolution
                            .newBuilder()
                            .addAllAutoTapActions(
                                MANA_SOURCE_IIDS.map { iid -> AutoTapAction.newBuilder().setInstanceId(iid).build() },
                            ),
                    )
                }
            }.build()
    val actions = listOf(cast, Action.newBuilder().setActionType(ActionType.Pass).build())
    val prompt =
        GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.ActionsAvailableReq_695e)
            .setMsgId(151)
            .setGameStateId(201)
            .addSystemSeatIds(CONSULT_SEAT)
            .setActionsAvailableReq(ActionsAvailableReq.newBuilder().addAllActions(actions))
            .build()
    val bridge = SnapshotHydration.hydrate(gsm, CONSULT_SEAT, TestCardRegistry.repo)
    return FallbackScenario(bridge, actions, prompt)
}

private fun cardObject(
    instanceId: Int,
    grpId: Int,
    zoneId: Int,
): GameObjectInfo =
    GameObjectInfo
        .newBuilder()
        .setInstanceId(instanceId)
        .setGrpId(grpId)
        .setType(GameObjectType.Card)
        .setZoneId(zoneId)
        .setVisibility(if (zoneId == ZoneIds.P2_HAND) Visibility.Private else Visibility.Public)
        .setOwnerSeatId(CONSULT_SEAT)
        .setControllerSeatId(CONSULT_SEAT)
        .apply {
            if (zoneId == ZoneIds.P2_HAND) addViewers(CONSULT_SEAT)
        }.build()
