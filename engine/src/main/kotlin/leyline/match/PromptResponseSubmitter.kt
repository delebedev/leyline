package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptResponseMapper
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
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

        recordChoiceResults(pendingPrompt, selectedIds)

        log.info("PromptResponseSubmitter: SelectNResp indices={}", selectedIndices)
        submit(pendingPrompt, selectedIndices, autoPass)
    }

    fun onOrderResp(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val pendingPrompt = pendingPromptOrWarn("OrderResp", PromptResponseKind.Order) ?: return
        val orderedIds = greMsg.orderResp.idsList
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(orderedIds, pendingPrompt)

        log.info("PromptResponseSubmitter: OrderResp ids={} indices={}", orderedIds, selectedIndices)
        submit(pendingPrompt, selectedIndices, autoPass)
    }

    fun onEffectCost(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
        clearManaSourcePayment: (String) -> Unit,
    ) {
        val pendingPrompt = pendingPromptOrWarn("EffectCostResp", PromptResponseKind.EffectCost) ?: return
        val ids = greMsg.effectCostResp.costSelection.idsList
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(ids, pendingPrompt)
        val route = pendingPrompt.request.route as? ResolvedPromptRoute.PayCosts
        if (route?.descriptor?.manaSourcePayment != null) {
            clearManaSourcePayment(pendingPrompt.promptId)
        }

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

    private fun recordChoiceResults(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        selectedIds: List<Int>,
    ) {
        val effects = choiceResultSideEffects(pendingPrompt, selectedIds, counters.seatId)
        if (effects.isEmpty()) return
        val journal =
            ctx.bridge
                .seat(counters.seatId)
                .prompt
                .journal
        effects.forEach(journal::record)
    }

    companion object {
        fun mapSelectNIdsToPromptIndices(
            selectedIds: List<Int>,
            pendingPrompt: InteractivePromptBridge.PendingPrompt,
            resolveForgeCardId: (Int) -> ForgeCardId?,
        ): List<Int> = PromptResponseMapper.selectNIdsToPromptIndices(selectedIds, pendingPrompt.request, resolveForgeCardId)

        fun choiceResultSideEffects(
            pendingPrompt: InteractivePromptBridge.PendingPrompt,
            selectedIds: List<Int>,
            chooserSeatId: SeatId,
        ): List<PromptSideEffect.ChoiceResult> {
            val source = pendingPrompt.request.sourceEntityId ?: return emptyList()
            val route = (pendingPrompt.request.route as? ResolvedPromptRoute.SelectN)?.descriptor ?: return emptyList()
            val sentiment = route.choiceResultSentiment ?: return emptyList()
            val choiceDomain = route.staticChoice?.choiceDomain
            return selectedIds.map { value ->
                PromptSideEffect.ChoiceResult(
                    sourceForgeCardId = ForgeCardId(source),
                    chooserSeatId = chooserSeatId,
                    choiceValue = value,
                    choiceDomain = choiceDomain,
                    sentiment = sentiment,
                )
            }
        }
    }
}
