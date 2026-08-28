package leyline.testkit

import wotc.mtgo.gre.external.messaging.Messages.*

typealias MatchFlowHarness = leyline.tooling.headless.MatchFlowHarness
typealias PlayerZone = leyline.tooling.headless.PlayerZone
typealias ClientAccumulator = leyline.tooling.headless.ClientAccumulator
typealias ValidatingMessageSink = leyline.tooling.headless.ValidatingMessageSink
typealias ScriptedPlayerController = leyline.tooling.headless.ScriptedPlayerController
typealias TestCardRegistry = leyline.tooling.headless.TestCardRegistry
typealias FixtureCardLoader = leyline.tooling.headless.FixtureCardLoader
typealias CardDataDeriver = leyline.tooling.headless.CardDataDeriver

@Suppress("FunctionName")
object ScriptedAction {
    fun PlayLand(name: String): leyline.tooling.headless.ScriptedAction =
        leyline.tooling.headless.ScriptedAction
            .PlayLand(name)

    fun CastSpell(name: String): leyline.tooling.headless.ScriptedAction =
        leyline.tooling.headless.ScriptedAction
            .CastSpell(name)

    val PassPriority: leyline.tooling.headless.ScriptedAction = leyline.tooling.headless.ScriptedAction.PassPriority
    val DeclareNoAttackers: leyline.tooling.headless.ScriptedAction = leyline.tooling.headless.ScriptedAction.DeclareNoAttackers
    val DeclareNoBlockers: leyline.tooling.headless.ScriptedAction = leyline.tooling.headless.ScriptedAction.DeclareNoBlockers

    fun Attack(names: List<String>): leyline.tooling.headless.ScriptedAction =
        leyline.tooling.headless.ScriptedAction
            .Attack(names)

    fun Block(assignments: Map<String, String>): leyline.tooling.headless.ScriptedAction =
        leyline.tooling.headless.ScriptedAction
            .Block(assignments)
}

inline fun clientMessage(
    type: ClientMessageType,
    block: ClientToGREMessage.Builder.() -> Unit = {},
): ClientToGREMessage = leyline.tooling.headless.clientMessage(type, block)

fun performAction(block: Action.Builder.() -> Unit): ClientToGREMessage = leyline.tooling.headless.performAction(block)

fun performAction(action: Action): ClientToGREMessage = leyline.tooling.headless.performAction(action)

fun declareAttackersResp(
    attackers: List<Int> = emptyList(),
    attackerAlternatives: Map<Int, Int> = emptyMap(),
    damageRecipients: Map<Int, DamageRecipient> = emptyMap(),
    autoDeclare: Boolean = false,
    autoDeclareTarget: Int? = null,
): ClientToGREMessage =
    leyline.tooling.headless.declareAttackersResp(
        attackers = attackers,
        attackerAlternatives = attackerAlternatives,
        damageRecipients = damageRecipients,
        autoDeclare = autoDeclare,
        autoDeclareTarget = autoDeclareTarget,
    )

fun planeswalkerDamageRecipient(planeswalkerInstanceId: Int): DamageRecipient =
    leyline.tooling.headless.planeswalkerDamageRecipient(planeswalkerInstanceId)

fun submitAttackersReq(seatId: Int = 1): ClientToGREMessage = leyline.tooling.headless.submitAttackersReq(seatId)

fun declareBlockersResp(assignments: Map<Int, Int> = emptyMap()): ClientToGREMessage =
    leyline.tooling.headless.declareBlockersResp(assignments)

fun declareBlockersRespDeselect(blockerInstanceId: Int): ClientToGREMessage =
    leyline.tooling.headless.declareBlockersRespDeselect(blockerInstanceId)

fun submitBlockersReq(seatId: Int = 1): ClientToGREMessage = leyline.tooling.headless.submitBlockersReq(seatId)

fun assignDamageResp(assigners: List<Pair<Int, List<Pair<Int, Int>>>>): ClientToGREMessage =
    leyline.tooling.headless.assignDamageResp(assigners)

fun selectTargetsResp(
    targets: List<Int>,
    targetIdx: Int = 1,
): ClientToGREMessage = leyline.tooling.headless.selectTargetsResp(targets, targetIdx)

fun cancelActionReq(): ClientToGREMessage = leyline.tooling.headless.cancelActionReq()

fun submitTargetsReq(): ClientToGREMessage = leyline.tooling.headless.submitTargetsReq()

fun selectNResp(ids: List<Int>): ClientToGREMessage = leyline.tooling.headless.selectNResp(ids)

fun orderResp(ids: List<Int>): ClientToGREMessage = leyline.tooling.headless.orderResp(ids)

fun distributionResp(amounts: List<Pair<Int, Int>>): ClientToGREMessage = leyline.tooling.headless.distributionResp(amounts)

fun searchResp(itemsFound: List<Int>): ClientToGREMessage = leyline.tooling.headless.searchResp(itemsFound)

fun groupedSearchResp(
    groupId: Int,
    ids: List<Int>,
    maxSelect: Int,
): ClientToGREMessage = leyline.tooling.headless.groupedSearchResp(groupId, ids, maxSelect)

fun groupedSearchFailResp(): ClientToGREMessage = leyline.tooling.headless.groupedSearchFailResp()

fun castingTimeOptionsResp(
    selectedGrpIds: List<Int>,
    ctoId: Int = 1,
): ClientToGREMessage = leyline.tooling.headless.castingTimeOptionsResp(selectedGrpIds, ctoId)

fun optionalCostResp(ctoId: Int): ClientToGREMessage = leyline.tooling.headless.optionalCostResp(ctoId)

fun effectCostResp(selectedInstanceIds: List<Int>): ClientToGREMessage = leyline.tooling.headless.effectCostResp(selectedInstanceIds)

fun gatherCountersResp(gatherings: List<Pair<Int, Int>>): ClientToGREMessage = leyline.tooling.headless.gatherCountersResp(gatherings)

fun optionalActionResp(accept: Boolean): ClientToGREMessage = leyline.tooling.headless.optionalActionResp(accept)

fun settingsMessage(block: SettingsMessage.Builder.() -> Unit): SettingsMessage = leyline.tooling.headless.settingsMessage(block)

fun stop(
    type: StopType,
    scope: SettingScope,
    status: SettingStatus,
): Stop = leyline.tooling.headless.stop(type, scope, status)

fun mana(
    color: ManaColor,
    count: Int,
): ManaRequirement = leyline.tooling.headless.mana(color, count)

fun greMessage(
    msgId: Int = 1,
    gsId: Int = 0,
    seatIds: List<Int> = listOf(1),
    gsm: GameStateMessage.Builder.() -> Unit = {},
): GREToClientMessage = leyline.tooling.headless.greMessage(msgId, gsId, seatIds, gsm)

fun greMessage(
    msgId: Int = 1,
    gsm: GameStateMessage,
    seatIds: List<Int> = listOf(1),
): GREToClientMessage = leyline.tooling.headless.greMessage(msgId, gsm, seatIds)

fun actionsMessage(
    msgId: Int = 1,
    gsId: Int = 0,
    seatIds: List<Int> = listOf(1),
    actions: ActionsAvailableReq.Builder.() -> Unit,
): GREToClientMessage = leyline.tooling.headless.actionsMessage(msgId, gsId, seatIds, actions)
