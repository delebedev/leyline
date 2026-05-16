package leyline.simclient

import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

internal interface SimPromptPolicy {
    fun respondToPrompt(
        prompt: ActivePrompt,
        attempts: ActionAttemptLedger,
    ): SimPromptResponse
}

internal data class SimPromptPolicyTelemetry(
    val consulted: Map<String, Int>,
    val chose: Map<String, Int>,
) {
    val consultedTotal: Int get() = consulted.values.sum()
    val choseTotal: Int get() = chose.values.sum()

    companion object {
        val Empty = SimPromptPolicyTelemetry(emptyMap(), emptyMap())
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
                SimPromptResponse(
                    decision = SimDecision.DeclareAllAttackers,
                    markHandled = false,
                    markAllHandledOfType = prompt.type,
                )
            GREMessageType.DeclareBlockersReq_695e ->
                respondDeclareBlockers()
            GREMessageType.ActionsAvailableReq_695e ->
                respondAar(prompt, attempts)
            GREMessageType.SelectTargetsReq_695e ->
                SimPromptResponse(respondSelectTargets(prompt.msg))
            GREMessageType.SelectNreq ->
                SimPromptResponse(respondSelectN(prompt.msg))
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

    protected fun hasActionableAar(prompt: ActivePrompt): Boolean = prompt.aarActions().any { it.isActionableAarAction() }

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
    ): Action? = prompt.aarActions().firstUnskipped(attempts) { it.actionType == ActionType.Cast }

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
                        .take(count)
                        .map { it.targetInstanceId }
                }.filter { it != 0 }
                .distinct()
        return if (ids.isEmpty()) SimDecision.CancelAction else SimDecision.SelectTargets(ids)
    }

    private fun respondSelectN(msg: GREToClientMessage): SimDecision {
        val req = msg.selectNReq
        val min = req.minSel.coerceAtLeast(0)
        val max = if (req.maxSel > 0) req.maxSel else min
        val count = min.coerceAtMost(max)
        return SimDecision.SelectN(req.idsList.take(count))
    }

    private fun respondSearch(msg: GREToClientMessage): SimDecision {
        val req = msg.searchReq
        val max = if (req.maxFind > 0) req.maxFind else req.minFind
        val count = max.coerceAtLeast(req.minFind).coerceAtLeast(1)
        return SimDecision.Search(req.itemsSoughtList.take(count))
    }

    private fun respondPayCosts(msg: GREToClientMessage): SimDecision {
        val selection = msg.payCostsReq.effectCostReq.costSelection
        val min = selection.minSel.coerceAtLeast(0)
        val max = if (selection.maxSel > 0) selection.maxSel else min
        val count = min.coerceAtMost(max)
        return SimDecision.EffectCost(selection.idsList.take(count))
    }

    private fun respondCastingTimeOptions(msg: GREToClientMessage): SimDecision {
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

    fun telemetry(): SimPromptPolicyTelemetry = SimPromptPolicyTelemetry(aiConsultedByPrompt.toMap(), aiChoseByPrompt.toMap())

    override fun advisedAarAction(
        prompt: ActivePrompt,
        attempts: ActionAttemptLedger,
    ): Action? {
        bumpConsulted("ActionsAvailableReq")
        val choice = forgeAi.chooseAarAction(prompt.aarActions(), attempts.skipFingerprints()) ?: return null
        bumpChose("ActionsAvailableReq")
        return choice.action
    }

    override fun advisedBlockers(): Map<Int, Int>? {
        bumpConsulted("DeclareBlockersReq")
        val assignments = forgeAi.chooseBlockers() ?: return null
        bumpChose("DeclareBlockersReq")
        return assignments
    }

    private fun bumpConsulted(prompt: String) {
        aiConsultedByPrompt.merge(prompt, 1) { a, b -> a + b }
    }

    private fun bumpChose(prompt: String) {
        aiChoseByPrompt.merge(prompt, 1) { a, b -> a + b }
    }
}

private fun ActivePrompt.aarActions(): List<Action> = (payload as? PromptPayload.ActionsAvailable)?.req?.actionsList.orEmpty()

private fun Action.isActionableAarAction(): Boolean =
    actionType == ActionType.Cast || actionType == ActionType.Play_add3 || actionType == ActionType.Activate_add3
