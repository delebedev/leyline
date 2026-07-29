package leyline.match

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.ManaSourcePaymentKind
import leyline.bridge.handoff.PayCostsPromptRoute
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.ManaColorMapping
import leyline.bridge.types.ManaCostText
import leyline.game.bundle.build
import leyline.game.data.KeywordAbilityIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/** Owns native PayCostsReq mana-source payment loops and lifecycle-local state. */
internal class PayCostsInteractionHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val bundles: BundleBuilderHolder,
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(PayCostsInteractionHandler::class.java)
    private val manaSourcePaymentSelections = mutableMapOf<String, LinkedHashSet<Int>>()
    private val convokePaymentSelections = mutableMapOf<String, List<ConvokePaymentSelection>>()

    private data class ConvokePaymentSelection(
        val forgeCardId: ForgeCardId,
        val color: ManaColor,
    )

    fun tryHandlePayCostsPerformAction(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean {
        val actions = greMsg.performActionResp.actionsList
        if (actions.none { it.actionType == ActionType.MakePayment || it.actionType == ActionType.Pass }) {
            return false
        }

        val bridge = ctx.bridge
        val pendingPrompt = bridge.seat(counters.seatId).prompt.getPendingPrompt() ?: return false
        val route = pendingPrompt.payCostsRoute() ?: return false
        val paymentKind = route.manaSourcePayment ?: return false

        val selectedIds =
            actions
                .flatMap { action ->
                    buildList {
                        if (action.actionType == ActionType.MakePayment && action.instanceId != 0) {
                            add(action.instanceId)
                        }
                        action.manaSelectionsList.mapNotNullTo(this) { selection ->
                            selection.instanceId.takeIf { id -> id != 0 }
                        }
                    }
                }.distinct()
        val selectedSet = manaSourcePaymentSelections.getOrPut(pendingPrompt.promptId) { linkedSetOf() }
        val newSelectedIds = selectedIds.filter { selectedSet.add(it) }

        if (actions.any { it.actionType == ActionType.Pass }) {
            val ids = manaSourcePaymentSelections[pendingPrompt.promptId]?.toList().orEmpty()
            submitManaSourcePayment(pendingPrompt, paymentKind, ids, autoPass)
            return true
        }

        if (newSelectedIds.isNotEmpty()) {
            val convokeSelections = recordConvokeSelections(pendingPrompt, paymentKind, newSelectedIds)
            log.info(
                "PayCostsInteractionHandler: {} MakePayment ids={} accumulated={}",
                manaSourceLabel(paymentKind),
                newSelectedIds,
                selectedSet,
            )
            recordPendingConvokePayments(pendingPrompt, convokeSelections)
            sendPayCostsReq(
                adjustManaSourcePaymentPrompt(pendingPrompt, paymentKind, selectedSet.toList(), convokeSelections),
                route,
            )
            return true
        }

        return true
    }

    fun clearPayment(promptId: String) {
        manaSourcePaymentSelections.remove(promptId)
        convokePaymentSelections.remove(promptId)
    }

    fun submitPartialPaymentForCancel(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        autoPass: () -> Unit,
    ): Boolean {
        val paymentKind = pendingPrompt.payCostsRoute()?.manaSourcePayment ?: return false
        val ids = manaSourcePaymentSelections[pendingPrompt.promptId]?.toList().orEmpty()
        if (ids.isEmpty()) return false
        log.info(
            "PayCostsInteractionHandler: CancelActionReq — completing {} payment ids={}",
            manaSourceLabel(paymentKind),
            ids,
        )
        submitManaSourcePayment(pendingPrompt, paymentKind, ids, autoPass)
        return true
    }

    fun sendPayCostsReq(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        route: PayCostsPromptRoute,
    ) {
        val bridge = ctx.bridge
        val (req, prompt) = route.build(pendingPrompt, bridge)
        val result =
            bundles.bundleBuilder.payCostsBundle(
                ctx.snapshot(),
                counters.counter,
                req,
                prompt,
            )
        Tap.outboundTemplate("PayCostsReq(${route.templateLabel}) seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    private fun submitManaSourcePayment(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        paymentKind: ManaSourcePaymentKind,
        selectedIds: List<Int>,
        autoPass: () -> Unit,
    ) {
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(selectedIds, pendingPrompt)
        log.info(
            "PayCostsInteractionHandler: {} payment ids={} indices={}",
            manaSourceLabel(paymentKind),
            selectedIds,
            selectedIndices,
        )
        val submitted =
            ctx.bridge
                .seat(counters.seatId)
                .prompt
                .submitResponse(pendingPrompt.promptId, selectedIndices) {
                    clearPayment(pendingPrompt.promptId)
                }
        if (!submitted) return
        ctx.engine.awaitPriority()
        autoPass()
    }

    private fun adjustManaSourcePaymentPrompt(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        paymentKind: ManaSourcePaymentKind,
        selectedIds: List<Int>,
        convokeSelections: List<ConvokePaymentSelection> = emptyList(),
    ): InteractivePromptBridge.PendingPrompt {
        val bridge = ctx.bridge
        val selectedForgeIds = selectedIds.mapNotNull { bridge.getForgeCardId(InstanceId(it))?.value }.toSet()
        val remainingRefs = pendingPrompt.request.candidateRefs.filterNot { it.isCard() && it.entityId in selectedForgeIds }
        val remainingOptions = remainingRefs.map { ref -> pendingPrompt.request.options.getOrNull(ref.index) ?: "" }
        val remainingManaCost =
            if (paymentKind == ManaSourcePaymentKind.Convoke) {
                reduceConvokeManaCost(pendingPrompt.request.waterbendManaCost, convokeSelections)
            } else {
                pendingPrompt.request.waterbendManaCost.reduceGenericBy(selectedIds.size)
            }
        return pendingPrompt.copy(
            request =
                pendingPrompt.request.copy(
                    options = remainingOptions,
                    max = (pendingPrompt.request.max - selectedIds.size).coerceAtLeast(0),
                    candidateRefs = remainingRefs.mapIndexed { index, ref -> ref.copy(index = index) },
                    waterbendManaCost = remainingManaCost,
                    waterbendCostString = ManaCostText.clientText(remainingManaCost).takeIf { it.isNotEmpty() },
                ),
        )
    }

    private fun recordConvokeSelections(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        paymentKind: ManaSourcePaymentKind,
        selectedIds: List<Int>,
    ): List<ConvokePaymentSelection> {
        if (paymentKind == ManaSourcePaymentKind.Waterbend) {
            return emptyList()
        }
        val existing = convokePaymentSelections[pendingPrompt.promptId].orEmpty()
        val newSelections = chooseConvokePaymentSelections(pendingPrompt, paymentKind, existing, selectedIds)
        val next = existing + newSelections
        convokePaymentSelections[pendingPrompt.promptId] = next
        return next
    }

    private fun recordPendingConvokePayments(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        selections: List<ConvokePaymentSelection>,
    ) {
        val paymentKind = pendingPrompt.payCostsRoute()?.manaSourcePayment ?: return
        if (paymentKind == ManaSourcePaymentKind.Waterbend) return
        if (selections.isEmpty()) return
        val source =
            ctx.bridge.currentStackSourceCardId(pendingPrompt.request.sourceEntityId) ?: return
        ctx.bridge
            .seat(counters.seatId)
            .prompt
            .journal
            .record(
                PromptSideEffect.ConvokePayments(
                    sourceForgeCardId = source,
                    payments =
                        selections.map { selection ->
                            PromptSideEffect.ConvokePayment(
                                paymentForgeCardId = selection.forgeCardId,
                                color = ManaColorMapping.paymentWireColor(selection.color).number,
                                substitutionGrpId = paymentKeywordGrpId(paymentKind),
                                paymentAbilityGrpId = paymentAbilityGrpId(paymentKind),
                            )
                        },
                ),
            )
    }

    private fun chooseConvokePaymentSelections(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        paymentKind: ManaSourcePaymentKind,
        existing: List<ConvokePaymentSelection>,
        selectedIds: List<Int>,
    ): List<ConvokePaymentSelection> {
        if (selectedIds.isEmpty()) return emptyList()
        val remainingCost =
            if (paymentKind == ManaSourcePaymentKind.Convoke) {
                reduceConvokeManaCost(pendingPrompt.request.waterbendManaCost, existing)
            } else {
                pendingPrompt.request.waterbendManaCost.reduceGenericBy(existing.size)
            }
        val existingForgeIds = existing.map { it.forgeCardId }.toSet()
        val plan = convokeAssignmentPlan(pendingPrompt, remainingCost, existingForgeIds)
        return selectedIds.mapNotNull { iid ->
            val forgeId = ctx.bridge.getForgeCardId(InstanceId(iid)) ?: return@mapNotNull null
            val color =
                if (paymentKind == ManaSourcePaymentKind.Convoke) {
                    plan[forgeId] ?: ctx.bridge.fallbackConvokeColor(forgeId, remainingCost)
                } else {
                    ManaColor.Generic
                } ?: return@mapNotNull null
            ConvokePaymentSelection(forgeId, color)
        }
    }

    private fun reduceConvokeManaCost(
        cost: List<Pair<ManaColor, Int>>,
        selections: List<ConvokePaymentSelection>,
    ): List<Pair<ManaColor, Int>> {
        val remaining = cost.associate { it.first to it.second }.toMutableMap()
        for (selection in selections) {
            val next = (remaining[selection.color] ?: 0) - 1
            if (next <= 0) remaining.remove(selection.color) else remaining[selection.color] = next
        }
        return cost.mapNotNull { (color, _) -> remaining[color]?.takeIf { it > 0 }?.let { color to it } }
    }

    private fun convokeAssignmentPlan(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        cost: List<Pair<ManaColor, Int>>,
        existingForgeIds: Set<ForgeCardId>,
    ): Map<ForgeCardId, ManaColor> {
        val candidates =
            pendingPrompt.request.candidateRefs.mapNotNull { ref ->
                val forgeId = ForgeCardId(ref.entityId)
                if (forgeId in existingForgeIds) return@mapNotNull null
                forgeId
            }
        return ctx.bridge.convokeAssignmentPlan(candidates, cost)
    }

    private fun mapSelectedInstanceIdsToPromptIndices(
        selectedInstanceIds: List<Int>,
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ): List<Int> =
        PromptResponseSubmitter.mapSelectNIdsToPromptIndices(selectedInstanceIds, pendingPrompt) { instanceId ->
            ctx.bridge.getForgeCardId(InstanceId(instanceId))
        }

    private fun manaSourceLabel(kind: ManaSourcePaymentKind): String =
        when (kind) {
            ManaSourcePaymentKind.Convoke -> "Convoke"
            ManaSourcePaymentKind.Improvise -> "Improvise"
            ManaSourcePaymentKind.Waterbend -> "Waterbend"
        }

    private fun paymentKeywordGrpId(kind: ManaSourcePaymentKind): Int =
        when (kind) {
            ManaSourcePaymentKind.Improvise -> KeywordAbilityIds.IMPROVISE
            ManaSourcePaymentKind.Convoke -> KeywordAbilityIds.CONVOKE
            ManaSourcePaymentKind.Waterbend -> error("Waterbend has no convoke payment annotation")
        }

    private fun paymentAbilityGrpId(kind: ManaSourcePaymentKind): Int =
        when (kind) {
            ManaSourcePaymentKind.Improvise -> KeywordAbilityIds.IMPROVISE
            ManaSourcePaymentKind.Convoke -> KeywordAbilityIds.CONVOKE_PAYMENT
            ManaSourcePaymentKind.Waterbend -> error("Waterbend has no convoke payment annotation")
        }

    private fun InteractivePromptBridge.PendingPrompt.payCostsRoute(): PayCostsPromptRoute? =
        (request.route as? ResolvedPromptRoute.PayCosts)?.descriptor

    private fun List<Pair<ManaColor, Int>>.reduceGenericBy(count: Int): List<Pair<ManaColor, Int>> {
        var remainingReduction = count
        return mapNotNull { (color, amount) ->
            if (color == ManaColor.Generic && remainingReduction > 0) {
                val reducedAmount = (amount - remainingReduction).coerceAtLeast(0)
                remainingReduction = (remainingReduction - amount).coerceAtLeast(0)
                if (reducedAmount > 0) color to reducedAmount else null
            } else {
                color to amount
            }
        }
    }
}
