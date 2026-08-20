package leyline.tooling.simclient

import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
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

private const val STALL_HORIZON_GSIDS = 8

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
     * [activePrompt] scans newest-first for anything unhandled, and a prompt the
     * driver skipped rather than answered is never handled and — unless it needs
     * the action bridge — never retired. Once a game stalls and every newer
     * prompt is resolved, that scan walks back to the oldest survivor, so
     * reporting it unfiltered names a prompt that stopped mattering hundreds of
     * game states earlier and sends triage at the wrong subsystem. A prompt that
     * is genuinely blocking sits at the head of the state stream; only trailing
     * state messages separate it from the last message seen.
     */
    fun stallPrompt(): SimStallPrompt {
        val latestGsId = harness.allMessages.lastOrNull()?.gameStateId ?: 0
        val unhandled = activePrompt()
        if (unhandled != null && latestGsId - unhandled.gsId <= STALL_HORIZON_GSIDS) {
            return SimStallPrompt(unhandled.type.name, unhandled.fingerprint)
        }
        val last = lastPromptMessage()?.let { (msg, type) -> "last=${type.name}:${msg.promptFingerprint()}" }
        val label =
            if (unhandled == null) {
                "IdleNoUnhandledPrompt"
            } else {
                "IdleStalePromptOnly:${unhandled.type.name}@gs${unhandled.gsId}"
            }
        return SimStallPrompt(label, last)
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
        msg.hasSearchReq() ||
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
    if (actionType != ActionType.Cast) return setOf(exact)
    val stable = listOf("retry", actionType.name, grpId, abilityGrpId, alternativeGrpId).joinToString(":")
    return setOf(exact, stable)
}

internal fun Action.isSkippedBy(skipFingerprints: Set<String>): Boolean = retryFingerprints().any { it in skipFingerprints }
