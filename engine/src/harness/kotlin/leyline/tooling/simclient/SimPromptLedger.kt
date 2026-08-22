package leyline.tooling.simclient

import leyline.copilot.isCopilotCastOffer
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

internal data class ActivePrompt(
    val msg: GREToClientMessage,
    val type: GREMessageType,
    val msgId: Int,
    val gsId: Int,
    val fingerprint: String,
    val payload: PromptPayload,
) {
    val requiresActionBridge: Boolean =
        type == GREMessageType.ActionsAvailableReq_695e ||
            type == GREMessageType.DeclareAttackersReq_695e ||
            type == GREMessageType.DeclareBlockersReq_695e
}

internal sealed interface PromptPayload {
    data class ActionsAvailable(
        val req: ActionsAvailableReq,
    ) : PromptPayload

    data object Other : PromptPayload
}

internal data class SimStallPrompt(
    val prompt: String,
    val fingerprint: String?,
)

internal data class SimPromptLedgerStats(
    val retiredByReason: Map<String, Int>,
) {
    companion object {
        val Empty = SimPromptLedgerStats(emptyMap())
    }
}

internal class SimPromptLedger(
    private val harness: MatchFlowHarness,
) {
    private val handledPromptMsgIds = mutableSetOf<Int>()
    private val retiredPromptMsgIds = mutableMapOf<Int, String>()
    private val retiredByReason = mutableMapOf<String, Int>()

    fun activePrompt(): ActivePrompt? {
        for (i in harness.allMessages.indices.reversed()) {
            val msg = harness.allMessages[i]
            if (!isSimPrompt(msg)) continue
            if (msg.msgId in handledPromptMsgIds || msg.msgId in retiredPromptMsgIds) continue
            val active = msg.toActivePrompt()
            retireSupersededActionBridgePrompts(active)
            return active
        }
        return null
    }

    fun markHandled(prompt: ActivePrompt) {
        handledPromptMsgIds += prompt.msgId
    }

    fun markHandled(msg: GREToClientMessage) {
        handledPromptMsgIds += msg.msgId
    }

    /** Mark a prompt answered by a responder that consumed it internally. */
    fun markHandled(msgId: Int) {
        handledPromptMsgIds += msgId
    }

    fun markAllHandled(
        type: GREMessageType,
        throughMsgId: Int = Int.MAX_VALUE,
    ) {
        harness.allMessages
            .filter { it.type == type && it.msgId <= throughMsgId }
            .forEach { markHandled(it) }
    }

    fun retire(
        prompt: ActivePrompt,
        reason: String,
    ) {
        retiredPromptMsgIds[prompt.msgId] = reason
        retiredByReason.merge(reason, 1) { a, b -> a + b }
    }

    fun retireActionBridgePrompts(reason: String) {
        harness.allMessages
            .asSequence()
            .filter { isSimPrompt(it) }
            .map { it.toActivePrompt() }
            .filter { it.requiresActionBridge }
            .filter { it.msgId !in handledPromptMsgIds && it.msgId !in retiredPromptMsgIds }
            .forEach { retire(it, reason) }
    }

    private fun retireSupersededActionBridgePrompts(active: ActivePrompt) {
        if (!active.requiresActionBridge) return
        harness.allMessages
            .asSequence()
            .filter { isSimPrompt(it) }
            .map { it.toActivePrompt() }
            .filter { it.requiresActionBridge }
            .filter { it.msgId != active.msgId }
            .filter { it.msgId !in handledPromptMsgIds && it.msgId !in retiredPromptMsgIds }
            .filter { it.gsId <= active.gsId }
            .forEach { retire(it, "superseded") }
    }

    fun stats(): SimPromptLedgerStats = SimPromptLedgerStats(retiredByReason.toMap())

    /**
     * What the driver was waiting on when it gave up.
     *
     * [activePrompt] never returns a prompt the game has moved past, so anything
     * it hands back here is live. A stall with nothing live to answer is the
     * engine having gone quiet; `retiredByReason["stale"]` says whether prompts
     * were stepped over on the way there.
     */
    fun stallPrompt(): SimStallPrompt {
        val unhandled = activePrompt()
        if (unhandled != null) return SimStallPrompt(unhandled.type.name, unhandled.fingerprint)
        return SimStallPrompt(
            prompt = "IdleNoUnhandledPrompt",
            fingerprint = lastPromptMessage()?.let { (msg, type) -> "last=${type.name}:${msg.promptFingerprint()}" },
        )
    }

    private fun GREToClientMessage.toActivePrompt(): ActivePrompt =
        ActivePrompt(
            msg = this,
            type = type,
            msgId = msgId,
            gsId = gameStateId,
            fingerprint = promptFingerprint(),
            payload =
                if (hasActionsAvailableReq()) {
                    PromptPayload.ActionsAvailable(actionsAvailableReq)
                } else {
                    PromptPayload.Other
                },
        )

    private fun lastPromptMessage(): Pair<GREToClientMessage, GREMessageType>? {
        for (i in harness.allMessages.indices.reversed()) {
            val msg = harness.allMessages[i]
            if (isSimPrompt(msg)) return msg to msg.type
        }
        return null
    }

    private fun GREToClientMessage.promptFingerprint(): String =
        when {
            hasActionsAvailableReq() ->
                actionsAvailableReq.actionsList.joinToString("|") { it.actionFingerprint() }
            hasSelectNReq() ->
                "SelectN:${selectNReq.minSel}:${selectNReq.maxSel}:${selectNReq.idsList.joinToString(",")}"
            hasOrderReq() ->
                "Order:${orderReq.idsList.joinToString(",")}"
            hasDistributionReq() ->
                "Distribution:${distributionReq.minAmount}:${distributionReq.maxAmount}:${distributionReq.targetIdsList.joinToString(",")}"
            hasSelectReplacementReq() ->
                "SelectReplacement:" +
                    selectReplacementReq.replacementsList.joinToString("|") { row ->
                        "${row.objectInstance}:${row.uniqueAbilityId}:${row.abilityGrpId}:${row.affectedObject}:${row.replacementEffectId}"
                    }
            hasSearchFromGroupsReq() ->
                "SearchFromGroups:" +
                    searchFromGroupsReq.groupsList.joinToString("|") { group ->
                        "${group.groupId}:${group.maxSelect}:${group.idsList.joinToString(",")}"
                    }
            hasSelectTargetsReq() ->
                "SelectTargets:" +
                    selectTargetsReq.targetsList.joinToString("|") { sel ->
                        "${sel.minTargets}:${sel.maxTargets}:" +
                            sel.targetsList.joinToString(",") { "${it.targetInstanceId}:${it.legalAction.name}" }
                    }
            hasPayCostsReq() ->
                payCostsReq.effectCostReq.costSelection.let { sel ->
                    "PayCosts:${sel.minSel}:${sel.maxSel}:${sel.idsList.joinToString(",")}"
                }
            else -> type.name
        }
}

internal fun isSimPrompt(msg: GREToClientMessage): Boolean =
    msg.hasActionsAvailableReq() ||
        msg.hasDeclareAttackersReq() ||
        msg.hasDeclareBlockersReq() ||
        msg.hasSelectTargetsReq() ||
        msg.hasGroupReq() ||
        msg.hasSelectNReq() ||
        msg.hasOrderReq() ||
        msg.hasDistributionReq() ||
        msg.hasSelectReplacementReq() ||
        msg.hasSearchReq() ||
        msg.hasSearchFromGroupsReq() ||
        msg.hasPayCostsReq() ||
        msg.hasAssignDamageReq() ||
        msg.hasMulliganReq() ||
        msg.hasIntermissionReq() ||
        msg.hasOptionalActionMessage() ||
        msg.hasCastingTimeOptionsReq() ||
        msg.hasNumericInputReq()

internal fun Action.actionFingerprint(): String =
    listOf(actionType.name, instanceId, grpId, abilityGrpId, alternativeGrpId).joinToString(":")

internal fun Action.retryFingerprints(): Set<String> {
    val exact = actionFingerprint()
    if (!actionType.isCopilotCastOffer()) return setOf(exact)
    val stable = listOf("retry", actionType.name, grpId, abilityGrpId, alternativeGrpId).joinToString(":")
    return setOf(exact, stable)
}

internal fun Action.isSkippedBy(skipFingerprints: Set<String>): Boolean = retryFingerprints().any { it in skipFingerprints }
