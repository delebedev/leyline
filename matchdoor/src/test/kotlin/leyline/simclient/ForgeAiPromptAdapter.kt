package leyline.simclient

import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext

internal data class ForgeAiPromptContext(
    val harness: MatchFlowHarness,
    val forgeAi: ForgeAiPolicy,
    val attempts: ActionAttemptLedger,
)

internal interface ForgeAiPromptAdapter {
    val promptType: GREMessageType
    val telemetryName: String

    fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = true

    fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse?
}

internal object ForgeAiAarAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.ActionsAvailableReq_695e
    override val telemetryName: String = "ActionsAvailableReq"

    override fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = prompt.aarActions().any { it.isActionableAarAction() }

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val choice = context.forgeAi.chooseAarAction(prompt.aarActions(), context.attempts.skipFingerprints()) ?: return null
        return SimPromptResponse(
            decision = SimDecision.PerformAction(choice.action),
            aarActionFingerprint = choice.action.actionFingerprint(),
        )
    }
}

internal object ForgeAiDeclareAttackersAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.DeclareAttackersReq_695e
    override val telemetryName: String = "DeclareAttackersReq"

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val attackers = context.forgeAi.chooseAttackers() ?: return null
        return SimPromptResponse(
            decision = SimDecision.DeclareAttackers(attackers),
            markHandled = false,
            markAllHandledOfType = prompt.type,
        )
    }
}

internal object ForgeAiDeclareBlockersAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.DeclareBlockersReq_695e
    override val telemetryName: String = "DeclareBlockersReq"

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val blockers = context.forgeAi.chooseBlockers() ?: return null
        return SimPromptResponse(SimDecision.DeclareBlockers(blockers))
    }
}

internal object ForgeAiSelectNAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.SelectNreq
    override val telemetryName: String = "SelectNReq"

    override fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = context.forgeAi.canChooseSelectN(prompt.msg.selectNReq)

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val selected = context.forgeAi.chooseSelectN(prompt.msg.selectNReq) ?: return null
        return SimPromptResponse(SimDecision.SelectN(selected))
    }
}

internal object ForgeAiSearchAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.SearchReq_695e
    override val telemetryName: String = "SearchReq"

    override fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = prompt.msg.searchReq.itemsSoughtCount > 0

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val selected = chooseBoardAwareSearchIds(prompt.msg, context.harness) ?: return null
        return SimPromptResponse(SimDecision.Search(selected))
    }
}

internal object ForgeAiGroupAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.GroupReq_695e
    override val telemetryName: String = "GroupReq"

    override fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = prompt.msg.groupReq.instanceIdsCount > 0

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val awayIds = chooseBoardAwareGroupAwayIds(prompt.msg, context.harness) ?: return null
        return SimPromptResponse(
            SimDecision.GroupAway(
                awayInstanceIds = awayIds,
                allInstanceIds = prompt.msg.groupReq.instanceIdsList.toList(),
                context = prompt.msg.groupReq.context,
            ),
        )
    }
}

internal fun chooseBoardAwareSearchIds(
    msg: GREToClientMessage,
    harness: MatchFlowHarness,
): List<Int>? {
    val req = msg.searchReq
    val max = if (req.maxFind > 0) req.maxFind else req.minFind
    val count = max.coerceAtLeast(req.minFind).coerceAtLeast(1)
    val soughtIds = req.itemsSoughtList
    if (soughtIds.isEmpty()) return null

    val candidates =
        soughtIds.mapNotNull { id ->
            harness.accumulator.objects[id]?.let { SearchCandidate(id, it) }
        }
    if (candidates.size != soughtIds.size) return null

    val chooserSeat = harness.accumulator.objects[req.sourceId]?.controllerSeatId.takeIf { it != 0 } ?: 1
    val battlefieldLands =
        harness.accumulator.objects.values.count {
            it.zoneId == ZoneIds.BATTLEFIELD &&
                it.controllerSeatId == chooserSeat &&
                CardType.Land_a80b in it.cardTypesList
        }
    val originalOrder = soughtIds.withIndex().associate { it.value to it.index }
    return candidates
        .sortedWith(
            compareByDescending<SearchCandidate> { searchPriority(it.objectInfo, battlefieldLands) }
                .thenBy { originalOrder[it.instanceId] ?: Int.MAX_VALUE },
        ).take(count)
        .map { it.instanceId }
}

private data class SearchCandidate(
    val instanceId: Int,
    val objectInfo: GameObjectInfo,
)

private fun searchPriority(
    objectInfo: GameObjectInfo,
    battlefieldLands: Int,
): Int =
    when {
        CardType.Land_a80b in objectInfo.cardTypesList -> if (battlefieldLands < SEARCH_LAND_FLOOR) 300 else 50
        CardType.Creature in objectInfo.cardTypesList -> 200 + objectInfo.creatureScore()
        else -> 100
    }

private fun GameObjectInfo.creatureScore(): Int =
    (if (hasPower()) power.value else 0) +
        (if (hasToughness()) toughness.value else 0)

internal fun chooseBoardAwareGroupAwayIds(
    msg: GREToClientMessage,
    harness: MatchFlowHarness,
): List<Int>? {
    val req = msg.groupReq
    if (req.context != GroupingContext.Scry_a0f6 && req.context != GroupingContext.Surveil) return null
    val ids = req.instanceIdsList
    if (ids.isEmpty()) return null

    val candidates =
        ids.map { id ->
            GroupCandidate(id, harness.accumulator.objects[id])
        }
    val chooserSeat = harness.accumulator.objects[req.sourceId]?.controllerSeatId.takeIf { it != 0 } ?: 1
    val battlefieldLands =
        harness.accumulator.objects.values.count {
            it.zoneId == ZoneIds.BATTLEFIELD &&
                it.controllerSeatId == chooserSeat &&
                CardType.Land_a80b in it.cardTypesList
        }
    return candidates
        .filter { candidate ->
            candidate.objectInfo?.let { groupKeepPriority(it, battlefieldLands) } == GROUP_AWAY_PRIORITY
        }.map { it.instanceId }
}

private data class GroupCandidate(
    val instanceId: Int,
    val objectInfo: GameObjectInfo?,
)

private fun groupKeepPriority(
    objectInfo: GameObjectInfo,
    battlefieldLands: Int,
): Int = searchPriority(objectInfo, battlefieldLands)

private const val SEARCH_LAND_FLOOR = 4
private const val GROUP_AWAY_PRIORITY = 50
