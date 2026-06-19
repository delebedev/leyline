package leyline.match

import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import leyline.DevCheck
import leyline.bridge.coord.ConvokeShardAssigner
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.PayCostsPromptRoute
import leyline.game.bundle.RequestBuilder
import leyline.game.bundle.SelectNEnvelope
import leyline.game.bundle.SelectNPromptRoutes
import leyline.game.codes.ManaColorMapping
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.mapping.SearchShape
import leyline.game.mapping.ZoneIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles targeting-related client messages and prompt detection.
 *
 * Protocol sequencing uses the shared
 * [MessageCounter][leyline.game.bundle.MessageCounter] via `counters.counter` —
 * no seeding or syncing needed.
 */
class TargetingHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val tracer: SessionTracer,
    private val bundles: BundleBuilderHolder,
    private val ctx: SessionContext,
) {
    private val manaSourcePaymentSelections = mutableMapOf<String, LinkedHashSet<Int>>()
    private val convokePaymentSelections = mutableMapOf<String, List<ConvokePaymentSelection>>()

    private data class ConvokePaymentSelection(
        val forgeCardId: ForgeCardId,
        val shard: ManaCostShard,
    )

    companion object {
        /** Stash optional cost indices after client response — writes to journal only. */
        fun stashOptionalCostIndices(
            prompt: InteractivePromptBridge,
            indices: List<Int>,
        ) {
            prompt.journal.record(PromptSideEffect.OptionalCostStash(indices))
        }

        internal fun mapSelectNIdsToPromptIndices(
            selectedIds: List<Int>,
            pendingPrompt: InteractivePromptBridge.PendingPrompt,
            resolveForgeCardId: (Int) -> ForgeCardId?,
        ): List<Int> =
            if (pendingPrompt.request.staticOptionIds.isNotEmpty()) {
                selectedIds
                    .map { staticId -> pendingPrompt.request.staticOptionIds.indexOf(staticId) }
                    .filter { it >= 0 }
            } else {
                selectedIds
                    .mapNotNull { instanceId ->
                        val cardId = resolveForgeCardId(instanceId) ?: return@mapNotNull null
                        pendingPrompt.request.candidateRefs
                            .firstOrNull { it.entityId == cardId.value }
                            ?.index
                    }.filter { it >= 0 }
            }

        internal fun choiceResultSideEffects(
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

    private val log = LoggerFactory.getLogger(TargetingHandler::class.java)

    @Volatile
    private var pendingInteraction: PendingClientInteraction? = null

    private var autoSubmittedTargetPromptId: String? = null

    /** Clear targeting state for puzzle hot-swap. */
    fun reset() {
        pendingInteraction = null
        autoSubmittedTargetPromptId = null
    }

    /**
     * Handle SelectTargetsResp (phase 1): store selection, send echo-back re-prompt.
     *
     * Does NOT submit to engine — waits for [onSubmitTargets] (SubmitTargetsReq).
     * The echo-back re-prompt reflects the selection per client wire spec:
     * only selected targets, legalAction=Unselect, selectedTargets count set.
     *
     * Player targets use seatId (1/2) as instanceId.
     */
    fun onSelectTargets(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val seatBridge = bridge.seat(counters.seatId)
        val resp = greMsg.selectTargetsResp
        val pendingPrompt =
            seatBridge.prompt.getPendingPrompt() ?: run {
                // Race: bridge prompt timed out and was cleared, then the late
                // client response arrives. Pre-fix this crashed the match in
                // strict mode (the client gets stuck on a "waiting for the
                // server" overlay). Downgraded to failOnAutoPass — caught in
                // tests with strict_pass=true, soft-dropped in dev. See
                // leyline-xejz for the proper fix.
                log.warn("TargetingHandler: SelectTargetsResp but no pending prompt (likely timeout race)")
                DevCheck.failOnAutoPass { "SelectTargetsResp but no pending prompt" }
                return
            }

        // Client sends one tap per SelectTargetsResp (Select_a1ad = add, Unselect = remove).
        // Accumulate across taps until SubmitTargetsReq finalizes the selection.
        val existing =
            (pendingInteraction as? PendingClientInteraction.TargetSelection)
                ?.takeIf { it.promptId == pendingPrompt.promptId }
                ?.selectedInstanceIds
                .orEmpty()

        val accumulated = existing.toMutableList()
        for (target in resp.target.targetsList) {
            val iid = target.targetInstanceId
            if (target.legalAction == SelectAction.Unselect) {
                accumulated.remove(iid)
            } else if (iid !in accumulated) {
                accumulated.add(iid)
            }
        }
        val selectedInstanceIds: List<Int> = accumulated

        val selectedIndices =
            selectedInstanceIds
                .mapNotNull { instanceId ->
                    val playerIdx = resolvePlayerTarget(instanceId, pendingPrompt)
                    if (playerIdx != null) return@mapNotNull playerIdx
                    val cardId = bridge.getForgeCardId(InstanceId(instanceId)) ?: return@mapNotNull null
                    // MUST filter by kind=="card" — Forge Card.id and Player.id share
                    // the same int space and frequently collide (e.g. card.id=1 and
                    // opp player.id=1). Without the kind guard, indexOfFirst hits the
                    // wrong candidate (saw bolt-on-Gixian register as bolt-on-opponent).
                    pendingPrompt.request.candidateRefs
                        .firstOrNull {
                            it.kind == "card" && it.entityId == cardId.value
                        }?.index ?: -1
                }.filter { it >= 0 }

        log.info(
            "TargetingHandler: SelectTargetsResp tap={} accumulated iids={} indices={} (awaiting SubmitTargetsReq)",
            resp.target.targetsList.map { "${it.targetInstanceId}:${it.legalAction}" },
            selectedInstanceIds,
            selectedIndices,
        )

        pendingInteraction =
            PendingClientInteraction.TargetSelection(
                promptId = pendingPrompt.promptId,
                selectedIndices = selectedIndices,
                selectedInstanceIds = selectedInstanceIds,
                sourceEntityId = pendingPrompt.request.sourceEntityId ?: 0,
            )

        val pending = pendingInteraction as PendingClientInteraction.TargetSelection
        if (shouldSubmitSingleTargetTrigger(pendingPrompt, selectedIndices)) {
            log.info("TargetingHandler: mandatory triggered target selected — submitting indices={}", selectedIndices)
            submitTargetSelection(pending, autoPass, autoSubmitted = true)
            return
        }

        // Echo-back: actions-only GSM diff + re-prompt with selection reflected
        val echoDiff = bundles.bundleBuilder.buildEchoDiffGsm(counters.counter)
        val gsId = counters.counter.currentGsId()
        val rePrompt = RequestBuilder.buildSelectTargetsRePrompt(pendingPrompt, bridge, selectedInstanceIds, counters.seatId.value)
        val rePromptMsg =
            sink.makeGRE(GREMessageType.SelectTargetsReq_695e, gsId, counters.counter.nextMsgId()) {
                it.selectTargetsReq = rePrompt
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_TARGETS).build())
                it.allowCancel = AllowCancel.Abort
                it.allowUndo = true
            }
        Tap.outboundTemplate("SelectTargetsReq re-prompt seat=${counters.seatId}")
        sink.sendBundledGRE(listOf(echoDiff, rePromptMsg))
    }

    /**
     * Handle SubmitTargetsReq (phase 2): submit stored selection to engine.
     *
     * Type-only message (no payload). Uses selection stored by [onSelectTargets].
     */
    fun onSubmitTargets(autoPass: () -> Unit) {
        val pending = pendingInteraction as? PendingClientInteraction.TargetSelection
        if (pending == null) {
            val autoSubmittedPromptId = autoSubmittedTargetPromptId
            if (autoSubmittedPromptId != null) {
                autoSubmittedTargetPromptId = null
                log.debug(
                    "TargetingHandler: ignoring duplicate SubmitTargetsReq after auto-submitted target prompt {}",
                    autoSubmittedPromptId,
                )
                return
            }
            log.warn("TargetingHandler: SubmitTargetsReq but no pending target selection (likely timeout race)")
            DevCheck.failOnAutoPass { "SubmitTargetsReq but no pending target selection" }
            return
        }

        autoSubmittedTargetPromptId = null
        submitTargetSelection(pending, autoPass, autoSubmitted = false)
    }

    private fun shouldSubmitSingleTargetTrigger(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        selectedIndices: List<Int>,
    ): Boolean =
        pendingPrompt.request.isTriggeredAbility &&
            pendingPrompt.request.min == 1 &&
            pendingPrompt.request.max == 1 &&
            selectedIndices.size == 1

    private fun submitTargetSelection(
        pending: PendingClientInteraction.TargetSelection,
        autoPass: () -> Unit,
        autoSubmitted: Boolean,
    ) {
        val bridge = ctx.bridge
        pendingInteraction = null
        if (autoSubmitted) {
            autoSubmittedTargetPromptId = pending.promptId
        }

        log.info("TargetingHandler: submitting target indices={}", pending.selectedIndices)

        // Source iid for PSuT comes from the pending interaction (stashed at
        // SelectTargetsResp time), not from the bridge prompt — the bridge
        // prompt may have cleared between SelectTargetsResp and SubmitTargetsReq
        // (timeout / shutdown race) and reading null here would silently drop
        // PSuT from the wire while the engine still receives the response.
        if (pending.sourceEntityId != 0) {
            val spellIid = bridge.getOrAllocInstanceId(ForgeCardId(pending.sourceEntityId))
            bundles.bundleBuilder.cursor.queuePSuT(spellIid, counters.seatId)
        }

        sink.sendBundledGRE(
            listOf(
                sink.makeGRE(GREMessageType.SubmitTargetsResp_695e, counters.counter.currentGsId(), counters.counter.nextMsgId()) {
                    it.submitTargetsResp = SubmitTargetsResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        bridge.seat(counters.seatId).prompt.submitResponse(pending.promptId, pending.selectedIndices)
        bridge.awaitPriority()
        autoPass()
    }

    /**
     * Handle SelectNResp: map client instanceIds back to prompt option indices and submit.
     * Mirrors [onSelectTargets] but for "choose N cards" prompts.
     */
    fun onSelectN(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val seatBridge = bridge.seat(counters.seatId)
        val pendingPrompt =
            seatBridge.prompt.getPendingPrompt() ?: run {
                log.warn("TargetingHandler: SelectNResp but no pending prompt (likely timeout race)")
                DevCheck.failOnAutoPass { "SelectNResp but no pending prompt" }
                return
            }

        val selectedIds = greMsg.selectNResp.idsList
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(selectedIds, pendingPrompt)

        recordChoiceResults(pendingPrompt, selectedIds)

        log.info("TargetingHandler: SelectNResp indices={}", selectedIndices)

        seatBridge.prompt.submitResponse(pendingPrompt.promptId, selectedIndices)
        bridge.awaitPriority()
        autoPass()
    }

    fun onOrderResp(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val seatBridge = bridge.seat(counters.seatId)
        val pendingPrompt =
            seatBridge.prompt.getPendingPrompt() ?: run {
                log.warn("TargetingHandler: OrderResp but no pending prompt (likely timeout race)")
                DevCheck.failOnAutoPass { "OrderResp but no pending prompt" }
                return
            }

        val orderedIds = greMsg.orderResp.idsList
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(orderedIds, pendingPrompt)
        log.info("TargetingHandler: OrderResp ids={} indices={}", orderedIds, selectedIndices)

        seatBridge.prompt.submitResponse(pendingPrompt.promptId, selectedIndices)
        bridge.awaitPriority()
        autoPass()
    }

    fun onEffectCost(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val seatBridge = bridge.seat(counters.seatId)
        val pendingPrompt =
            seatBridge.prompt.getPendingPrompt() ?: run {
                log.warn("TargetingHandler: EffectCostResp but no pending prompt (likely timeout race)")
                DevCheck.failOnAutoPass { "EffectCostResp but no pending prompt" }
                return
            }

        val ids = greMsg.effectCostResp.costSelection.idsList
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(ids, pendingPrompt)
        if (isManaSourcePaymentSemantic(pendingPrompt.request.semantic)) {
            manaSourcePaymentSelections.remove(pendingPrompt.promptId)
            convokePaymentSelections.remove(pendingPrompt.promptId)
        }

        log.info("TargetingHandler: EffectCostResp indices={}", selectedIndices)

        seatBridge.prompt.submitResponse(pendingPrompt.promptId, selectedIndices)
        bridge.awaitPriority()
        autoPass()
    }

    /**
     * Native PayCostsReq mana-payment UIs answer through PerformActionResp.
     * Waterbend reducer clicks are MakePayment actions; the Done button is Pass.
     */
    fun tryHandlePayCostsPerformAction(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean {
        val actions = greMsg.performActionResp.actionsList
        if (actions.none { it.actionType == ActionType.MakePayment || it.actionType == ActionType.Pass }) {
            return false
        }

        val bridge = ctx.bridge
        val seatBridge = bridge.seat(counters.seatId)
        val pendingPrompt = seatBridge.prompt.getPendingPrompt() ?: return false
        if (!isManaSourcePaymentSemantic(pendingPrompt.request.semantic)) return false

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
                "TargetingHandler: {} MakePayment ids={} accumulated={}",
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

    private fun submitManaSourcePayment(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        selectedIds: List<Int>,
        autoPass: () -> Unit,
    ) {
        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(selectedIds, pendingPrompt)
        log.info(
            "TargetingHandler: {} payment ids={} indices={}",
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
        val remainingRefs = pendingPrompt.request.candidateRefs.filterNot { it.entityId in selectedForgeIds }
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
                    waterbendCostString = remainingManaCost.toArenaCostString().takeIf { it.isNotEmpty() },
                ),
        )
    }

    private fun recordConvokeSelections(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        selectedIds: List<Int>,
    ): List<ConvokePaymentSelection> {
        if (pendingPrompt.request.semantic != PromptSemantic.ConvokeCost) return emptyList()
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
        if (pendingPrompt.request.semantic != PromptSemantic.ConvokeCost || selections.isEmpty()) return
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
                                color = selection.shard.toConvokeWireColor().number,
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
        val remainingCost = reduceConvokeManaCost(pendingPrompt.request.waterbendManaCost, existing)
        val existingForgeIds = existing.map { it.forgeCardId }.toSet()
        val plan = convokeAssignmentPlan(pendingPrompt, remainingCost, existingForgeIds)
        return selectedIds.mapNotNull { iid ->
            val forgeId = ctx.bridge.getForgeCardId(InstanceId(iid)) ?: return@mapNotNull null
            val card = ctx.bridge.findCard(forgeId) ?: return@mapNotNull null
            val shard = plan[forgeId] ?: fallbackConvokeShard(card.color, remainingCost) ?: return@mapNotNull null
            ConvokePaymentSelection(forgeId, shard)
        }
    }

    private fun reduceConvokeManaCost(
        cost: List<Pair<ManaColor, Int>>,
        selections: List<ConvokePaymentSelection>,
    ): List<Pair<ManaColor, Int>> {
        val remaining = cost.associate { it.first to it.second }.toMutableMap()
        for (selection in selections) {
            val color = selection.shard.toConvokeCostColor()
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
            .assign(candidates, cost.toConvokeShardCounts()) { (_, card) -> card.color }
            .associate { (entry, shard) -> entry.first to shard }
    }

    private fun fallbackConvokeShard(
        color: forge.card.ColorSet,
        cost: List<Pair<ManaColor, Int>>,
    ): ManaCostShard? =
        ConvokeShardAssigner
            .assign(listOf(color), cost.toConvokeShardCounts()) { it }
            .firstOrNull()
            ?.second

    private fun List<Pair<ManaColor, Int>>.toConvokeShardCounts(): Map<ManaCostShard, Int> =
        buildMap {
            for ((color, count) in this@toConvokeShardCounts) {
                val shard = color.toConvokeShard() ?: continue
                put(shard, count)
            }
        }

    private fun ManaColor.toConvokeShard(): ManaCostShard? =
        when {
            this == ManaColor.White_afc9 -> ManaCostShard.WHITE
            this == ManaColor.Blue_afc9 -> ManaCostShard.BLUE
            this == ManaColor.Black_afc9 -> ManaCostShard.BLACK
            this == ManaColor.Red_afc9 -> ManaCostShard.RED
            this == ManaColor.Green_afc9 -> ManaCostShard.GREEN
            this == ManaColor.Generic -> ManaCostShard.GENERIC
            else -> null
        }

    private fun ManaCostShard.toConvokeWireColor(): ManaColor =
        if (this == ManaCostShard.WHITE) {
            ManaColor.White_afc9
        } else if (this == ManaCostShard.BLUE) {
            ManaColor.Blue_afc9
        } else if (this == ManaCostShard.BLACK) {
            ManaColor.Black_afc9
        } else if (this == ManaCostShard.RED) {
            ManaColor.Red_afc9
        } else if (this == ManaCostShard.GREEN) {
            ManaColor.Green_afc9
        } else {
            ManaColor.Colorless_afc9
        }

    private fun ManaCostShard.toConvokeCostColor(): ManaColor =
        if (this == ManaCostShard.GENERIC) ManaColor.Generic else toConvokeWireColor()

    private fun isManaSourcePaymentSemantic(semantic: PromptSemantic): Boolean =
        semantic == PromptSemantic.WaterbendCost || semantic == PromptSemantic.ConvokeCost

    private fun manaSourceLabel(semantic: PromptSemantic): String =
        when {
            semantic == PromptSemantic.ConvokeCost -> "Convoke"
            semantic == PromptSemantic.WaterbendCost -> "Waterbend"
            else -> "ManaSource"
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

    private fun List<Pair<ManaColor, Int>>.toArenaCostString(): String =
        joinToString(separator = "") { (color, count) ->
            when (color) {
                ManaColor.Generic -> "o$count"
                ManaColor.White_afc9 -> "oW".repeat(count)
                ManaColor.Blue_afc9 -> "oU".repeat(count)
                ManaColor.Black_afc9 -> "oB".repeat(count)
                ManaColor.Red_afc9 -> "oR".repeat(count)
                ManaColor.Green_afc9 -> "oG".repeat(count)
                else -> ""
            }
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

    /**
     * After a cast, check for a pending targeting prompt or intermediate stack state.
     * Returns true if handled (caller should return), false to continue normal flow.
     *
     * @param clientAutoResolve true when the client's autoPassOption signals
     *   "resolve my stack effects" — skips the stack prompt when the player has
     *   no meaningful responses, matching client behavior (#92).
     */
    @Suppress(
        "ReturnCount",
        // Sacrifice/ExileFromGrave route to PayCostsReq; everything else falls
        // through to SelectNReq. Exhaustive when would block adding new
        // Reason variants gracefully.
        "ElseCaseInsteadOfExhaustiveWhen",
    )
    fun handlePostCastPrompt(clientAutoResolve: Boolean = false): Boolean {
        val bridge = ctx.bridge
        val game = ctx.game
        val pendingPrompt = bridge.seat(counters.seatId).prompt.getPendingPrompt()
        if (pendingPrompt != null && sendClassifiedPrompt(PromptClassifier.classify(pendingPrompt), PromptDispatchContext.POST_CAST)) {
            return true
        }
        if (!game.stack.isEmpty) {
            // When auto-resolve is active and the player has no meaningful responses
            // (only Pass), skip the prompt — let autoPassAndAdvance() handle stack
            // resolution transparently, matching client behavior (#92).
            if (clientAutoResolve && BundleBuilder.shouldAutoPass(bundles.bundleBuilder.buildActions())) {
                return false
            }
            sink.sendRealGameState(bridge)
            return true
        }
        return false
    }

    /** Result from [checkPendingPrompt]. */
    enum class PromptResult {
        /** No prompt pending. */
        NONE,

        /** Targeting prompt sent to client — caller should exit loop and wait. */
        SENT_TO_CLIENT,

        /** Non-targeting prompt auto-resolved — caller should re-evaluate (loop continues). */
        AUTO_RESOLVED,
    }

    /**
     * Check for pending interactive prompt (targeting, sacrifice, discard, etc.).
     * - Targeting prompts (candidateRefs non-empty) → send SelectTargetsReq to client.
     * - Surveil/scry prompts → send GroupReq to client.
     * - Other non-targeting prompts (confirm, choose_cards, order) → auto-resolve with
     *   defaultIndex. Covers discard-to-hand-size at Cleanup and similar engine prompts.
     */
    fun checkPendingPrompt(): PromptResult {
        val bridge = ctx.bridge
        val game = ctx.game
        val seatBridge = bridge.seat(counters.seatId)
        val pendingPrompt = seatBridge.prompt.getPendingPrompt() ?: return PromptResult.NONE
        val classified = PromptClassifier.classify(pendingPrompt)

        return if (sendClassifiedPrompt(classified, PromptDispatchContext.PENDING_CHECK)) {
            PromptResult.SENT_TO_CLIENT
        } else {
            when (classified) {
                is ClassifiedPrompt.AutoResolve -> {
                    val req = pendingPrompt.request
                    // Multi-option generic prompts are real gameplay choices (for
                    // example, odd/even effects) unless a narrower semantic has
                    // classified them. Keep known safe defaults quiet, but make this
                    // path visible so simclient runs do not silently swallow decisions.
                    if (req.semantic == PromptSemantic.Generic && req.options.size > 1) {
                        log.warn(
                            "TargetingHandler: auto-resolving ambiguous non-targeting prompt [{}] " +
                                "semantic={} message=\"{}\" opts={} labels={} default={} sourceEntityId={} modalSource={}",
                            req.promptType,
                            req.semantic,
                            req.message,
                            req.options.size,
                            req.options,
                            req.defaultIndex,
                            req.sourceEntityId,
                            req.modalSourceCardName,
                        )
                    } else {
                        log.info(
                            "TargetingHandler: auto-resolving non-targeting prompt [{}] \"{}\" opts={} default={}",
                            req.promptType,
                            req.message,
                            req.options.size,
                            req.defaultIndex,
                        )
                    }
                    tracer.traceEvent(
                        MatchEventType.AUTO_PASS,
                        game,
                        "auto-resolve prompt [${req.promptType}] default=${req.defaultIndex}",
                    )
                    seatBridge.prompt.submitResponse(pendingPrompt.promptId, listOf(req.defaultIndex))
                    bridge.awaitPriority()
                    PromptResult.AUTO_RESOLVED
                }

                is ClassifiedPrompt.Grouping,
                is ClassifiedPrompt.ModalChoice,
                is ClassifiedPrompt.Order,
                is ClassifiedPrompt.Search,
                is ClassifiedPrompt.SelectN,
                is ClassifiedPrompt.Targeting,
                -> PromptResult.NONE
            }
        }
    }

    private enum class PromptDispatchContext {
        POST_CAST,
        PENDING_CHECK,
    }

    private fun sendClassifiedPrompt(
        classified: ClassifiedPrompt,
        context: PromptDispatchContext,
    ): Boolean {
        val pendingPrompt = classified.pendingPrompt
        return when (classified) {
            is ClassifiedPrompt.Grouping -> {
                if (context != PromptDispatchContext.PENDING_CHECK) return false
                sendGroupReqForSurveilScry(classified.pendingPrompt, classified.context)
                true
            }

            is ClassifiedPrompt.ModalChoice -> {
                val prefix = if (context == PromptDispatchContext.POST_CAST) "post-cast modal" else "modal"
                tracer.traceEvent(MatchEventType.TARGET_PROMPT, ctx.game, "$prefix: ${pendingPrompt.request.message}")
                sendCastingTimeOptionsReq(classified.pendingPrompt)
                true
            }

            is ClassifiedPrompt.SelectN -> {
                val label =
                    if (context == PromptDispatchContext.POST_CAST) {
                        "post-cast selectN reason=${classified.reason} candidates=${pendingPrompt.request.candidateRefs.size}"
                    } else {
                        "select_n(${classified.reason}) candidates=${pendingPrompt.request.candidateRefs.size}"
                    }
                tracer.traceEvent(
                    MatchEventType.TARGET_PROMPT,
                    ctx.game,
                    label,
                )
                sendSelectNPrompt(classified.pendingPrompt, classified.reason)
                true
            }

            is ClassifiedPrompt.Targeting -> {
                val prefix = if (context == PromptDispatchContext.POST_CAST) "cast-target targets" else "targets"
                tracer.traceEvent(MatchEventType.TARGET_PROMPT, ctx.game, "$prefix=${pendingPrompt.request.candidateRefs.size}")
                sendSelectTargetsReq(classified.pendingPrompt)
                true
            }

            is ClassifiedPrompt.Search -> {
                val label =
                    if (context == PromptDispatchContext.POST_CAST) {
                        "post-cast search"
                    } else {
                        "search: ${pendingPrompt.request.message}"
                    }
                tracer.traceEvent(MatchEventType.TARGET_PROMPT, ctx.game, label)
                sendSearchReq(classified.pendingPrompt)
                true
            }

            is ClassifiedPrompt.Order -> {
                tracer.traceEvent(MatchEventType.TARGET_PROMPT, ctx.game, "order: ${pendingPrompt.request.message}")
                sendOrderReq(classified.pendingPrompt)
                true
            }

            is ClassifiedPrompt.AutoResolve -> false
        }
    }

    private fun sendSelectNPrompt(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        reason: SelectNReason,
    ) {
        SelectNPromptRoutes.payCosts(pendingPrompt.request.semantic)?.let { route ->
            sendPayCostsReq(pendingPrompt, route)
            return
        }

        when (reason) {
            SelectNReason.LegendRule,
            SelectNReason.Discard,
            SelectNReason.SacrificeEffect,
            SelectNReason.RevealChoose,
            SelectNReason.Resolution,
            SelectNReason.SuspectChoice,
            SelectNReason.LibraryPutback,
            SelectNReason.MutateTopBottom,
            SelectNReason.LearnLesson,
            SelectNReason.StaticColorChoice,
            SelectNReason.StaticSubtypeChoice,
            SelectNReason.StaticParityChoice,
            -> sendSelectNReq(pendingPrompt, reason)
            SelectNReason.Sacrifice,
            SelectNReason.ExileFromGrave,
            SelectNReason.CollectEvidenceCost,
            SelectNReason.EnlistCost,
            SelectNReason.StationTapCost,
            SelectNReason.ReturnUnblockedAttackerCost,
            SelectNReason.ConvokeCost,
            SelectNReason.WaterbendCost,
            -> error("missing PayCosts route for ${pendingPrompt.request.semantic}")
        }
    }

    /**
     * Handle GroupResp for surveil/scry: translate client grouping back to prompt indices.
     *
     * Arena sends GroupResp with 2 groups:
     *   - Group 0 (Library/Top): cards to keep on top
     *   - Group 1 (Graveyard or Library/Bottom): cards to send away
     *
     * For single-card surveil: group 0 non-empty → index 0 (keep), group 1 non-empty → index 1 (graveyard).
     * For multi-card: group 1 IDs → indices of cards chosen for "away" zone.
     */
    fun onGroupResp(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val seatBridge = bridge.seat(counters.seatId)
        val pendingPrompt =
            seatBridge.prompt.getPendingPrompt() ?: run {
                log.warn("TargetingHandler: GroupResp but no pending prompt (likely timeout race)")
                DevCheck.failOnAutoPass { "GroupResp but no pending prompt" }
                return
            }

        val groups = greMsg.groupResp.groupsList
        val req = pendingPrompt.request
        val classified = PromptClassifier.classify(pendingPrompt)

        val selectedIndices =
            when (classified) {
                is ClassifiedPrompt.Grouping -> {
                    val topIds = groups.getOrNull(0)?.idsList.orEmpty()
                    val awayIds = groups.getOrNull(1)?.idsList.orEmpty()
                    bridge.recordLibraryArrangement(counters.seatId, classified.context, topIds, awayIds)

                    if (req.max == 1 && req.options.size == 2) {
                        // Single-card surveil/scry: "Top of library" (0) vs "Graveyard"/"Bottom" (1)
                        // Group 1 (away zone) has the card → user chose "away" → index 1
                        if (awayIds.isNotEmpty()) {
                            listOf(1) // away (graveyard for surveil, bottom for scry)
                        } else {
                            listOf(0) // keep on top
                        }
                    } else {
                        // Multi-card surveil/scry: away group IDs → indices into options
                        awayIds
                            .mapNotNull { iid ->
                                val cardId = bridge.getForgeCardId(InstanceId(iid)) ?: return@mapNotNull null
                                // Cards may be zoneless during surveil — use game.findById
                                // instead of player.allCards (which only sees zoned cards).
                                val card = ctx.game.findById(cardId.value) ?: return@mapNotNull null
                                req.options.indexOf(card.name)
                            }.filter { it >= 0 }
                    }
                }

                is ClassifiedPrompt.AutoResolve,
                is ClassifiedPrompt.ModalChoice,
                is ClassifiedPrompt.Order,
                is ClassifiedPrompt.Search,
                is ClassifiedPrompt.SelectN,
                is ClassifiedPrompt.Targeting,
                -> listOf(req.defaultIndex)
            }

        log.info("TargetingHandler: GroupResp → prompt indices={}", selectedIndices)

        seatBridge.prompt.submitResponse(pendingPrompt.promptId, selectedIndices)
        bridge.awaitPriority()
        autoPass()
    }

    /**
     * Handle CancelActionReq: player backed out of targeting (cancel spell cast).
     *
     * Submits an empty target list to the pending prompt. The engine interprets
     * empty indices as "no targets chosen" → `TargetSelectionResult(false, false)`
     * → spell targeting fails → engine unwinds the cast (removes from stack,
     * returns mana). We then resend the game state so the client sees the
     * board return to pre-cast state with available actions.
     */
    fun onCancelAction(autoPass: () -> Unit) {
        val bridge = ctx.bridge
        when (pendingInteraction) {
            is PendingClientInteraction.OptionalCost,
            is PendingClientInteraction.AlternateCostChoice,
            is PendingClientInteraction.HybridManaType,
            -> {
                pendingInteraction = null
                bridge
                    .seat(counters.seatId)
                    .prompt
                    .journal
                    .clearHybridManaStash()
                log.info("TargetingHandler: CancelActionReq — cancelling deferred cast before engine submit")
                autoPass()
                return
            }

            is PendingClientInteraction.ModalChoice,
            is PendingClientInteraction.Search,
            is PendingClientInteraction.TargetSelection,
            null,
            -> Unit
        }

        val seatBridge = bridge.seat(counters.seatId)
        val pendingPrompt = seatBridge.prompt.getPendingPrompt()
        if (pendingPrompt == null) {
            log.warn("TargetingHandler: CancelActionReq but no pending prompt (likely timeout race)")
            DevCheck.failOnAutoPass { "CancelActionReq but no pending prompt" }
            return
        }

        if (isManaSourcePaymentSemantic(pendingPrompt.request.semantic)) {
            val ids = manaSourcePaymentSelections.remove(pendingPrompt.promptId)?.toList().orEmpty()
            convokePaymentSelections.remove(pendingPrompt.promptId)
            if (ids.isNotEmpty()) {
                log.info(
                    "TargetingHandler: CancelActionReq — completing {} payment ids={}",
                    manaSourceLabel(pendingPrompt.request.semantic),
                    ids,
                )
                submitManaSourcePayment(pendingPrompt, ids, autoPass)
                return
            }
        }

        log.info("TargetingHandler: CancelActionReq — submitting empty targets to unwind spell")

        // Submit empty list → engine sees no targets → spell fails → unwind
        seatBridge.prompt.submitResponse(pendingPrompt.promptId, emptyList())
        bridge.awaitPriority()
        autoPass()
    }

    /**
     * Handle SearchResp: resolve the pending search prompt with the client's choice.
     *
     * @param itemsFound instanceIds the client selected (from SearchResp.itemsFound).
     *        Empty = player declined ("fail to find").
     */
    fun onSearchResp(
        itemsFound: List<Int>,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val pending =
            pendingInteraction as? PendingClientInteraction.Search ?: run {
                log.warn("SearchResp received but no search pending (likely timeout race)")
                DevCheck.failOnAutoPass { "SearchResp but no search pending" }
                return
            }
        pendingInteraction = null

        val seatBridge = bridge.seat(counters.seatId)
        val prompt = seatBridge.prompt.getPendingPrompt()
        if (prompt != null && prompt.promptId == pending.promptId) {
            val responseIndices =
                if (itemsFound.isEmpty()) {
                    // Declined — submit index past the last option (= "none")
                    log.info("SearchResp: player declined (fail to find)")
                    listOf(prompt.request.options.size)
                } else {
                    itemsFound.map { chosenInstanceId ->
                        val cardId = bridge.getForgeCardId(InstanceId(chosenInstanceId))
                        val idx =
                            if (cardId != null) {
                                prompt.request.candidateRefs
                                    .firstOrNull { it.entityId == cardId.value }
                                    ?.index ?: -1
                            } else {
                                -1
                            }
                        if (idx >= 0) {
                            log.info("SearchResp: player chose instanceId={} → prompt index {}", chosenInstanceId, idx)
                            idx
                        } else {
                            log.warn("SearchResp: instanceId={} not found in candidates, using default", chosenInstanceId)
                            DevCheck.fail { "SearchResp: instanceId=$chosenInstanceId not in candidates" }
                            prompt.request.defaultIndex
                        }
                    }
                }
            seatBridge.prompt.submitResponse(pending.promptId, responseIndices)
            bridge.awaitPriority()
            drainPendingPlayback()
        }
        // Diff baseline is invalid post library-search — revealed objects must
        // vanish next bundle; see BundleCursor.invalidate KDoc (#42).
        bundles.bundleBuilder.cursor.invalidate()
        sink.sendRealGameState(bridge)
        autoPass()
    }

    // --- Helpers ---

    /**
     * Resolve a player target: if [instanceId] is a seatId (1 or 2), find the
     * matching `kind="player"` candidateRef in the pending prompt.
     * Returns the candidateRef index, or null if this isn't a player target.
     */
    private fun resolvePlayerTarget(
        instanceId: Int,
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ): Int? {
        // Arena uses seatId as instanceId for player targets (1 or 2)
        val player = ctx.bridge.getPlayer(SeatId(instanceId)) ?: return null
        val idx =
            pendingPrompt.request.candidateRefs
                .firstOrNull {
                    it.kind == "player" && it.entityId == player.id
                }?.index
        if (idx != null) return idx

        val seatIdx =
            pendingPrompt.request.candidateRefs
                .firstOrNull {
                    it.kind == "player" && it.entityId == instanceId
                }?.index
        return seatIdx
    }

    /**
     * Build and send CastingTimeOptionsReq for a modal prompt.
     * Looks up card grpId and modal option grpIds from CardRepository,
     * saves PendingModal state for response mapping.
     */
    @Suppress("LongMethod") // Sequential CTO assembly: lookup → translate → build → bundle. Splitting hides the data-flow.
    private fun sendCastingTimeOptionsReq(pendingPrompt: InteractivePromptBridge.PendingPrompt) {
        val bridge = ctx.bridge
        val game = ctx.game
        val req = pendingPrompt.request
        val cardName = req.modalSourceCardName
        if (cardName == null) {
            log.warn("TargetingHandler: modal prompt but no modalSourceCardName, auto-resolving")
            DevCheck.fail { "modal prompt but no modalSourceCardName" }
            autoResolvePrompt(pendingPrompt)
            return
        }

        // Look up card grpId and modal options
        val cardGrpId = bridge.cardRepository.findGrpIdByName(cardName)
        if (cardGrpId == null) {
            log.warn("TargetingHandler: card '{}' not in card DB, auto-resolving modal", cardName)
            DevCheck.fail { "modal card '$cardName' not in card DB" }
            autoResolvePrompt(pendingPrompt)
            return
        }

        val modalInfo = bridge.cardRepository.lookupModalOptions(cardGrpId)
        if (modalInfo == null) {
            log.warn("TargetingHandler: no modal options for grpId={}, auto-resolving", cardGrpId)
            DevCheck.fail { "no modal options for card '$cardName' grpId=$cardGrpId" }
            autoResolvePrompt(pendingPrompt)
            return
        }

        // For triggered abilities (ETB modals), the protocol references the
        // ability object on the stack, not the source card.
        val isTriggered = req.isTriggeredAbility
        val sourceInstanceId: Int
        val ctoGrpId: Int
        val ctoId: Int
        if (isTriggered && req.sourceEntityId != null) {
            // SA-id-keyed surrogate matches the iid the StateMapper emits on
            // the matching AbilityInstanceCreated; falls back to the legacy
            // source-card-keyed scheme when the producer didn't surface
            // forgeAbilityId (e.g. activated-ability modal).
            val surrogate =
                if (req.forgeAbilityId != 0) {
                    FrameIdResolver.triggerStackAbilityForgeId(req.forgeAbilityId)
                } else {
                    FrameIdResolver.stackAbilityForgeId(ForgeCardId(req.sourceEntityId))
                }
            sourceInstanceId = bridge.getOrAllocInstanceId(surrogate).value
            ctoGrpId = modalInfo.parentGrpId
            ctoId = 2
        } else {
            sourceInstanceId =
                if (req.sourceEntityId != null) {
                    bridge.getOrAllocInstanceId(ForgeCardId(req.sourceEntityId)).value
                } else {
                    0
                }
            ctoGrpId = cardGrpId
            ctoId = 2
        }

        // Resolve per-mode grpIds. When the bridge supplies full-list indices
        // (Spree path, and any Charm cast where Forge filtered at least one
        // mode), translate via card-DB childGrpIds — keeps the modal ordering
        // aligned with `possible[]` upstream. Otherwise fall back to unfiltered
        // (legacy Charm-with-all-modes-legal path).
        val possibleFullIndices = req.modalChoicePossibleFullIndices
        val excludedFullIndices = req.excludedModalFullIndices
        val effectiveChildGrpIds: List<Int>
        val effectiveModalCosts: List<List<Pair<ManaColor, Int>>>?
        val effectiveExcludedGrpIds: List<Int>
        val effectiveExcludedCosts: List<List<Pair<ManaColor, Int>>>
        if (possibleFullIndices != null && possibleFullIndices.all { it in modalInfo.childGrpIds.indices }) {
            effectiveChildGrpIds = possibleFullIndices.map { modalInfo.childGrpIds[it] }
            effectiveModalCosts = req.modalCosts
            effectiveExcludedGrpIds =
                excludedFullIndices
                    ?.filter { it in modalInfo.childGrpIds.indices }
                    ?.map { modalInfo.childGrpIds[it] }
                    ?: emptyList()
            effectiveExcludedCosts = req.excludedModalCosts ?: emptyList()
        } else {
            // Silent fallback: bridge populated full-list indices but they fell
            // outside card-DB childGrpIds (Forge SVar count vs. card-DB
            // modalChildIds count drift). Spree-style picked-mode mapping will
            // regress here. Loud in tests, soft in prod.
            if (possibleFullIndices != null) {
                DevCheck.fail {
                    "modal full-list indices out of card-DB range: " +
                        "indices=$possibleFullIndices childCount=${modalInfo.childGrpIds.size} " +
                        "card='$cardName' grpId=$cardGrpId"
                }
            }
            effectiveChildGrpIds = modalInfo.childGrpIds
            effectiveModalCosts = null
            effectiveExcludedGrpIds = emptyList()
            effectiveExcludedCosts = emptyList()
        }

        val ctoReq =
            bundles.bundleBuilder.buildModalCastingTimeOptionsReq(
                parentGrpId = modalInfo.parentGrpId,
                childGrpIds = effectiveChildGrpIds,
                modalCosts = effectiveModalCosts,
                excludedGrpIds = effectiveExcludedGrpIds,
                excludedCosts = effectiveExcludedCosts,
                minSel = req.min,
                maxSel = req.max,
                sourceInstanceId = sourceInstanceId,
                grpId = ctoGrpId,
                ctoId = ctoId,
                playerIdToPrompt = if (isTriggered) counters.seatId.value else null,
            )

        // Save pending state for response mapping. Store the *effective* child
        // grpIds so `onCastingTimeOptions`'s `indexOf(pickedGrpId)` returns an
        // index that aligns with `possible[]` upstream — not an unfiltered index.
        pendingInteraction =
            PendingClientInteraction.ModalChoice(
                pendingPrompt.promptId,
                effectiveChildGrpIds,
                stackAbilityInstanceId = sourceInstanceId.takeIf { isTriggered && it > 0 },
            )

        // For triggered abilities, pass the source card's instanceId and grpId so the
        // synthesized ability object has correct parentId and objectSourceGrpId.
        val cardInstanceId =
            if (isTriggered && req.sourceEntityId != null) {
                bridge.getOrAllocInstanceId(ForgeCardId(req.sourceEntityId)).value
            } else {
                null
            }

        val result =
            bundles.bundleBuilder.castingTimeOptionsBundle(
                game,
                counters.counter,
                ctoReq,
                sourceCardInstanceId = cardInstanceId,
                sourceCardGrpId = if (isTriggered) cardGrpId else null,
            )
        Tap.outboundTemplate("CastingTimeOptionsReq seat=${counters.seatId} card=$cardName")
        sink.sendBundledGRE(result.messages)
    }

    /**
     * Handle CastingTimeOptionsResp: dispatches to modal or kicker/optional cost handler.
     */
    fun onCastingTimeOptions(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        when (val pending = pendingInteraction) {
            is PendingClientInteraction.AlternateCostChoice -> {
                pendingInteraction = null
                onAlternateCostChoiceResponse(greMsg, pending, autoPass)
                return
            }

            is PendingClientInteraction.OptionalCost -> {
                pendingInteraction = null
                onOptionalCostResponse(greMsg, pending, autoPass)
                return
            }

            is PendingClientInteraction.HybridManaType -> {
                pendingInteraction = null
                onHybridManaTypeResponse(greMsg, pending, autoPass)
                return
            }

            is PendingClientInteraction.ModalChoice -> {
                val resp = greMsg.castingTimeOptionsResp
                val chosenGrpIds = resp.castingTimeOptionResp.chooseModalResp.grpIdsList

                val selectedIndices =
                    chosenGrpIds.mapNotNull { grpId ->
                        pending.childGrpIds.indexOf(grpId).takeIf { it >= 0 }
                    }

                log.info("TargetingHandler: CastingTimeOptionsResp (modal) grpIds={} → indices={}", chosenGrpIds, selectedIndices)

                bridge.seat(counters.seatId).prompt.submitResponse(pending.promptId, selectedIndices)
                pendingInteraction = null
                bridge.awaitPriority()
                autoPass()
                pending.stackAbilityInstanceId?.let { abilityIid ->
                    sink.sendBundledGRE(listOf(bundles.bundleBuilder.modalStackCleanup(counters.counter, abilityIid)))
                }
            }

            else -> {
                log.warn("TargetingHandler: CastingTimeOptionsResp but no pending modal or optional cost (likely timeout race)")
                DevCheck.failOnAutoPass { "CastingTimeOptionsResp but no pending modal or optional cost" }
            }
        }
    }

    fun checkHybridManaTypeOptions(
        action: Action,
        pendingActionId: String,
        castAbilityIndex: Int?,
    ): Boolean {
        if (action.alternativeGrpId != 0) return false
        val bridge = ctx.bridge
        val game = ctx.game
        val seatBridge = bridge.seat(counters.seatId)
        seatBridge.prompt.journal.clearHybridManaStash()

        val cardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return false
        val card = game.findById(cardId.value) ?: return false
        val player = bridge.getPlayer(counters.seatId) ?: return false
        val castable = getAllCastableAbilities(card, player)
        val sa = castAbilityIndex?.let { castable.getOrNull(it) } ?: castable.firstOrNull() ?: return false
        sa.setActivatingPlayer(player)
        val effectiveCost = ActionMapper.computeEffectiveCost(sa, player) ?: return false
        val paymentColors = effectiveCost.hybridOrTwoGenericColors()
        if (paymentColors.isEmpty()) return false
        val baseCost = sa.payCosts?.totalMana
        val promptCost = baseCost?.takeIf { it.hybridOrTwoGenericColors().size == paymentColors.size } ?: effectiveCost
        val promptColors = promptCost.hybridOrTwoGenericColors()

        val (ctoReq, ctoIds) =
            bundles.bundleBuilder.buildManaTypeCastingTimeOptionsReq(
                instanceId = action.instanceId,
                grpId = action.grpId,
                playerIdToPrompt = counters.seatId.value,
                hybridColors = promptColors,
                manaCost = promptCost.toManaRequirementSpecs(),
            )
        pendingInteraction =
            PendingClientInteraction.HybridManaType(
                pendingActionId = pendingActionId,
                action = PlayerAction.CastSpell(cardId, castAbilityIndex),
                clientAction = action,
                castAbilityIndex = castAbilityIndex,
                ctoIds = ctoIds,
                promptColors = promptColors,
                paymentColors = paymentColors,
            )

        val result = bundles.bundleBuilder.castingTimeOptionsBundle(game, counters.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq (hybrid mana type) seat=${counters.seatId} card=${card.name}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    /**
     * Check if a Cast action targets a card with optional costs (kicker, buyback, etc.).
     * If yes, sends CastingTimeOptionsReq to client and returns true (caller should NOT submit to engine).
     * If no, returns false (caller should proceed normally).
     */
    fun checkOptionalCosts(
        action: Action,
        pendingActionId: String,
        castAbilityIndex: Int?,
        preserveHybridStash: Boolean = false,
    ): Boolean {
        val bridge = ctx.bridge
        val game = ctx.game
        val cardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return false
        val card = game.findById(cardId.value) ?: return false

        val player = bridge.getPlayer(counters.seatId) ?: return false
        val castable = leyline.bridge.getAllCastableAbilities(card, player)
        val sa =
            castAbilityIndex?.let { castable.getOrNull(it) }
                ?: castable.firstOrNull()
                ?: return false
        sa.setActivatingPlayer(player)
        clearDeferredCastCostStashes(clearHybrid = !preserveHybridStash)

        val optionalCosts = forge.game.GameActionUtil.getOptionalCostValues(sa)
        // Keyword-with-cost keywords (Offspring, Casualty, Conspire) ride a separate
        // Forge dispatch path (`addKeywordCost` → `chooseNumberForKeywordCost`)
        // but the player-facing surface is identical to OptionalCost — pre-cast
        // yes/no on an extra mana cost. Surface them through the same CTO emit
        // here so the client renders one combined modal; pull decisions back out of
        // the journal in CostPaymentCoordinator when Forge prompts.
        val keywordCostEntries = collectKeywordCostEntries(card)
        if (optionalCosts.isEmpty() && keywordCostEntries.isEmpty()) return false

        log.info(
            "TargetingHandler: card '{}' has {} optional costs and {} keyword costs — sending prompt",
            card.name,
            optionalCosts.size,
            keywordCostEntries.size,
        )

        // Map each optional cost to (CastingTimeOptionType, abilityGrpId)
        val cardData = bridge.cardRepository.findByGrpId(action.grpId)
        // Keywords occupy the first N slots of CardData.abilityIds; optional costs
        // index past them. SlotLayout (from AbilityRegistry) is the source of
        // truth for keyword count.
        val keywordCount =
            if (cardData != null) {
                bridge.abilityRegistryFor(card, cardData)?.slotLayout?.keywordCount ?: 0
            } else {
                0
            }
        // Bargain / Casualty / Conspire have dedicated proto enums but the
        // client's renderer for those silently drops under our current
        // envelope — route through AdditionalCost (proto 5), which renders.
        // Tracked in leyline-zru7.
        val optionalCostEntries =
            optionalCosts.mapIndexed { i, cost ->
                val ctoType =
                    when (cost.type) {
                        forge.game.spellability.OptionalCost.Kicker1,
                        forge.game.spellability.OptionalCost.Kicker2,
                        -> CastingTimeOptionType.Kicker
                        else -> CastingTimeOptionType.AdditionalCost
                    }
                // Bargain has a keyword slot (universal id) AND a per-card
                // "If bargained..." conditional slot. The client expects the
                // keyword slot's grpId on the CTO entry.
                val abilityGrpId =
                    if (cost.type == forge.game.spellability.OptionalCost.Bargain) {
                        findKeywordSlot(card, "Bargain", keywordCount)
                            ?.let { cardData?.abilityIds?.getOrNull(it)?.first }
                            ?: 0
                    } else {
                        cardData
                            ?.abilityIds
                            ?.getOrNull(keywordCount + i)
                            ?.first ?: 0
                    }
                Pair(ctoType, abilityGrpId)
            }

        // Keyword slots come first in `abilityIds`, bounded by `keywordCount`
        // (SlotLayout source of truth). Anything beyond is an optional-cost
        // slot and shouldn't be matched as a keyword grpId.
        val keywordEntries =
            keywordCostEntries.mapNotNull { kw ->
                val slot = findKeywordSlot(card, kw.name, keywordCount) ?: return@mapNotNull null
                val abilityGrpId = cardData?.abilityIds?.getOrNull(slot)?.first ?: 0
                Triple(CastingTimeOptionType.AdditionalCost, abilityGrpId, kw.name)
            }

        val combinedCostEntries =
            optionalCostEntries + keywordEntries.map { (ctoType, gid, _) -> ctoType to gid }

        val (ctoReq, costCtoIds) =
            bundles.bundleBuilder.buildOptionalCostCastingTimeOptionsReq(
                instanceId = action.instanceId,
                optionalCosts = combinedCostEntries,
                playerIdToPrompt = counters.seatId.value,
                baseManaCost = cardData?.manaCost ?: emptyList(),
            )

        // Stash the Cast action for replay after response. Map the trailing
        // ctoIds (the keyword-cost ones) back to their keyword names so the
        // response handler knows where to journal each decision.
        val keywordCtoIdMap =
            keywordEntries
                .mapIndexed { idx, (_, _, kwName) ->
                    val ctoIdx = optionalCostEntries.size + idx
                    val ctoId = costCtoIds.getOrNull(ctoIdx) ?: return@mapIndexed null
                    ctoId to kwName
                }.filterNotNull()
                .toMap()

        pendingInteraction =
            PendingClientInteraction.OptionalCost(
                pendingActionId = pendingActionId,
                action = PlayerAction.CastSpell(cardId, castAbilityIndex),
                costCtoIds = costCtoIds,
                keywordCostsByCtoId = keywordCtoIdMap,
            )

        // Send prompt
        val result =
            bundles.bundleBuilder.castingTimeOptionsBundle(
                game,
                counters.counter,
                ctoReq,
            )
        Tap.outboundTemplate("CastingTimeOptionsReq (optional costs) seat=${counters.seatId} card=${card.name}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    fun checkAlternateAdditionalCostChoice(
        action: Action,
        pendingActionId: String,
    ): Boolean {
        val bridge = ctx.bridge
        val game = ctx.game
        val cardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return false
        val card = game.findById(cardId.value) ?: return false
        if (card.keywords.none { it.original.startsWith("AlternateAdditionalCost") }) return false

        val player = bridge.getPlayer(counters.seatId) ?: return false
        val castable = leyline.bridge.getAllCastableAbilities(card, player)
        if (castable.size <= 1) return false

        val optionPromptIds = alternateAdditionalCostPromptIds(castable)

        val (ctoReq, ctoIds) =
            bundles.bundleBuilder.buildChooseOrCostCastingTimeOptionsReq(
                instanceId = action.instanceId,
                grpId = action.grpId,
                optionCount = castable.size,
                optionPromptIds = optionPromptIds,
            )
        pendingInteraction =
            PendingClientInteraction.AlternateCostChoice(
                pendingActionId = pendingActionId,
                cardId = cardId,
                abilityIndicesByCtoId = ctoIds.mapIndexed { index, ctoId -> ctoId to index }.toMap(),
            )

        val result = bundles.bundleBuilder.castingTimeOptionsBundle(game, counters.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq (alternate additional cost) seat=${counters.seatId} card=${card.name}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    private fun alternateAdditionalCostPromptIds(castable: List<forge.game.spellability.SpellAbility>): List<Int> {
        val promptIds = castable.map { sa -> promptIdForAdditionalCostBranch(sa) }
        return if (promptIds.all { it != null }) promptIds.filterNotNull() else emptyList()
    }

    private fun promptIdForAdditionalCostBranch(sa: forge.game.spellability.SpellAbility): Int? {
        val costs = sa.payCosts ?: return null
        if (costs.isOnlyManaCost) return PromptIds.CHOOSE_OR_COST_PAY_MANA
        val costPartNames = costs.costParts.map { it.javaClass.simpleName }
        return when {
            costPartNames.any { it.contains("Sacrifice") } -> PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE
            costPartNames.any { it.contains("Exile") } -> PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE
            else -> null
        }
    }

    /**
     * Handle CastingTimeOptionsResp for optional costs (kicker, buyback, etc.).
     * Stores chosen cost indices, then submits the Cast action to the engine.
     */
    private fun onOptionalCostResponse(
        greMsg: ClientToGREMessage,
        pending: PendingClientInteraction.OptionalCost,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        // Check which optional costs the client chose
        val resp = greMsg.castingTimeOptionsResp
        val chosenCtoId = resp.castingTimeOptionResp?.ctoId ?: 0

        // ctoId=0 means Done (declined all costs)
        // ctoId>0 means accepted that cost
        val accepted = chosenCtoId != 0 && chosenCtoId in pending.costCtoIds
        // OptionalCost-enum entries occupy the first N ctoIds (1..N where N =
        // optionalCostCount); keyword-cost entries fill the trailing slots.
        // Only stash an OptionalCost-enum index when the picked ctoId lands in
        // that range — keyword-cost picks route through KeywordCostStash below.
        val isOptionalCostPick = accepted && chosenCtoId !in pending.keywordCostsByCtoId
        val acceptedIndices =
            if (isOptionalCostPick) {
                listOf(chosenCtoId - 1) // 1-based ctoId → 0-based index into optionalCosts
            } else {
                emptyList()
            }

        log.info(
            "TargetingHandler: optional cost response ctoId={} accepted={} indices={} keywordPick={}",
            chosenCtoId,
            accepted,
            acceptedIndices,
            chosenCtoId in pending.keywordCostsByCtoId,
        )

        // Stash decision for PlayerController.chooseOptionalCosts to read
        val seatBridge = bridge.seat(counters.seatId)
        stashOptionalCostIndices(seatBridge.prompt, acceptedIndices)

        // For keyword-cost entries (Offspring, Casualty, Conspire) record a
        // per-keyword pay/decline decision so
        // CostPaymentCoordinator.chooseKeywordCostBinary can answer when Forge
        // calls addKeywordCost during cost prep. Every known keyword gets an
        // entry — true if the player picked its ctoId, false otherwise.
        if (pending.keywordCostsByCtoId.isNotEmpty()) {
            val decisions =
                pending.keywordCostsByCtoId.entries.associate { (ctoId, kwName) ->
                    kwName to (chosenCtoId == ctoId)
                }
            seatBridge.prompt.journal.record(PromptSideEffect.KeywordCostStash(decisions))
            log.info("TargetingHandler: keyword cost decisions stashed: {}", decisions)
        }

        // Now submit the Cast action to the engine
        val actionBridge = seatBridge.action
        val pendingAction = actionBridge.getPending()
        if (pendingAction != null) {
            actionBridge.submitAction(pendingAction.actionId, pending.action)
            bridge.awaitPriority()
            autoPass()
        } else {
            log.warn("TargetingHandler: optional cost response but no pending engine action (likely timeout race)")
            DevCheck.failOnAutoPass { "optional cost response but no pending engine action" }
        }
    }

    private fun onHybridManaTypeResponse(
        greMsg: ClientToGREMessage,
        pending: PendingClientInteraction.HybridManaType,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val resp = greMsg.castingTimeOptionsResp
        val optionResponses =
            if (resp.castingTimeOptionRespsCount > 0) {
                resp.castingTimeOptionRespsList
            } else {
                listOf(resp.castingTimeOptionResp)
            }
        val byCtoId = optionResponses.associateBy { it.ctoId }
        val promptChoices =
            pending.ctoIds.mapIndexed { index, ctoId ->
                byCtoId[ctoId]
                    ?.takeIf { it.hasSelectManaTypeResp() }
                    ?.selectManaTypeResp
                    ?.manaColor
                    ?: optionResponses
                        .getOrNull(index)
                        ?.takeIf { it.hasSelectManaTypeResp() }
                        ?.selectManaTypeResp
                        ?.manaColor
                    ?: pending.promptColors.getOrNull(index)
                    ?: ManaColor.TwoGeneric
            }
        val choices = promptChoices.reorderHybridChoices(pending.promptColors, pending.paymentColors)
        val seatBridge = bridge.seat(counters.seatId)
        seatBridge.prompt.journal.record(PromptSideEffect.HybridManaStash(choices))
        log.info("TargetingHandler: hybrid mana type choices stashed: prompt={} payment={}", promptChoices, choices)

        if (checkOptionalCosts(pending.clientAction, pending.pendingActionId, pending.castAbilityIndex, preserveHybridStash = true)) {
            Tap.outboundTemplate("Cast deferred — optional cost prompt sent after hybrid mana type")
            return
        }

        val pendingAction = seatBridge.action.getPending()
        if (pendingAction != null) {
            seatBridge.action.submitAction(pendingAction.actionId, pending.action)
            bridge.awaitPriority()
            autoPass()
        } else {
            log.warn("TargetingHandler: hybrid mana response but no pending engine action (likely timeout race)")
            DevCheck.failOnAutoPass { "hybrid mana response but no pending engine action" }
        }
    }

    private fun clearDeferredCastCostStashes(clearHybrid: Boolean = true) {
        // Drop stale decisions from prior casts: each cast prompt overwrites
        // these stashes, and non-prompt casts must not inherit old choices.
        val journal =
            ctx.bridge
                .seat(counters.seatId)
                .prompt
                .journal
        journal.clearKeywordCostStash()
        if (clearHybrid) journal.clearHybridManaStash()
        journal.clearCollectEvidenceCost()
    }

    private fun ManaCost.hybridOrTwoGenericColors(): List<ManaColor> = mapNotNull { shard -> ManaColorMapping.fromOrTwoGenericShard(shard) }

    private fun List<ManaColor>.reorderHybridChoices(
        promptColors: List<ManaColor>,
        paymentColors: List<ManaColor>,
    ): List<ManaColor> {
        val used = BooleanArray(size)
        return paymentColors.map { paymentColor ->
            val promptIndex = promptColors.indices.firstOrNull { index -> !used[index] && promptColors[index] == paymentColor }
            if (promptIndex == null) {
                paymentColor
            } else {
                used[promptIndex] = true
                getOrNull(promptIndex) ?: paymentColor
            }
        }
    }

    private fun ManaCost.toManaRequirementSpecs(): List<BundleBuilder.ManaRequirementSpec> =
        buildList {
            for (shard in this@toManaRequirementSpecs) {
                val hybridColor = ManaColorMapping.fromOrTwoGenericShard(shard)
                val color = hybridColor ?: ManaColorMapping.fromShard(shard) ?: continue
                add(
                    BundleBuilder.ManaRequirementSpec(
                        colors = if (hybridColor != null) listOf(ManaColor.TwoGeneric, color) else listOf(color),
                    ),
                )
            }
            if (genericCost > 0) {
                add(BundleBuilder.ManaRequirementSpec(colors = listOf(ManaColor.Generic), count = genericCost))
            }
        }

    private fun onAlternateCostChoiceResponse(
        greMsg: ClientToGREMessage,
        pending: PendingClientInteraction.AlternateCostChoice,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val optionResp = greMsg.castingTimeOptionsResp.castingTimeOptionResp
        val selectedIndex = optionResp?.selectNResp?.idsList?.firstOrNull()
        val chosenCtoId = optionResp?.ctoId ?: 0
        val abilityIndex =
            if (selectedIndex != null) {
                pending.abilityIndicesByCtoId[selectedIndex] ?: 0
            } else {
                pending.abilityIndicesByCtoId[chosenCtoId] ?: 0
            }
        val seatBridge = bridge.seat(counters.seatId)
        val pendingAction = seatBridge.action.getPending()
        if (pendingAction != null) {
            seatBridge.action.submitAction(
                pendingAction.actionId,
                PlayerAction.CastSpell(pending.cardId, abilityIndex),
            )
            bridge.awaitPriority()
            autoPass()
        } else {
            log.warn("TargetingHandler: alternate cost choice response but no pending engine action (likely timeout race)")
            DevCheck.failOnAutoPass { "alternate cost choice response but no pending engine action" }
        }
    }

    private fun sendSearchReq(pendingPrompt: InteractivePromptBridge.PendingPrompt) {
        val bridge = ctx.bridge
        // Reveal library contents so the client can populate the search picker.
        // The GSM sent by sendRealGameState will include full card objects for the library.
        sink.sendRealGameState(bridge, revealForSeat = counters.seatId.value)

        // Extract search parameters from the Forge prompt.
        val req = pendingPrompt.request
        val player = bridge.getPlayer(counters.seatId)
        val library = player?.getZone(forge.game.zone.ZoneType.Library)
        val libZoneId = ZoneIds.libraryOf(counters.seatId)

        // All library card instanceIds
        val allLibIds =
            library?.cards?.map {
                bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value
            } ?: emptyList()

        // Valid search targets from candidateRefs (cards matching "basic land" filter)
        val validIds =
            req.candidateRefs.map { ref ->
                bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
            }

        // Source iid (searchReq.sourceId) — for activated-ability searches
        // (cycling, typecycling) the protocol expects the AB instance iid
        // here. Mint via the same SA-id-keyed surrogate the AbilityInstance
        // lifecycle uses so sourceId and AbilityInstanceCreated reference
        // the same iid. Spell searches fall back to the host card iid.
        //
        // Host card iid (prompt.parameters[0]) — names the source card so
        // the picker header reads the card name. Always the host card iid,
        // even for activated abilities (the AB iid lives in sourceId).
        //
        // Picker layout (promptId) — typecycling-shape searches use
        // SEARCH_TYPECYCLING (highlight every valid candidate, click-to-pick).
        // Generic tutors (Diabolic Tutor, Sylvan Ranger) use SEARCH.
        val stackTop = ctx.game.stack.firstOrNull()
        val sa = stackTop?.spellAbility
        val saId = sa?.id
        val isAbilityOnStack = stackTop?.isAbility == true
        val hostCardForgeId = sa?.hostCard?.id ?: req.sourceEntityId
        val hostCardIid =
            hostCardForgeId?.let { bridge.getOrAllocInstanceId(ForgeCardId(it)).value } ?: 0
        val sourceId =
            when {
                isAbilityOnStack && saId != null -> {
                    val abForgeId = FrameIdResolver.triggerStackAbilityForgeId(saId)
                    bridge.getOrAllocInstanceId(abForgeId).value
                }
                hostCardIid != 0 -> hostCardIid
                stackTop != null ->
                    bridge.getOrAllocInstanceId(ForgeCardId(stackTop.id)).value
                else -> 0
            }
        val promptId =
            if (isAbilityOnStack && SearchShape.isTypeCycling(sa)) {
                PromptIds.SEARCH_TYPECYCLING
            } else {
                PromptIds.SEARCH
            }

        val msgId = counters.counter.nextMsgId()
        val gsId = counters.counter.currentGsId()
        val msg =
            bundles.bundleBuilder.buildSearchReq(
                msgId = msgId,
                gsId = gsId,
                sourceInstanceId = sourceId,
                hostCardInstanceId = hostCardIid,
                searchingSeat = counters.seatId.value,
                libraryZoneId = libZoneId,
                allLibraryIds = allLibIds,
                validTargetIds = validIds,
                maxFind = req.max,
                allowFailToFind = req.min == 0,
                promptId = promptId,
            )
        sink.sendBundledGRE(listOf(msg))
        pendingInteraction = PendingClientInteraction.Search(pendingPrompt.promptId)
        log.info(
            "SearchReq sent: lib={} valid={} source={}, awaiting SearchResp",
            allLibIds.size,
            validIds.size,
            sourceId,
        )
    }

    private fun sendSelectTargetsReq(pendingPrompt: InteractivePromptBridge.PendingPrompt) {
        val result = bundles.bundleBuilder.selectTargetsBundle(ctx.game, counters.counter, pendingPrompt)
        Tap.outboundTemplate("SelectTargetsReq seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    private fun sendSelectNReq(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        reason: SelectNReason,
    ) {
        val game = ctx.game
        val bb = bundles.bundleBuilder
        val result =
            bb.selectNBundle(
                game,
                counters.counter,
                pendingPrompt,
            ) { req -> selectNEnvelope(pendingPrompt, reason, req) }
        Tap.outboundTemplate("SelectNReq seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    private fun sendOrderReq(pendingPrompt: InteractivePromptBridge.PendingPrompt) {
        val result = bundles.bundleBuilder.orderBundle(ctx.game, counters.counter, pendingPrompt)
        Tap.outboundTemplate("OrderReq seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    private fun learnPromptId(pendingPrompt: InteractivePromptBridge.PendingPrompt): Int {
        val hasHandChoice = pendingPrompt.request.candidateRefs.any { it.zone == "Hand" }
        return if (hasHandChoice) PromptIds.LEARN_LESSON_OR_DISCARD else PromptIds.LEARN_LESSON_ONLY
    }

    private fun selectNEnvelope(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        reason: SelectNReason,
        req: SelectNReq,
    ): SelectNEnvelope =
        when (reason) {
            SelectNReason.Discard,
            SelectNReason.SacrificeEffect,
            SelectNReason.LegendRule,
            SelectNReason.RevealChoose,
            SelectNReason.Resolution,
            SelectNReason.SuspectChoice,
            SelectNReason.LibraryPutback,
            SelectNReason.MutateTopBottom,
            SelectNReason.LearnLesson,
            SelectNReason.StaticColorChoice,
            SelectNReason.StaticSubtypeChoice,
            SelectNReason.StaticParityChoice,
            ->
                SelectNPromptRoutes.route(pendingPrompt.request.semantic)?.envelope(req) { learnPromptId(pendingPrompt) }
                    ?: error("missing SelectN route for ${pendingPrompt.request.semantic}")
            SelectNReason.Sacrifice,
            SelectNReason.ExileFromGrave,
            SelectNReason.CollectEvidenceCost,
            SelectNReason.EnlistCost,
            SelectNReason.StationTapCost,
            SelectNReason.ReturnUnblockedAttackerCost,
            SelectNReason.ConvokeCost,
            SelectNReason.WaterbendCost,
            -> error("cost SelectN uses PayCostsReq")
        }

    private fun sendPayCostsReq(
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

    /**
     * Keywords that surface as pre-cast binary optional costs via Forge's
     * `addKeywordCost` dispatch path (distinct from the `OptionalCostValue`
     * enum path). Each entry maps to one `CastingTimeOptionType_AdditionalCost`
     * CTO option in the combined cost prompt at cast time.
     *
     * Selection criteria: Forge's `GameActionUtil.getAlternativeCosts`
     * keyword-cost loop calls `pc.addKeywordCost` (binary yes/no) for
     * exactly these keywords today — Offspring, Casualty, Conspire.
     * Multikicker/Replicate/Squad use `chooseNumberForKeywordCost(max=N)`
     * for repeated payment and need a different protocol shape (deferred
     * to a future `Multikicker`-typed CTO).
     *
     * Cat-C alternative-cast keywords (Disturb, Foretell, Plot, Madness,
     * Warp, Escape, Flashback, Unearth, Bestow, Suspend, …) ride the
     * `AlternativeCost`-enum / `addHandAltCostCastActions` path and
     * deliberately do NOT show up here. Note: Harmonize *does* call
     * `addKeywordCost` inside its `sa.isHarmonize()` branch in Forge's
     * loop, but only as a sub-prompt of the Harmonize alt-cast SA itself
     * (graveyard cast); the normal (non-Harmonize) cast of the same card
     * doesn't trigger it. Whitelisting Harmonize here would mis-emit a
     * pre-cast CTO on the normal cast path, so it stays excluded.
     */
    private val binaryKeywordCostNames =
        setOf(
            forge.game.keyword.Keyword.OFFSPRING,
            forge.game.keyword.Keyword.CASUALTY,
            forge.game.keyword.Keyword.CONSPIRE,
        )

    private data class KeywordCostEntry(
        val name: String,
    )

    private fun collectKeywordCostEntries(card: forge.game.card.Card): List<KeywordCostEntry> {
        val out = mutableListOf<KeywordCostEntry>()
        for (ki in card.keywords) {
            val keyword = ki.keyword ?: continue
            if (keyword in binaryKeywordCostNames) {
                out += KeywordCostEntry(keyword.toString())
            }
        }
        return out
    }

    private fun findKeywordSlot(
        card: forge.game.card.Card,
        keywordName: String,
        slotBound: Int,
    ): Int? {
        // Match against the card's printed keyword text in declaration order
        // — the same source `AbilityRegistry.mapKeywords` uses to assign
        // ability-id slots. Walking `card.keywords` (the live KeywordInterface
        // list) drifts whenever a static effect grants/removes a keyword
        // mid-game; printed text is stable.
        val keywordStrings =
            card.rules
                ?.mainPart
                ?.keywords
                ?.toList() ?: return null
        for ((idx, kwText) in keywordStrings.withIndex()) {
            if (idx >= slotBound) return null
            if (kwText.startsWith(keywordName)) return idx
        }
        return null
    }

    /**
     * Build and send a GroupReq for surveil/scry. Looks up instanceIds for
     * the cards being surveilled from the library top.
     *
     * Client expects a GSM diff that exposes the library top card(s) as
     * `visibility=Private, viewers=[seatId]` before the GroupReq — this makes
     * the card visible (face-up) in the client's surveil/scry modal.
     */
    private fun sendGroupReqForSurveilScry(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        context: GroupingContext,
    ) {
        val bridge = ctx.bridge
        val game = ctx.game
        val req = pendingPrompt.request

        // Resolve candidateRefs → cards + build bundle. Returns null if no cards resolved.
        val result = bundles.bundleBuilder.resolveSurveilScryBundle(req.candidateRefs, context, counters.counter)
        if (result == null) {
            log.warn(
                "TargetingHandler: surveil/scry resolve failed — candidateRefs={} (falling back)",
                req.candidateRefs.size,
            )
            bridge.seat(counters.seatId).prompt.submitResponse(pendingPrompt.promptId, listOf(req.defaultIndex))
            bridge.awaitPriority()
            return
        }

        val contextLabel = if (context == GroupingContext.Surveil) "Surveil" else "Scry"
        val msgCount = result.messages.size
        log.info("TargetingHandler: sending GroupReq for {} messages={}", contextLabel, msgCount)
        tracer.traceEvent(MatchEventType.TARGET_PROMPT, game, "$contextLabel GroupReq messages=$msgCount")

        Tap.outboundTemplate("GroupReq($contextLabel) seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    private fun drainPendingPlayback() {
        val playback = ctx.bridge.playbackFor(counters.seatId) ?: return
        if (!playback.hasPendingMessages()) return

        for (batch in playback.drainQueue()) {
            sink.sendBundledGRE(batch)
        }
    }

    /** Submit default response and wait — used when modal lookup fails. */
    private fun autoResolvePrompt(prompt: InteractivePromptBridge.PendingPrompt) {
        val bridge = ctx.bridge
        bridge.seat(counters.seatId).prompt.submitResponse(prompt.promptId, listOf(prompt.request.defaultIndex))
        bridge.awaitPriority()
    }
}
