package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptResponseMapper
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.bundle.SelectNPromptRoutes
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
        val pendingPrompt = pendingPromptOrWarn("SelectNResp") ?: return
        val selectedIds = greMsg.selectNResp.idsList
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(selectedIds, pendingPrompt)

        recordChoiceResults(pendingPrompt, selectedIds)

        log.info("TargetingHandler: SelectNResp indices={}", selectedIndices)
        submit(pendingPrompt, selectedIndices, autoPass)
    }

    fun onOrderResp(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val pendingPrompt = pendingPromptOrWarn("OrderResp") ?: return
        val orderedIds = greMsg.orderResp.idsList
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(orderedIds, pendingPrompt)

        log.info("TargetingHandler: OrderResp ids={} indices={}", orderedIds, selectedIndices)
        submit(pendingPrompt, selectedIndices, autoPass)
    }

    fun onEffectCost(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
        clearManaSourcePayment: (String) -> Unit,
    ) {
        val pendingPrompt = pendingPromptOrWarn("EffectCostResp") ?: return
        val ids = greMsg.effectCostResp.costSelection.idsList
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(ids, pendingPrompt)
        if (isManaSourcePaymentSemantic(pendingPrompt.request.semantic)) {
            clearManaSourcePayment(pendingPrompt.promptId)
        }

        log.info("TargetingHandler: EffectCostResp indices={}", selectedIndices)
        submit(pendingPrompt, selectedIndices, autoPass)
    }

    private fun pendingPromptOrWarn(label: String): InteractivePromptBridge.PendingPrompt? {
        val pendingPrompt =
            ctx.bridge
                .seat(counters.seatId)
                .prompt
                .getPendingPrompt()
        if (pendingPrompt == null) {
            log.warn("TargetingHandler: {} but no pending prompt (likely timeout race)", label)
            DevCheck.failOnAutoPass { "$label but no pending prompt" }
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
        fun isManaSourcePaymentSemantic(semantic: PromptSemantic): Boolean =
            semantic == PromptSemantic.WaterbendCost || semantic == PromptSemantic.ConvokeCost

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
            val semantic = pendingPrompt.request.semantic
            val (choiceDomain, sentiment) =
                when (val staticChoice = SelectNPromptRoutes.staticChoice(semantic)) {
                    null ->
                        when {
                            semantic == PromptSemantic.SelectNDiscard ||
                                semantic == PromptSemantic.SelectNSacrificeEffect -> null to 1
                            semantic == PromptSemantic.SuspectChoice -> null to 2
                            else -> return emptyList()
                        }
                    else -> staticChoice.choiceDomain to 2
                }
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
