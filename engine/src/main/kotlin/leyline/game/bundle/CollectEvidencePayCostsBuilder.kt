package leyline.game.bundle

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.EffectCostReq
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType

internal object CollectEvidencePayCostsBuilder {
    fun build(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<PayCostsReq, Prompt> {
        val sourceInstanceId = sourceInstanceIdForPrompt(prompt, bridge)
        val maxSel =
            prompt.request.max
                .coerceAtLeast(prompt.request.min)
                .coerceAtLeast(0)
        val weights = prompt.request.costSelectionWeights.map { it.coerceAtLeast(0) }
        require(weights.size == prompt.request.candidateRefs.size) {
            "Collect Evidence cost weights must match candidate count"
        }
        val minWeight =
            requireNotNull(prompt.request.minSelectionWeight) {
                "Collect Evidence cost requires a minimum selection weight"
            }
        val minSel =
            prompt.request.min
                .coerceAtLeast(0)
                .coerceAtMost(maxSel)
        val selection =
            SelectNReq
                .newBuilder()
                .setMinSel(minSel)
                .setMaxSel(maxSel)
                .setContext(SelectionContext.NonManaPayment)
                .setOptionContext(OptionContext.Payment)
                .setListType(SelectionListType.Dynamic)
                .setIdType(IdType.InstanceId_ab2c)
                .setValidationType(SelectionValidationType.NonRepeatable)
                .setMinWeight(minWeight)
                .setMaxWeight(Int.MAX_VALUE)

        for ((idx, ref) in prompt.request.candidateRefs.withIndex()) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
            selection.addIds(instanceId)
            selection.addWeights(weights.getOrElse(idx) { 1 })
        }

        val req =
            PayCostsReq
                .newBuilder()
                .setPaymentActions(ActionsAvailableReq.newBuilder().build())
                .setEffectCostReq(
                    EffectCostReq
                        .newBuilder()
                        .setEffectCostType(EffectCostType.Select_a59c)
                        .setCostSelection(selection),
                ).build()

        return req to promptWithCardId(PromptIds.COLLECT_EVIDENCE_COST, sourceInstanceId)
    }

    private fun sourceInstanceIdForPrompt(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Int =
        prompt.request.sourceEntityId?.let {
            bridge.getOrAllocInstanceId(ForgeCardId(it)).value
        } ?: 0
}
