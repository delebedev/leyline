package leyline.simclient

import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.HighlightType
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.StaticList

internal interface SimPromptPolicy {
    fun respondToPrompt(
        prompt: ActivePrompt,
        attempts: ActionAttemptLedger,
    ): SimPromptResponse
}

internal data class SimPromptPolicyTelemetry(
    val consulted: Map<String, Int>,
    val chose: Map<String, Int>,
    val totalMs: Map<String, Long>,
    val maxMs: Map<String, Long>,
) {
    val consultedTotal: Int get() = consulted.values.sum()
    val choseTotal: Int get() = chose.values.sum()
    val totalAiMs: Long get() = totalMs.values.sum()

    companion object {
        val Empty = SimPromptPolicyTelemetry(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}

internal open class GreedyPromptPolicy(
    protected val harness: MatchFlowHarness,
) : SimPromptPolicy {
    override fun respondToPrompt(
        prompt: ActivePrompt,
        attempts: ActionAttemptLedger,
    ): SimPromptResponse =
        @Suppress("ElseCaseInsteadOfExhaustiveWhen")
        when (prompt.type) {
            GREMessageType.DeclareAttackersReq_695e ->
                respondDeclareAttackers(prompt)
            GREMessageType.DeclareBlockersReq_695e ->
                respondDeclareBlockers()
            GREMessageType.ActionsAvailableReq_695e ->
                respondAar(prompt, attempts)
            GREMessageType.SelectTargetsReq_695e ->
                SimPromptResponse(respondSelectTargets(prompt.msg))
            GREMessageType.SelectNreq ->
                SimPromptResponse(respondSelectN(prompt.msg))
            GREMessageType.OrderReq_695e ->
                SimPromptResponse(respondOrder(prompt.msg))
            GREMessageType.SearchReq_695e ->
                SimPromptResponse(respondSearch(prompt.msg))
            GREMessageType.PayCostsReq_695e ->
                SimPromptResponse(respondPayCosts(prompt.msg))
            GREMessageType.GroupReq_695e ->
                SimPromptResponse(respondGroup(prompt.msg))
            GREMessageType.CastingTimeOptionsReq_695e ->
                SimPromptResponse(respondCastingTimeOptions(prompt.msg))
            GREMessageType.NumericInputReq_695e ->
                SimPromptResponse(respondNumericInput(prompt.msg))
            GREMessageType.AssignDamageReq_695e ->
                SimPromptResponse(respondAssignDamage(prompt.msg))
            GREMessageType.IntermissionReq_695e ->
                SimPromptResponse(SimDecision.Terminal)
            else ->
                SimPromptResponse(SimDecision.PassPriority)
        }

    protected open fun advisedAarAction(
        prompt: ActivePrompt,
        attempts: ActionAttemptLedger,
    ): Action? = null

    protected open fun advisedBlockers(): Map<Int, Int>? = null

    protected open fun advisedAttackers(): List<Int>? = null

    protected fun hasActionableAar(prompt: ActivePrompt): Boolean = prompt.aarActions().any { it.isActionableAarAction() }

    private fun respondDeclareAttackers(prompt: ActivePrompt): SimPromptResponse {
        val attackers = advisedAttackers()
        return SimPromptResponse(
            decision =
                if (attackers == null) {
                    SimDecision.DeclareAllAttackers
                } else {
                    SimDecision.DeclareAttackers(attackers)
                },
            markHandled = false,
            markAllHandledOfType = prompt.type,
        )
    }

    private fun respondDeclareBlockers(): SimPromptResponse {
        val assignments = advisedBlockers()
        return SimPromptResponse(
            if (assignments == null) {
                SimDecision.DeclareNoBlockers
            } else {
                SimDecision.DeclareBlockers(assignments)
            },
        )
    }

    private fun respondAar(
        prompt: ActivePrompt,
        attempts: ActionAttemptLedger,
    ): SimPromptResponse {
        val advised =
            if (hasActionableAar(prompt)) {
                advisedAarAction(prompt, attempts)
            } else {
                null
            }
        if (advised != null) return advised.toAarResponse()

        firstPlayableLand(prompt, attempts)?.let { return it.toAarResponse() }
        firstCastableSpell(prompt, attempts)?.let { return it.toAarResponse() }
        firstPass(prompt)?.let { return it.toAarResponse() }
        return SimPromptResponse(SimDecision.RetirePrompt, markHandled = false)
    }

    private fun firstPlayableLand(
        prompt: ActivePrompt,
        attempts: ActionAttemptLedger,
    ): Action? = prompt.aarActions().firstUnskipped(attempts) { it.actionType == ActionType.Play_add3 }

    private fun firstCastableSpell(
        prompt: ActivePrompt,
        attempts: ActionAttemptLedger,
    ): Action? {
        val casts = prompt.aarActions().filter { it.actionType == ActionType.Cast }
        val ordered = casts.sortedBy { if (it.alternativeGrpId != 0) 0 else 1 }
        val action = ordered.firstOrNull { it.actionFingerprint() !in attempts.skipFingerprints() }
        if (action == null && casts.isNotEmpty()) attempts.noteSkippedAlreadyTried()
        return action
    }

    private fun firstPass(prompt: ActivePrompt): Action? = prompt.aarActions().firstOrNull { it.actionType == ActionType.Pass }

    private fun List<Action>.firstUnskipped(
        attempts: ActionAttemptLedger,
        predicate: (Action) -> Boolean,
    ): Action? {
        val candidates = filter(predicate)
        val action = candidates.firstOrNull { it.actionFingerprint() !in attempts.skipFingerprints() }
        if (action == null && candidates.isNotEmpty()) attempts.noteSkippedAlreadyTried()
        return action
    }

    private fun Action.toAarResponse(): SimPromptResponse =
        SimPromptResponse(
            decision = SimDecision.PerformAction(this),
            aarActionFingerprint = if (actionType == ActionType.Pass) null else actionFingerprint(),
        )

    private fun respondSelectTargets(msg: GREToClientMessage): SimDecision {
        val ids =
            msg.selectTargetsReq.targetsList
                .flatMap { sel ->
                    val count = sel.minTargets.coerceAtLeast(0)
                    sel.targetsList
                        .filter { it.legalAction == wotc.mtgo.gre.external.messaging.Messages.SelectAction.Select_a1ad }
                        .sortedByDescending { it.highlight.targetPreference() }
                        .take(count)
                        .map { it.targetInstanceId }
                }.filter { it != 0 }
                .distinct()
        return if (ids.isEmpty()) SimDecision.CancelAction else SimDecision.SelectTargets(ids)
    }

    private fun respondSelectN(msg: GREToClientMessage): SimDecision {
        val req = msg.selectNReq
        if (msg.prompt.promptId == PromptIds.LEARN_LESSON_OR_DISCARD || msg.prompt.promptId == PromptIds.LEARN_LESSON_ONLY) {
            return SimDecision.SelectN(learnLessonIds(req).take(1))
        }
        val min = req.minSel.coerceAtLeast(0)
        val max = if (req.maxSel > 0) req.maxSel else min
        val count = min.coerceAtMost(max)
        val ids = if (req.staticList == StaticList.Colors && req.idsList.isEmpty()) listOf(1) else req.idsList
        return SimDecision.SelectN(ids.take(count))
    }

    private fun respondOrder(msg: GREToClientMessage): SimDecision = SimDecision.Order(msg.orderReq.idsList.toList())

    private fun respondSearch(msg: GREToClientMessage): SimDecision {
        val req = msg.searchReq
        val max = if (req.maxFind > 0) req.maxFind else req.minFind
        val count = max.coerceAtLeast(req.minFind).coerceAtLeast(1)
        return SimDecision.Search(req.itemsSoughtList.take(count))
    }

    private fun learnLessonIds(req: SelectNReq): List<Int> {
        val sideboardIds = req.idsList.filter { id -> harness.accumulator.objects[id]?.zoneId == ZoneIds.P1_SIDEBOARD }
        return sideboardIds.ifEmpty { req.idsList }
    }

    private fun respondPayCosts(msg: GREToClientMessage): SimDecision {
        val selection = msg.payCostsReq.effectCostReq.costSelection
        val min = selection.minSel.coerceAtLeast(0)
        val max = if (selection.maxSel > 0) selection.maxSel else min
        val count = min.coerceAtMost(max)
        if (selection.minWeight > 0 && selection.weightsCount == selection.idsCount) {
            val picked = mutableListOf<Int>()
            var total = 0
            for ((idx, id) in selection.idsList.withIndex()) {
                if (picked.size >= max) break
                picked.add(id)
                total += selection.getWeights(idx)
                if (total >= selection.minWeight) return SimDecision.EffectCost(picked)
            }
            return SimDecision.EffectCost(picked)
        }
        return SimDecision.EffectCost(selection.idsList.take(count))
    }

    private fun respondCastingTimeOptions(msg: GREToClientMessage): SimDecision {
        chooseSimClientModalGrpIds(msg)?.let { return SimDecision.ModalChoice(it) }
        val acceptOptionalCosts = System.getenv("SIMCLIENT_ACCEPT_OPTIONAL_COSTS").equals("true", ignoreCase = true)
        return SimDecision.OptionalCost(chooseSimClientCastingTimeOptionId(msg, acceptOptionalCosts))
    }

    private fun respondNumericInput(msg: GREToClientMessage): SimDecision {
        val req = msg.numericInputReq
        val choice =
            if (req == null) {
                0
            } else {
                req.minValue.coerceAtLeast(NUMERIC_INPUT_DEFAULT_MAX.coerceAtMost(req.maxValue))
            }
        return SimDecision.NumericInput(choice)
    }

    private fun respondGroup(msg: GREToClientMessage): SimDecision = SimDecision.GroupTop(msg.groupReq.instanceIdsList.toList())

    private fun respondAssignDamage(msg: GREToClientMessage): SimDecision =
        SimDecision.AssignDamage(
            msg.assignDamageReq.damageAssignersList.map { assigner ->
                assigner.instanceId to assigner.assignmentsList.map { it.instanceId to it.assignedDamage }
            },
        )

    private companion object {
        const val NUMERIC_INPUT_DEFAULT_MAX = 3
    }
}

internal class ForgeAiPromptPolicy(
    harness: MatchFlowHarness,
    private val forgeAi: ForgeAiPolicy,
) : GreedyPromptPolicy(harness) {
    private val aiConsultedByPrompt = mutableMapOf<String, Int>()
    private val aiChoseByPrompt = mutableMapOf<String, Int>()
    private val aiTotalMsByPrompt = mutableMapOf<String, Long>()
    private val aiMaxMsByPrompt = mutableMapOf<String, Long>()
    private val adapters: Map<GREMessageType, ForgeAiPromptAdapter> =
        listOf(
            ForgeAiAarAdapter,
            ForgeAiDeclareAttackersAdapter,
            ForgeAiDeclareBlockersAdapter,
            ForgeAiSelectNAdapter,
        ).associateBy { it.promptType }

    fun telemetry(): SimPromptPolicyTelemetry =
        SimPromptPolicyTelemetry(
            consulted = aiConsultedByPrompt.toMap(),
            chose = aiChoseByPrompt.toMap(),
            totalMs = aiTotalMsByPrompt.toMap(),
            maxMs = aiMaxMsByPrompt.toMap(),
        )

    override fun respondToPrompt(
        prompt: ActivePrompt,
        attempts: ActionAttemptLedger,
    ): SimPromptResponse {
        val adapter = adapters[prompt.type]
        val context = ForgeAiPromptContext(harness, forgeAi, attempts)
        if (adapter != null && adapter.shouldConsult(prompt, context)) {
            bumpConsulted(adapter.telemetryName)
            val response = timed(adapter.telemetryName) { adapter.decide(prompt, context) }
            if (response != null) {
                bumpChose(adapter.telemetryName)
                return response
            }
        }
        return super.respondToPrompt(prompt, attempts)
    }

    private inline fun <T> timed(
        prompt: String,
        block: () -> T,
    ): T {
        val t0 = System.nanoTime()
        return try {
            block()
        } finally {
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            aiTotalMsByPrompt.merge(prompt, elapsedMs) { a, b -> a + b }
            aiMaxMsByPrompt.merge(prompt, elapsedMs) { a, b -> maxOf(a, b) }
        }
    }

    private fun bumpConsulted(prompt: String) {
        aiConsultedByPrompt.merge(prompt, 1) { a, b -> a + b }
    }

    private fun bumpChose(prompt: String) {
        aiChoseByPrompt.merge(prompt, 1) { a, b -> a + b }
    }
}

private fun HighlightType.targetPreference(): Int =
    when (this) {
        HighlightType.Hot -> 3
        HighlightType.Tepid -> 2
        HighlightType.Cold -> 1
        HighlightType.None_ad60,
        HighlightType.Counterspell,
        HighlightType.Random,
        HighlightType.CopySpell,
        HighlightType.ReplaceRole,
        HighlightType.UNRECOGNIZED,
        -> 0
    }

internal fun chooseSimClientModalGrpIds(msg: GREToClientMessage?): List<Int>? {
    val option =
        msg
            ?.castingTimeOptionsReq
            ?.castingTimeOptionReqList
            ?.firstOrNull { it.castingTimeOptionType == CastingTimeOptionType.Modal_a7b4 && it.hasModalReq() }
            ?: return null
    val req = option.modalReq
    val min = req.minSel.coerceAtLeast(0)
    val max = if (req.maxSel > 0) req.maxSel else min
    val count = min.coerceAtMost(max)
    return req.modalOptionsList.map { it.grpId }.take(count)
}

internal fun ActivePrompt.aarActions(): List<Action> = (payload as? PromptPayload.ActionsAvailable)?.req?.actionsList.orEmpty()

internal fun Action.isActionableAarAction(): Boolean =
    actionType == ActionType.Cast || actionType == ActionType.Play_add3 || actionType == ActionType.Activate_add3
