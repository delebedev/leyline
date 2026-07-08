package leyline.match

import forge.card.mana.ManaCostShard
import leyline.bridge.coord.ConvokeShardAssigner
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.ManaColorMapping
import leyline.bridge.types.ManaCostText
import leyline.game.annotations.AnnotationBuilder
import leyline.game.bundle.PayCostsPromptRoute
import leyline.game.bundle.SelectNPromptRoutes
import leyline.game.data.KeywordAbilityIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
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
        val shard: ManaCostShard,
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
        if (!PromptResponseSubmitter.isManaSourcePaymentSemantic(pendingPrompt.request.semantic)) return false

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
            val ids = manaSourcePaymentSelections.remove(pendingPrompt.promptId)?.toList().orEmpty()
            convokePaymentSelections.remove(pendingPrompt.promptId)
            submitManaSourcePayment(pendingPrompt, ids, autoPass)
            return true
        }

        if (newSelectedIds.isNotEmpty()) {
            val convokeSelections = recordConvokeSelections(pendingPrompt, newSelectedIds)
            log.info(
                "PayCostsInteractionHandler: {} MakePayment ids={} accumulated={}",
                manaSourceLabel(pendingPrompt.request.semantic),
                newSelectedIds,
                selectedSet,
            )
            recordPendingConvokePayments(pendingPrompt, convokeSelections)
            sendPayCostsReq(
                adjustManaSourcePaymentPrompt(pendingPrompt, selectedSet.toList(), convokeSelections),
                SelectNPromptRoutes.payCosts(pendingPrompt.request.semantic)
                    ?: error("missing PayCosts route for ${pendingPrompt.request.semantic}"),
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
        if (!PromptResponseSubmitter.isManaSourcePaymentSemantic(pendingPrompt.request.semantic)) return false
        val ids = manaSourcePaymentSelections.remove(pendingPrompt.promptId)?.toList().orEmpty()
        convokePaymentSelections.remove(pendingPrompt.promptId)
        if (ids.isEmpty()) return false
        log.info(
            "PayCostsInteractionHandler: CancelActionReq — completing {} payment ids={}",
            manaSourceLabel(pendingPrompt.request.semantic),
            ids,
        )
        submitManaSourcePayment(pendingPrompt, ids, autoPass)
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
                ctx.game,
                counters.counter,
                req,
                prompt,
                convokeCountPersistentAnnotations(pendingPrompt),
            )
        Tap.outboundTemplate("PayCostsReq(${route.templateLabel}) seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    private fun submitManaSourcePayment(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        selectedIds: List<Int>,
        autoPass: () -> Unit,
    ) {
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(selectedIds, pendingPrompt)
        log.info(
            "PayCostsInteractionHandler: {} payment ids={} indices={}",
            manaSourceLabel(pendingPrompt.request.semantic),
            selectedIds,
            selectedIndices,
        )
        ctx.bridge
            .seat(counters.seatId)
            .prompt
            .submitResponse(pendingPrompt.promptId, selectedIndices)
        ctx.bridge.awaitPriority()
        autoPass()
    }

    private fun adjustManaSourcePaymentPrompt(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        selectedIds: List<Int>,
        convokeSelections: List<ConvokePaymentSelection> = emptyList(),
    ): InteractivePromptBridge.PendingPrompt {
        val bridge = ctx.bridge
        val selectedForgeIds = selectedIds.mapNotNull { bridge.getForgeCardId(InstanceId(it))?.value }.toSet()
        val remainingRefs = pendingPrompt.request.candidateRefs.filterNot { it.isCard() && it.entityId in selectedForgeIds }
        val remainingOptions = remainingRefs.map { ref -> pendingPrompt.request.options.getOrNull(ref.index) ?: "" }
        val remainingManaCost =
            if (pendingPrompt.request.semantic == PromptSemantic.ConvokeCost) {
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
        selectedIds: List<Int>,
    ): List<ConvokePaymentSelection> {
        if (pendingPrompt.request.semantic != PromptSemantic.ConvokeCost &&
            pendingPrompt.request.semantic != PromptSemantic.ImproviseCost
        ) {
            return emptyList()
        }
        val existing = convokePaymentSelections[pendingPrompt.promptId].orEmpty()
        val newSelections = chooseConvokePaymentSelections(pendingPrompt, existing, selectedIds)
        val next = existing + newSelections
        convokePaymentSelections[pendingPrompt.promptId] = next
        return next
    }

    private fun recordPendingConvokePayments(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        selections: List<ConvokePaymentSelection>,
    ) {
        if (pendingPrompt.request.semantic != PromptSemantic.ConvokeCost &&
            pendingPrompt.request.semantic != PromptSemantic.ImproviseCost
        ) {
            return
        }
        if (selections.isEmpty()) return
        val source =
            pendingPrompt.request.sourceEntityId ?: ctx.game.stack
                .firstOrNull()
                ?.spellAbility
                ?.hostCard
                ?.id ?: return
        ctx.bridge
            .seat(counters.seatId)
            .prompt
            .journal
            .record(
                PromptSideEffect.ConvokePayments(
                    sourceForgeCardId = ForgeCardId(source),
                    payments =
                        selections.map { selection ->
                            PromptSideEffect.ConvokePayment(
                                paymentForgeCardId = selection.forgeCardId,
                                color = ManaColorMapping.paymentWireColor(selection.shard).number,
                                substitutionGrpId = paymentKeywordGrpId(pendingPrompt.request.semantic),
                                paymentAbilityGrpId = paymentAbilityGrpId(pendingPrompt.request.semantic),
                            )
                        },
                ),
            )
    }

    private fun chooseConvokePaymentSelections(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        existing: List<ConvokePaymentSelection>,
        selectedIds: List<Int>,
    ): List<ConvokePaymentSelection> {
        if (selectedIds.isEmpty()) return emptyList()
        val remainingCost =
            if (pendingPrompt.request.semantic == PromptSemantic.ConvokeCost) {
                reduceConvokeManaCost(pendingPrompt.request.waterbendManaCost, existing)
            } else {
                pendingPrompt.request.waterbendManaCost.reduceGenericBy(existing.size)
            }
        val existingForgeIds = existing.map { it.forgeCardId }.toSet()
        val plan = convokeAssignmentPlan(pendingPrompt, remainingCost, existingForgeIds)
        return selectedIds.mapNotNull { iid ->
            val forgeId = ctx.bridge.getForgeCardId(InstanceId(iid)) ?: return@mapNotNull null
            val card = ctx.bridge.findCard(forgeId) ?: return@mapNotNull null
            val shard =
                if (pendingPrompt.request.semantic == PromptSemantic.ConvokeCost) {
                    plan[forgeId] ?: fallbackConvokeShard(card.color, remainingCost)
                } else {
                    ManaCostShard.GENERIC
                } ?: return@mapNotNull null
            ConvokePaymentSelection(forgeId, shard)
        }
    }

    private fun reduceConvokeManaCost(
        cost: List<Pair<ManaColor, Int>>,
        selections: List<ConvokePaymentSelection>,
    ): List<Pair<ManaColor, Int>> {
        val remaining = cost.associate { it.first to it.second }.toMutableMap()
        for (selection in selections) {
            val color = ManaColorMapping.paymentCostColor(selection.shard)
            val next = (remaining[color] ?: 0) - 1
            if (next <= 0) remaining.remove(color) else remaining[color] = next
        }
        return cost.mapNotNull { (color, _) -> remaining[color]?.takeIf { it > 0 }?.let { color to it } }
    }

    private fun convokeAssignmentPlan(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        cost: List<Pair<ManaColor, Int>>,
        existingForgeIds: Set<ForgeCardId>,
    ): Map<ForgeCardId, ManaCostShard> {
        val candidates =
            pendingPrompt.request.candidateRefs.mapNotNull { ref ->
                val forgeId = ForgeCardId(ref.entityId)
                if (forgeId in existingForgeIds) return@mapNotNull null
                val card = ctx.bridge.findCard(forgeId) ?: return@mapNotNull null
                forgeId to card
            }
        return ConvokeShardAssigner
            .assign(candidates, ManaColorMapping.paymentShardCounts(cost)) { (_, card) -> card.color }
            .associate { (entry, shard) -> entry.first to shard }
    }

    private fun fallbackConvokeShard(
        color: forge.card.ColorSet,
        cost: List<Pair<ManaColor, Int>>,
    ): ManaCostShard? =
        ConvokeShardAssigner
            .assign(listOf(color), ManaColorMapping.paymentShardCounts(cost)) { it }
            .firstOrNull()
            ?.second

    private fun convokeCountPersistentAnnotations(pendingPrompt: InteractivePromptBridge.PendingPrompt): List<AnnotationInfo> {
        if (pendingPrompt.request.semantic != PromptSemantic.ConvokeCost) return emptyList()
        return ctx.bridge
            .seat(counters.seatId)
            .prompt
            .journal
            .activeConvokePayments()
            .mapNotNull { (sourceForgeCardId, payments) ->
                if (payments.isEmpty()) return@mapNotNull null
                val sourceIid = ctx.bridge.getOrAllocInstanceId(sourceForgeCardId)
                AnnotationBuilder.abilityWordActive(
                    instanceId = sourceIid,
                    abilityWordName = "ConvokeCount",
                    value = payments.size,
                    abilityGrpId = GrpId(KeywordAbilityIds.CONVOKE),
                )
            }
    }

    private fun mapSelectedInstanceIdsToPromptIndices(
        selectedInstanceIds: List<Int>,
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ): List<Int> =
        PromptResponseSubmitter.mapSelectNIdsToPromptIndices(selectedInstanceIds, pendingPrompt) { instanceId ->
            ctx.bridge.getForgeCardId(InstanceId(instanceId))
        }

    private fun manaSourceLabel(semantic: PromptSemantic): String =
        when {
            semantic == PromptSemantic.ConvokeCost -> "Convoke"
            semantic == PromptSemantic.ImproviseCost -> "Improvise"
            semantic == PromptSemantic.WaterbendCost -> "Waterbend"
            else -> "ManaSource"
        }

    private fun paymentKeywordGrpId(semantic: PromptSemantic): Int =
        when {
            semantic == PromptSemantic.ImproviseCost -> KeywordAbilityIds.IMPROVISE
            else -> KeywordAbilityIds.CONVOKE
        }

    private fun paymentAbilityGrpId(semantic: PromptSemantic): Int =
        when {
            semantic == PromptSemantic.ImproviseCost -> KeywordAbilityIds.IMPROVISE
            else -> KeywordAbilityIds.CONVOKE_PAYMENT
        }

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
