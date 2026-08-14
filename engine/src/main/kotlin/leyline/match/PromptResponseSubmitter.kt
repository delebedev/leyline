package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptResponseMapper
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Shared session-side response mapping/submission for non-target prompt replies. */
internal class PromptResponseSubmitter(
    private val counters: SessionCounters,
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(PromptResponseSubmitter::class.java)

    fun onSelectN(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val pendingPrompt = pendingPromptOrWarn("SelectNResp", PromptResponseKind.SelectN) ?: return
        val selectedIds = greMsg.selectNResp.idsList
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(selectedIds, pendingPrompt)

        log.info("PromptResponseSubmitter: SelectNResp indices={}", selectedIndices)
        submit(pendingPrompt, selectedIndices, autoPass)
    }

    fun onEffectCost(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val pendingPrompt = pendingPromptOrWarn("EffectCostResp", PromptResponseKind.EffectCost) ?: return
        val ids = greMsg.effectCostResp.costSelection.idsList
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(ids, pendingPrompt)
        log.info("PromptResponseSubmitter: EffectCostResp indices={}", selectedIndices)
        submit(pendingPrompt, selectedIndices, autoPass)
    }

    private fun pendingPromptOrWarn(
        label: String,
        responseKind: PromptResponseKind,
    ): InteractivePromptBridge.PendingPrompt? {
        val pendingPrompt =
            ctx.bridge
                .seat(counters.seatId)
                .prompt
                .getPendingPrompt()
        if (pendingPrompt == null) {
            log.warn("PromptResponseSubmitter: {} but no pending prompt (likely timeout race)", label)
            DevCheck.failOnAutoPass { "$label but no pending prompt" }
        }
        if (pendingPrompt != null && !pendingPrompt.request.route.accepts(responseKind)) {
            log.warn("PromptResponseSubmitter: {} does not match bound route {}", label, pendingPrompt.request.route)
            DevCheck.failOnAutoPass { "$label does not match bound route ${pendingPrompt.request.route}" }
            return null
        }
        return pendingPrompt
    }

    private fun submit(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        selectedIndices: List<Int>,
        autoPass: () -> Unit,
    ) {
        ctx.bridge
            .seat(counters.seatId)
            .prompt
            .submitResponse(pendingPrompt.promptId, selectedIndices)
        ctx.bridge.awaitPriority()
        autoPass()
    }

    private fun mapSelectedInstanceIdsToPromptIndices(
        selectedInstanceIds: List<Int>,
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ): List<Int> =
        mapSelectNIdsToPromptIndices(selectedInstanceIds, pendingPrompt) { instanceId ->
            ctx.bridge.getForgeCardId(InstanceId(instanceId))
        }

    companion object {
        fun mapSelectNIdsToPromptIndices(
            selectedIds: List<Int>,
            pendingPrompt: InteractivePromptBridge.PendingPrompt,
            resolveForgeCardId: (Int) -> ForgeCardId?,
        ): List<Int> = PromptResponseMapper.selectNIdsToPromptIndices(selectedIds, pendingPrompt.request, resolveForgeCardId)
    }
}
