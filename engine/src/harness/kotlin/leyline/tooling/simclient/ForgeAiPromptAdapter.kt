package leyline.tooling.simclient

import leyline.copilot.CopilotProposal
import leyline.copilot.SimDecision
import leyline.game.mapping.ZoneIds
import leyline.tooling.headless.HeadlessMatch
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext

internal data class ForgeAiPromptContext(
    val harness: HeadlessMatch,
    val attempts: ActionAttemptLedger,
)

private fun ForgeAiPromptContext.proposal(prompt: ActivePrompt): CopilotProposal = harness.advise(prompt.msg).proposal

private fun CopilotProposal.toDecision(prompt: ActivePrompt): SimDecision? =
    when (intent) {
        "play_land", "cast", "activate", "pass" -> {
            val action =
                prompt.aarActions().firstOrNull { action ->
                    when (intent) {
                        "play_land" -> action.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Play_add3
                        "cast" -> action.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Cast
                        "activate" -> action.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Activate_add3
                        else -> action.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Pass
                    } &&
                        (
                            responseIds.isEmpty() ||
                                action.instanceId in responseIds ||
                                action.grpId in responseIds ||
                                action.grpId == card?.grpId
                        )
                } ?: return null
            SimDecision.PerformAction(action)
        }
        "target" -> SimDecision.SelectTargets(responseIds)
        "select_n" -> SimDecision.SelectN(responseIds)
        "search" -> SimDecision.Search(responseIds)
        "pay_cost" -> SimDecision.EffectCost(responseIds)
        "group" -> SimDecision.GroupAway(responseIds, prompt.msg.groupReq.instanceIdsList, prompt.msg.groupReq.context)
        "attack", "attack_all" -> SimDecision.DeclareAttackers(responseIds)
        "block" -> SimDecision.DeclareBlockers(blocks.associate { it.blocker.instanceId to it.attacker.instanceId })
        "modal" -> ctoId?.let { SimDecision.ModalChoice(it, modalGrpIds) }
        "mana_type" ->
            SimDecision.ManaTypeChoices(
                manaTypes.map {
                    it.ctoId to
                        wotc.mtgo.gre.external.messaging.Messages.ManaColor
                            .valueOf(it.color)
                },
            )
        "optional_cost" -> ctoId?.let(SimDecision::OptionalCost)
        "optional_action" -> accept?.let(SimDecision::OptionalAction)
        "numeric" -> numericValue?.let(SimDecision::NumericInput)
        else -> null
    }

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
        val choice = context.proposal(prompt).toDecision(prompt) as? SimDecision.PerformAction ?: return null
        return SimPromptResponse(
            decision = choice,
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
        val attackers = context.proposal(prompt).toDecision(prompt) as? SimDecision.DeclareAttackers ?: return null
        return SimPromptResponse(
            decision = attackers,
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
        val blockers = context.proposal(prompt).toDecision(prompt) as? SimDecision.DeclareBlockers ?: return null
        return SimPromptResponse(blockers)
    }
}

internal object ForgeAiSelectNAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.SelectNreq
    override val telemetryName: String = "SelectNReq"

    override fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = context.proposal(prompt).intent == "select_n"

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val selected = context.proposal(prompt).toDecision(prompt) as? SimDecision.SelectN ?: return null
        return SimPromptResponse(selected)
    }
}

internal object ForgeAiSelectTargetsAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.SelectTargetsReq_695e
    override val telemetryName: String = "SelectTargetsReq"

    override fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = context.proposal(prompt).intent == "target"

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val selected = context.proposal(prompt).toDecision(prompt) as? SimDecision.SelectTargets ?: return null
        return SimPromptResponse(selected)
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
        val selected = context.proposal(prompt).toDecision(prompt) as? SimDecision.Search ?: return null
        return SimPromptResponse(selected)
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
        val decision = context.proposal(prompt).toDecision(prompt) as? SimDecision.GroupAway ?: return null
        return SimPromptResponse(decision)
    }
}

internal object ForgeAiPayCostsAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.PayCostsReq_695e
    override val telemetryName: String = "PayCostsReq"

    override fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = context.proposal(prompt).intent == "pay_cost"

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val selected = context.proposal(prompt).toDecision(prompt) as? SimDecision.EffectCost ?: return null
        return SimPromptResponse(selected)
    }
}

internal object ForgeAiCastingTimeOptionsAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.CastingTimeOptionsReq_695e
    override val telemetryName: String = "CastingTimeOptionsReq"

    override fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = context.proposal(prompt).intent in setOf("modal", "optional_cost", "mana_type")

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val decision = context.proposal(prompt).toDecision(prompt) ?: return null
        return SimPromptResponse(decision)
    }
}

internal fun chooseBoardAwareSearchIds(
    msg: GREToClientMessage,
    harness: HeadlessMatch,
): List<Int>? {
    val req = msg.searchReq
    val max = if (req.maxFind > 0) req.maxFind else req.minFind
    val count = max.coerceAtLeast(req.minFind).coerceAtLeast(1)
    val soughtIds = req.itemsSoughtList
    if (soughtIds.isEmpty()) return null

    val candidates =
        soughtIds.mapNotNull { id ->
            harness
                .observe()
                .client.objects[id]
                ?.let { SearchCandidate(id, it) }
        }
    if (candidates.size != soughtIds.size) return null

    val chooserSeat =
        harness
            .observe()
            .client.objects[req.sourceId]
            ?.controllerSeatId
            .takeIf { it != 0 } ?: 1
    val battlefieldLands =
        harness.observe().client.objects.values.count {
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
    harness: HeadlessMatch,
): List<Int>? {
    val req = msg.groupReq
    if (req.context != GroupingContext.Scry_a0f6 && req.context != GroupingContext.Surveil) return null
    val ids = req.instanceIdsList
    if (ids.isEmpty()) return null

    val candidates =
        ids.map { id ->
            GroupCandidate(id, harness.observe().client.objects[id])
        }
    val chooserSeat =
        harness
            .observe()
            .client.objects[req.sourceId]
            ?.controllerSeatId
            .takeIf { it != 0 } ?: 1
    val battlefieldLands =
        harness.observe().client.objects.values.count {
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
