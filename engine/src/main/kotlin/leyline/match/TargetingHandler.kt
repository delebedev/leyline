package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptResponseMapper
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SelectNPromptRoute
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.handoff.TargetingCommandReceipt
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.bundle.UnclassifiedCandidateRequestBuilder
import leyline.game.bundle.envelope
import leyline.game.mapping.PromptIds
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
    private val bundles: BundleBuilderHolder,
    private val ctx: SessionContext,
) {
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
        ): List<Int> = PromptResponseSubmitter.mapSelectNIdsToPromptIndices(selectedIds, pendingPrompt, resolveForgeCardId)
    }

    private val log = LoggerFactory.getLogger(TargetingHandler::class.java)
    private val promptResponseSubmitter = PromptResponseSubmitter(counters, ctx)
    private val cardSelectInteractionHandler = CardSelectInteractionHandler(ctx)
    private val revealChoiceInteractionHandler = RevealChoiceInteractionHandler(ctx)
    private val staticChoiceInteractionHandler = StaticChoiceInteractionHandler(ctx)
    private val manaSourcePaymentHandler = ManaSourcePaymentHandler(sink, counters, ctx)
    private val deferredCastCostInteractionHandler =
        DeferredCastCostInteractionHandler(
            sink = sink,
            counters = counters,
            bundles = bundles,
            ctx = ctx,
            getPendingInteraction = { pendingInteraction },
            setPendingInteraction = { pendingInteraction = it },
        )

    @Volatile
    private var pendingInteraction: PendingClientInteraction? = null

    /** Clear targeting state for puzzle hot-swap. */
    fun reset() {
        pendingInteraction = null
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
        val resp = greMsg.selectTargetsResp
        val targeting = bridge.cutCoordinator.targeting.current()
        if (targeting != null) {
            val receipt =
                bridge.cutCoordinator.targeting.submitToggle(
                    targeting.interactionId,
                    greMsg.gameStateId,
                    resp.target.targetIdx,
                    resp.target.targetsList.map { target ->
                        TargetToggleValue(
                            instanceId = target.targetInstanceId,
                            selected = target.legalAction != SelectAction.Unselect,
                        )
                    },
                ) ?: return
            deliverTargetingReceipt(receipt, autoPass)
            return
        }
        val pendingPrompt = bridge.seat(counters.seatId).prompt.getPendingPrompt()
        if (pendingPrompt == null || pendingPrompt.request.route !is ResolvedPromptRoute.UnclassifiedCandidate) {
            log.warn("TargetingHandler: SelectTargetsResp did not match a bound candidate window")
            DevCheck.failOnAutoPass { "SelectTargetsResp but no matching candidate window" }
            return
        }
        val existing =
            (pendingInteraction as? PendingClientInteraction.UnclassifiedCandidateSelection)
                ?.takeIf { it.promptId == pendingPrompt.promptId }
                ?.selectedInstanceIds
                .orEmpty()
        if (resp.target.targetIdx != pendingPrompt.request.targetIndex) {
            sendUnclassifiedCandidateRePrompt(pendingPrompt, existing)
            return
        }
        val selected = existing.toMutableList()
        resp.target.targetsList.forEach { target ->
            if (target.legalAction == SelectAction.Unselect) {
                selected.remove(target.targetInstanceId)
            } else if (target.targetInstanceId !in selected) {
                selected += target.targetInstanceId
            }
        }
        val indices =
            PromptResponseMapper.targetIdsToPromptIndices(
                selected,
                pendingPrompt.request,
                resolveForgeCardId = { bridge.getForgeCardId(InstanceId(it)) },
                resolvePlayerEntityId = { bridge.getPlayer(SeatId(it))?.id },
            )
        pendingInteraction = PendingClientInteraction.UnclassifiedCandidateSelection(pendingPrompt.promptId, indices, selected)
        sendUnclassifiedCandidateRePrompt(pendingPrompt, selected)
    }

    /**
     * Handle SubmitTargetsReq (phase 2): submit stored selection to engine.
     *
     * Type-only message (no payload). Uses selection stored by [onSelectTargets].
     */
    fun onSubmitTargets(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val targeting = bridge.cutCoordinator.targeting.current()
        val migrated = bridge.cutCoordinator.targeting.submitTargets(targeting?.interactionId, greMsg.gameStateId)
        if (migrated != null) {
            deliverTargetingReceipt(migrated, autoPass)
            return
        }
        val pending = pendingInteraction as? PendingClientInteraction.UnclassifiedCandidateSelection
        if (pending == null) {
            log.warn("TargetingHandler: SubmitTargetsReq did not match a bound candidate window")
            DevCheck.failOnAutoPass { "SubmitTargetsReq but no matching candidate window" }
            return
        }
        val prompt = bridge.seat(counters.seatId).prompt.getPendingPrompt()
        if (prompt?.promptId != pending.promptId || prompt.request.route !is ResolvedPromptRoute.UnclassifiedCandidate) return
        pendingInteraction = null
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
        if (revealChoiceInteractionHandler.tryHandleSelectN(greMsg, autoPass)) return
        if (staticChoiceInteractionHandler.tryHandleSelectN(greMsg, autoPass)) return
        if (cardSelectInteractionHandler.tryHandleSelectN(greMsg, autoPass)) return
        promptResponseSubmitter.onSelectN(greMsg, autoPass)
    }

    fun onEffectCost(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        if (manaSourcePaymentHandler.tryHandleEffectCost(greMsg, autoPass)) return
        if (manaSourcePaymentHandler.tryHandleOneShotEffectCost(greMsg, autoPass)) return
        if (cardSelectInteractionHandler.tryHandleEffectCost(greMsg, autoPass)) return
        promptResponseSubmitter.onEffectCost(greMsg, autoPass)
    }

    /**
     * Native PayCostsReq mana-payment UIs answer through PerformActionResp.
     * Waterbend reducer clicks are MakePayment actions; the Done button is Pass.
     */
    fun tryHandlePayCostsPerformAction(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean = manaSourcePaymentHandler.tryHandlePerformAction(greMsg, autoPass)

    /**
     * After a cast, check for a pending targeting prompt or intermediate stack state.
     * Returns true if handled (caller should return), false to continue normal flow.
     *
     * @param clientAutoResolve true when the client's autoPassOption signals
     *   "resolve my stack effects" — skips the stack prompt when the player has
     *   no meaningful responses, matching client behavior (#92).
     */
    @Suppress("ReturnCount")
    fun handlePostCastPrompt(clientAutoResolve: Boolean = false): Boolean {
        val bridge = ctx.bridge
        val game = ctx.game
        val pendingPrompt = bridge.seat(counters.seatId).prompt.getPendingPrompt()
        if (pendingPrompt != null) {
            if (sendPrompt(pendingPrompt)) return true
            if (checkPendingPrompt() == PromptResult.SENT_TO_CLIENT) return true
        }
        if (!game.stack.isEmpty) {
            // When auto-resolve is active and the player has no meaningful responses
            // (only Pass), skip the prompt — let autoPassAndAdvance() handle stack
            // resolution transparently, matching client behavior (#92).
            val actionWindow = bridge.seat(counters.seatId).action.getPending()
            if (clientAutoResolve &&
                actionWindow != null &&
                !bridge.cutCoordinator.hasMeaningfulPriorityAction(actionWindow.actionId)
            ) {
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
    }

    /**
     * Check for a residual pending interactive prompt.
     * - Targeting prompts (candidateRefs non-empty) → send SelectTargetsReq to client.
     * - Unclassified entity choices retain their legacy request path.
     * Coordinator-owned prompts and synchronous default policies never enter this fallback.
     */
    fun checkPendingPrompt(): PromptResult {
        val bridge = ctx.bridge
        if (hasCoordinatorPrompt(bridge)) return PromptResult.SENT_TO_CLIENT
        val seatBridge = bridge.seat(counters.seatId)
        val pendingPrompt = seatBridge.prompt.getPendingPrompt() ?: return PromptResult.NONE
        return if (sendPrompt(pendingPrompt)) {
            PromptResult.SENT_TO_CLIENT
        } else {
            when (pendingPrompt.request.route) {
                is ResolvedPromptRoute.AutoResolve ->
                    error("AutoResolve policy must complete before publishing a pending prompt")

                is ResolvedPromptRoute.Order ->
                    error("Order prompts must be published by MatchOrderInteractionRuntime")

                is ResolvedPromptRoute.CardSelect ->
                    error("CardSelect prompts must be published by MatchCardSelectInteractionRuntime")

                is ResolvedPromptRoute.StaticChoice ->
                    error("StaticChoice prompts must be published by MatchStaticChoiceInteractionRuntime")

                is ResolvedPromptRoute.RevealChoice ->
                    error("RevealChoice prompts must be published by MatchRevealChoiceInteractionRuntime")

                is ResolvedPromptRoute.Grouping,
                is ResolvedPromptRoute.ModalChoice,
                is ResolvedPromptRoute.PayCosts,
                is ResolvedPromptRoute.Search,
                is ResolvedPromptRoute.UnclassifiedEntityChoice,
                is ResolvedPromptRoute.Targeting,
                is ResolvedPromptRoute.UnclassifiedCandidate,
                -> PromptResult.NONE
            }
        }
    }

    private fun hasCoordinatorPrompt(bridge: leyline.game.state.GameBridge): Boolean =
        bridge.cutCoordinator.let { coordinator ->
            coordinator.targeting.current() != null ||
                coordinator.search.current() != null ||
                coordinator.grouping.current() != null ||
                coordinator.staticChoices.current() != null ||
                coordinator.revealChoices.current() != null ||
                coordinator.modalChoices.current() != null ||
                coordinator.manaSourcePayments.current() != null ||
                coordinator.oneShotPayCosts.current() != null
        }

    private fun sendPrompt(pendingPrompt: InteractivePromptBridge.PendingPrompt): Boolean =
        when (val route = pendingPrompt.request.route) {
            is ResolvedPromptRoute.Grouping -> {
                error("Grouping prompts must be published by MatchGroupingInteractionRuntime")
            }

            is ResolvedPromptRoute.ModalChoice -> {
                error("ModalChoice prompts must be published by MatchModalChoiceRuntime")
            }

            is ResolvedPromptRoute.UnclassifiedEntityChoice -> {
                sendSelectNReq(pendingPrompt, route.descriptor)
                true
            }

            is ResolvedPromptRoute.PayCosts -> {
                error("PayCosts prompts must be published by a match-scoped coordinator runtime")
            }

            is ResolvedPromptRoute.CardSelect -> {
                error("CardSelect prompts must be published by MatchCardSelectInteractionRuntime")
            }

            is ResolvedPromptRoute.StaticChoice -> {
                error("StaticChoice prompts must be published by MatchStaticChoiceInteractionRuntime")
            }

            is ResolvedPromptRoute.RevealChoice -> {
                error("RevealChoice prompts must be published by MatchRevealChoiceInteractionRuntime")
            }

            is ResolvedPromptRoute.Targeting -> {
                error("Targeting prompts must be published by MatchTargetingInteractionRuntime")
            }

            is ResolvedPromptRoute.UnclassifiedCandidate -> {
                sendUnclassifiedCandidateReq(pendingPrompt)
                true
            }

            is ResolvedPromptRoute.Search -> {
                error("Search prompts must be published by MatchSearchInteractionRuntime")
            }

            is ResolvedPromptRoute.Order -> {
                error("Order prompts must be published by MatchOrderInteractionRuntime")
            }

            is ResolvedPromptRoute.AutoResolve -> false
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
    fun onCancelAction(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val deferredClaim =
            when (val interaction = pendingInteraction) {
                is PendingClientInteraction.OptionalCost -> interaction.actionClaim
                is PendingClientInteraction.AlternateCostChoice -> interaction.actionClaim
                is PendingClientInteraction.HybridManaType -> interaction.actionClaim
                is PendingClientInteraction.UnclassifiedCandidateSelection,
                null,
                -> null
            }
        if (deferredClaim != null) {
            cancelDeferredCast(deferredClaim, autoPass)
            return
        }

        val targeting = bridge.cutCoordinator.targeting.current()
        if (targeting != null) {
            bridge.cutCoordinator.targeting.cancel(targeting.interactionId, greMsg.gameStateId)?.let { receipt ->
                deliverTargetingReceipt(receipt, autoPass)
                return
            }
        }

        val modal = bridge.cutCoordinator.modalChoices.current()
        if (modal != null && cancelModalChoice(modal, greMsg.gameStateId, autoPass)) return

        if (manaSourcePaymentHandler.tryHandleCancel(greMsg, autoPass)) return
        if (manaSourcePaymentHandler.tryHandleOneShotCancel(greMsg, autoPass)) return

        val seatBridge = bridge.seat(counters.seatId)
        val pendingPrompt = seatBridge.prompt.getPendingPrompt()
        if (pendingPrompt == null) {
            log.warn("TargetingHandler: CancelActionReq but no pending prompt (likely timeout race)")
            DevCheck.failOnAutoPass { "CancelActionReq but no pending prompt" }
            return
        }

        log.info("TargetingHandler: CancelActionReq — submitting empty targets to unwind spell")

        // Submit empty list → engine sees no targets → spell fails → unwind
        seatBridge.prompt.submitResponse(pendingPrompt.promptId, emptyList())
        bridge.awaitPriority()
        autoPass()
    }

    private fun cancelModalChoice(
        modal: leyline.bridge.handoff.PublishedModalChoiceInteraction,
        gameStateId: Int,
        autoPass: () -> Unit,
    ): Boolean {
        val bridge = ctx.bridge
        if (!bridge.cutCoordinator.modalChoices.cancel(modal.interactionId, gameStateId)) {
            log.warn("TargetingHandler: CancelActionReq did not match current modal window")
            DevCheck.failOnAutoPass { "CancelActionReq did not match current modal window" }
            return false
        }
        log.info("TargetingHandler: CancelActionReq — cancelling modal choice")
        bridge.awaitPriority()
        bridge.cutCoordinator.modalChoices.releaseAfterEngineResume(modal.interactionId)
        autoPass()
        return true
    }

    private fun cancelDeferredCast(
        actionClaim: leyline.bridge.coord.MatchActionWindowRuntime.ActionClaim,
        autoPass: () -> Unit,
    ) {
        pendingInteraction = null
        actionClaim.deferredCostPlan?.sourceCardId?.let { ctx.bridge.setSelectedSpellGrpId(it, null) }
        ctx.bridge
            .seat(counters.seatId)
            .prompt.journal
            .clearHybridManaStash()
        check(ctx.bridge.cutCoordinator.reopenActionClaim(actionClaim)) { "Deferred action claim did not reopen" }
        log.info("TargetingHandler: CancelActionReq — cancelling deferred cast before engine submit")
        autoPass()
    }

    private fun deliverTargetingReceipt(
        receipt: TargetingCommandReceipt,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        receipt.deliveryToken?.let { deliveryToken ->
            val batches = bridge.cutCoordinator.drain(counters.seatId)
            try {
                batches.forEach(sink::sendBundledGRE)
            } catch (ex: Exception) {
                bridge.cutCoordinator.failDelivery(ex)
            }
            check(bridge.cutCoordinator.targeting.acknowledgeDelivery(receipt.interactionId, deliveryToken)) {
                "Targeting delivery acknowledgement was stale"
            }
        }
        if (receipt.engineWillResume) {
            bridge.awaitPriority()
            autoPass()
        }
    }

    private fun sendUnclassifiedCandidateRePrompt(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        selectedInstanceIds: List<Int>,
    ) {
        val echo = bundles.bundleBuilder.buildEchoDiffGsm(counters.counter)
        val gameStateId = counters.counter.currentGsId()
        val request =
            UnclassifiedCandidateRequestBuilder.rePrompt(
                pendingPrompt,
                ctx.bridge,
                counters.seatId.value,
                selectedInstanceIds.toSet(),
            )
        val message =
            sink.makeGRE(GREMessageType.SelectTargetsReq_695e, gameStateId, counters.counter.nextMsgId()) {
                it.selectTargetsReq = request
                it.prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_TARGETS).build()
                it.allowCancel = AllowCancel.Abort
                it.allowUndo = true
            }
        sink.sendBundledGRE(listOf(echo, message))
    }

    private fun sendUnclassifiedCandidateReq(pendingPrompt: InteractivePromptBridge.PendingPrompt) {
        val result = bundles.bundleBuilder.unclassifiedCandidateBundle(ctx.game, counters.counter, pendingPrompt)
        sink.sendBundledGRE(result.messages)
    }

    /**
     * Handle SearchResp: resolve the pending search prompt with the client's choice.
     *
     * @param itemsFound instanceIds the client selected (from SearchResp.itemsFound).
     *        Empty = player declined ("fail to find").
     */
    fun onSearchResp(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val pending = bridge.cutCoordinator.search.current()
        if (pending == null) {
            log.warn("SearchResp but no coordinator-owned search window")
            DevCheck.failOnAutoPass { "SearchResp but no search window" }
            return
        }
        val accepted =
            bridge.cutCoordinator.search.submit(
                pending.interactionId,
                greMsg.gameStateId,
                greMsg.searchResp?.itemsFoundList.orEmpty(),
            )
        if (!accepted) {
            log.warn("SearchResp did not match the current search window")
            DevCheck.failOnAutoPass { "SearchResp did not match the current search window" }
            return
        }
        bridge.awaitPriority()
        autoPass()
    }

    // --- Helpers ---

    /**
     * Handle CastingTimeOptionsResp: dispatches to modal or kicker/optional cost handler.
     */
    fun onCastingTimeOptions(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        // Deferred cast costs retain the cast action claim; modal ability choices use the coordinator window below.
        if (deferredCastCostInteractionHandler.onCastingTimeOptions(greMsg, autoPass)) {
            return
        }
        val bridge = ctx.bridge
        val modal = bridge.cutCoordinator.modalChoices.current()
        if (modal == null) {
            when (val pending = pendingInteraction) {
                is PendingClientInteraction.AlternateCostChoice,
                is PendingClientInteraction.OptionalCost,
                is PendingClientInteraction.HybridManaType,
                -> error("deferred cast-cost handler did not consume ${pending::class.simpleName}")

                else -> {
                    log.warn("TargetingHandler: CastingTimeOptionsResp but no modal or deferred-cost window")
                    DevCheck.failOnAutoPass { "CastingTimeOptionsResp but no modal or deferred-cost window" }
                }
            }
            return
        }
        val chosenGrpIds = greMsg.castingTimeOptionsResp.castingTimeOptionResp.chooseModalResp.grpIdsList
        if (!bridge.cutCoordinator.modalChoices.submit(modal.interactionId, greMsg.gameStateId, chosenGrpIds)) {
            log.warn("TargetingHandler: CastingTimeOptionsResp did not match current modal window")
            DevCheck.failOnAutoPass { "CastingTimeOptionsResp did not match current modal window" }
            return
        }
        log.info("TargetingHandler: CastingTimeOptionsResp (modal) grpIds={}", chosenGrpIds)
        bridge.awaitPriority()
        bridge.cutCoordinator.modalChoices.releaseAfterEngineResume(modal.interactionId)
        autoPass()
    }

    internal fun checkHybridManaTypeOptions(actionClaim: leyline.bridge.coord.MatchActionWindowRuntime.ActionClaim): Boolean =
        deferredCastCostInteractionHandler.checkHybridManaTypeOptions(actionClaim)

    /**
     * Check if a Cast action targets a card with optional costs (kicker, buyback, etc.).
     * If yes, sends CastingTimeOptionsReq to client and returns true (caller should NOT submit to engine).
     * If no, returns false (caller should proceed normally).
     */
    internal fun checkOptionalCosts(
        actionClaim: leyline.bridge.coord.MatchActionWindowRuntime.ActionClaim,
        preserveHybridStash: Boolean = false,
    ): Boolean =
        deferredCastCostInteractionHandler.checkOptionalCosts(
            actionClaim = actionClaim,
            preserveHybridStash = preserveHybridStash,
        )

    internal fun checkAlternateAdditionalCostChoice(actionClaim: leyline.bridge.coord.MatchActionWindowRuntime.ActionClaim): Boolean =
        deferredCastCostInteractionHandler.checkAlternateAdditionalCostChoice(actionClaim)

    private fun sendSelectNReq(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        route: SelectNPromptRoute,
    ) {
        val game = ctx.game
        val bb = bundles.bundleBuilder
        val result =
            bb.selectNBundle(
                game,
                counters.counter,
                pendingPrompt,
                route,
            ) { req -> route.envelope(req) }
        Tap.outboundTemplate("SelectNReq seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }
}
