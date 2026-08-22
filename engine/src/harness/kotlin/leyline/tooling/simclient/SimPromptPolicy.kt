package leyline.tooling.simclient

import leyline.copilot.DefaultDecisions
import leyline.copilot.SimDecision
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.tooling.headless.HeadlessMatch
import leyline.tooling.headless.cardNameByGrpId
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
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
    val targetChoices: Map<String, Int>,
    val targetChoiceSamples: Map<String, String>,
) {
    val consultedTotal: Int get() = consulted.values.sum()
    val choseTotal: Int get() = chose.values.sum()
    val totalAiMs: Long get() = totalMs.values.sum()

    companion object {
        val Empty =
            SimPromptPolicyTelemetry(
                consulted = emptyMap(),
                chose = emptyMap(),
                totalMs = emptyMap(),
                maxMs = emptyMap(),
                targetChoices = emptyMap(),
                targetChoiceSamples = emptyMap(),
            )
    }
}

internal open class GreedyPromptPolicy(
    protected val harness: HeadlessMatch,
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
            GREMessageType.OptionalActionMessage_695e ->
                SimPromptResponse(respondOptionalAction())
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
        val skipFingerprints = attempts.skipFingerprints()
        val action = ordered.firstOrNull { !it.isSkippedBy(skipFingerprints) }
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
        val selections = msg.selectTargetsReq.targetsList
        val ids =
            selections
                .flatMap { sel ->
                    val count = sel.minTargets.coerceAtLeast(0)
                    sel.targetsList
                        .filter { it.legalAction == wotc.mtgo.gre.external.messaging.Messages.SelectAction.Select_a1ad }
                        .sortedByDescending { it.highlight.targetPreference() }
                        .take(count)
                        .map { it.targetInstanceId }
                }.filter { it != 0 }
                .distinct()
        if (ids.isNotEmpty()) return SimDecision.SelectTargets(ids)
        // Nothing left to select. A re-offer of an already-chosen target carries
        // only Unselect, and the selection is complete — submit it. Cancelling
        // there unwinds the cast, which the policy then proposes again, so the
        // pair loops until the driver calls the game stalled.
        val satisfied = selections.isNotEmpty() && selections.all { it.selectedTargets >= it.minTargets }
        return if (satisfied) SimDecision.SubmitTargets else SimDecision.CancelAction
    }

    private fun respondSelectN(msg: GREToClientMessage): SimDecision {
        val req = msg.selectNReq
        if (msg.prompt.promptId == PromptIds.LEARN_LESSON_OR_DISCARD || msg.prompt.promptId == PromptIds.LEARN_LESSON_ONLY) {
            return SimDecision.SelectN(learnLessonIds(req).take(1))
        }
        if (msg.prompt.promptId == PromptIds.SUSPECT_ONE_OF_THOSE_CREATURES) {
            return SimDecision.SelectN(req.idsList.take(req.maxSel.coerceAtLeast(1)))
        }
        if (isRevealChoice(req)) {
            return SimDecision.SelectN(req.idsList.take(req.maxSel.coerceAtLeast(1)))
        }
        val min = req.minSel.coerceAtLeast(0)
        val max = if (req.maxSel > 0) req.maxSel else min
        val count = min.coerceAtMost(max)
        val ids = if (req.staticList == StaticList.Colors && req.idsList.isEmpty()) listOf(1) else req.idsList
        return SimDecision.SelectN(ids.take(count))
    }

    private fun respondOrder(msg: GREToClientMessage): SimDecision = DefaultDecisions.order(msg)

    private fun respondSearch(msg: GREToClientMessage): SimDecision = DefaultDecisions.search(msg)

    private fun learnLessonIds(req: SelectNReq): List<Int> {
        val sideboardIds =
            req.idsList.filter { id ->
                harness
                    .observe()
                    .client.objects[id]
                    ?.zoneId == ZoneIds.P1_SIDEBOARD
            }
        return sideboardIds.ifEmpty { req.idsList }
    }

    private fun isRevealChoice(req: SelectNReq): Boolean =
        req.prompt.promptId == PromptIds.SELECT_N &&
            req.context == wotc.mtgo.gre.external.messaging.Messages.SelectionContext.Resolution_a163 &&
            req.listType == wotc.mtgo.gre.external.messaging.Messages.SelectionListType.Dynamic &&
            req.minSel == 0 &&
            req.maxSel > 0 &&
            req.unfilteredIdsCount > req.idsCount

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
        val acceptOptionalCosts = System.getenv("SIMCLIENT_ACCEPT_OPTIONAL_COSTS").equals("true", ignoreCase = true)
        return DefaultDecisions.castingTimeOptions(msg, acceptOptionalCosts)
    }

    /** Greedy: accept the offer. Declining strands chained prompts the offer gates. */
    private fun respondOptionalAction(): SimDecision = DefaultDecisions.optionalAction()

    private fun respondNumericInput(msg: GREToClientMessage): SimDecision = DefaultDecisions.numericInput(msg)

    private fun respondGroup(msg: GREToClientMessage): SimDecision = DefaultDecisions.group(msg)

    private fun respondAssignDamage(msg: GREToClientMessage): SimDecision = DefaultDecisions.assignDamage(msg)

    private companion object {
        const val NUMERIC_INPUT_DEFAULT_MAX = 3
    }
}

internal class ForgeAiPromptPolicy(
    harness: HeadlessMatch,
) : GreedyPromptPolicy(harness) {
    private val aiConsultedByPrompt = mutableMapOf<String, Int>()
    private val aiChoseByPrompt = mutableMapOf<String, Int>()
    private val aiTotalMsByPrompt = mutableMapOf<String, Long>()
    private val aiMaxMsByPrompt = mutableMapOf<String, Long>()
    private val targetChoiceCounts = mutableMapOf<String, Int>()
    private val targetChoiceSamples = mutableMapOf<String, String>()
    private val adapters: Map<GREMessageType, ForgeAiPromptAdapter> =
        listOf(
            ForgeAiAarAdapter,
            ForgeAiDeclareAttackersAdapter,
            ForgeAiDeclareBlockersAdapter,
            ForgeAiSelectNAdapter,
            ForgeAiSelectTargetsAdapter,
            ForgeAiSearchAdapter,
            ForgeAiGroupAdapter,
            ForgeAiCastingTimeOptionsAdapter,
            ForgeAiPayCostsAdapter,
        ).associateBy { it.promptType }

    fun telemetry(): SimPromptPolicyTelemetry =
        SimPromptPolicyTelemetry(
            consulted = aiConsultedByPrompt.toMap(),
            chose = aiChoseByPrompt.toMap(),
            totalMs = aiTotalMsByPrompt.toMap(),
            maxMs = aiMaxMsByPrompt.toMap(),
            targetChoices = targetChoiceCounts.toMap(),
            targetChoiceSamples = targetChoiceSamples.toMap(),
        )

    override fun respondToPrompt(
        prompt: ActivePrompt,
        attempts: ActionAttemptLedger,
    ): SimPromptResponse {
        val adapter = adapters[prompt.type]
        val context = ForgeAiPromptContext(harness, attempts)
        if (adapter != null && adapter.shouldConsult(prompt, context)) {
            bumpConsulted(adapter.telemetryName)
            val response = timed(adapter.telemetryName) { adapter.decide(prompt, context) }
            if (response != null) {
                bumpChose(adapter.telemetryName)
                recordTargetChoice(prompt, response, source = "forge-ai")
                return response
            }
        }
        val response = super.respondToPrompt(prompt, attempts)
        recordTargetChoice(prompt, response, source = "greedy")
        return response
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun recordTargetChoice(
        prompt: ActivePrompt,
        response: SimPromptResponse,
        source: String,
    ) {
        if (prompt.type != GREMessageType.SelectTargetsReq_695e) return
        val req = prompt.msg.selectTargetsReq
        val decision = response.decision
        val targetKey =
            when (decision) {
                is SimDecision.SelectTargets ->
                    decision.targetInstanceIds.joinToString("+") { targetChoiceObjectKey(it) }
                else -> decision.kind
            }
        val sourceKey = targetChoiceObjectKey(req.sourceId)
        val key = "source=$source|abilityGrpId=${req.abilityGrpId}|from=$sourceKey|to=$targetKey"
        targetChoiceCounts.merge(key, 1) { a, b -> a + b }
        targetChoiceSamples.putIfAbsent(
            key,
            "sourceId=${req.sourceId};promptIds=${req.targetsList.joinToString(",") { it.prompt.promptId.toString() }};" +
                "targetingAbilityGrpIds=${req.targetsList.joinToString(",") { it.targetingAbilityGrpId.toString() }};" +
                "targetIds=${if (decision is SimDecision.SelectTargets) decision.targetInstanceIds.joinToString(",") else ""}",
        )
    }

    private fun targetChoiceObjectKey(instanceId: Int): String {
        val objectInfo = harness.observe().client.objects[instanceId]
        if (objectInfo == null) return "playerOrMissing:$instanceId"
        val name =
            if (objectInfo.type == GameObjectType.Card && objectInfo.grpId != 0) {
                runCatching { harness.cardNameByGrpId(objectInfo.grpId) }.getOrNull()
            } else {
                null
            } ?: "grp:${objectInfo.grpId}"
        val types = objectInfo.cardTypesList.joinToString("+") { it.name }
        return "object:${objectInfo.type.name}:grp:${objectInfo.grpId}:$name:ctrl:${objectInfo.controllerSeatId}:types:$types"
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

internal fun chooseSimClientModalGrpIds(msg: GREToClientMessage?): List<Int>? = msg?.let { DefaultDecisions.modalGrpIds(it) }

internal fun ActivePrompt.aarActions(): List<Action> = (payload as? PromptPayload.ActionsAvailable)?.req?.actionsList.orEmpty()

internal fun Action.isActionableAarAction(): Boolean =
    actionType == ActionType.Cast || actionType == ActionType.Play_add3 || actionType == ActionType.Activate_add3
