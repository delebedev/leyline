package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.handoff.TargetingCommandReceipt
import leyline.bridge.handoff.TargetingInteractionKind
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
    }

    private val log = LoggerFactory.getLogger(TargetingHandler::class.java)
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
        val compatibility = bridge.cutCoordinator.compatibilityCostSelection.current()
        if (compatibility != null) {
            val receipt =
                bridge.cutCoordinator.compatibilityCostSelection.submitToggle(
                    compatibility.interactionId,
                    greMsg.gameStateId,
                    resp.target.targetIdx,
                    resp.target.targetsList.map { target ->
                        TargetToggleValue(target.targetInstanceId, target.legalAction != SelectAction.Unselect)
                    },
                ) ?: return
            deliverCompatibilityReceipt(receipt, autoPass)
            return
        }
        val targeting =
            bridge.cutCoordinator.targeting
                .current()
                ?.takeIf { it.kind == TargetingInteractionKind.Targeting }
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
        log.warn("TargetingHandler: SelectTargetsResp did not match a coordinator-owned window")
        DevCheck.failOnAutoPass { "SelectTargetsResp but no coordinator-owned window" }
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
        val compatibility = bridge.cutCoordinator.compatibilityCostSelection.current()
        val compatibilityReceipt =
            bridge.cutCoordinator.compatibilityCostSelection.submitTargets(
                compatibility?.interactionId,
                greMsg.gameStateId,
            )
        if (compatibilityReceipt != null) {
            deliverCompatibilityReceipt(compatibilityReceipt, autoPass)
            return
        }
        val targeting =
            bridge.cutCoordinator.targeting
                .current()
                ?.takeIf { it.kind == TargetingInteractionKind.Targeting }
        val migrated = bridge.cutCoordinator.targeting.submitTargets(targeting?.interactionId, greMsg.gameStateId)
        if (migrated != null) {
            deliverTargetingReceipt(migrated, autoPass)
            return
        }
        log.warn("TargetingHandler: SubmitTargetsReq did not match a coordinator-owned window")
        DevCheck.failOnAutoPass { "SubmitTargetsReq but no coordinator-owned window" }
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
        log.warn("TargetingHandler: SelectNResp did not match a coordinator-owned window")
        DevCheck.failOnAutoPass { "SelectNResp but no coordinator-owned window" }
    }

    fun onEffectCost(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        if (manaSourcePaymentHandler.tryHandleEffectCost(greMsg, autoPass)) return
        if (manaSourcePaymentHandler.tryHandleGatherCounters(greMsg, autoPass)) return
        if (manaSourcePaymentHandler.tryHandleOneShotEffectCost(greMsg, autoPass)) return
        if (cardSelectInteractionHandler.tryHandleEffectCost(greMsg, autoPass)) return
        log.warn("TargetingHandler: EffectCostResp did not match a coordinator-owned window")
        DevCheck.failOnAutoPass { "EffectCostResp but no coordinator-owned window" }
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
        if (checkPendingPrompt() == PromptResult.SENT_TO_CLIENT) return true
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
     * Check whether a coordinator-owned prompt is visible to the session.
     * Targeting and compatibility card windows publish SelectTargetsReq;
     * typed runtimes own their response mapping and retirement. Other
     * coordinator windows remain on their named dispatch paths.
     */
    fun checkPendingPrompt(): PromptResult {
        val bridge = ctx.bridge
        if (hasCoordinatorPrompt(bridge)) return PromptResult.SENT_TO_CLIENT
        return PromptResult.NONE
    }

    private fun hasCoordinatorPrompt(bridge: leyline.game.state.GameBridge): Boolean = bridge.cutCoordinator.prompts.hasPendingInteraction()

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
                null,
                -> null
            }
        if (deferredClaim != null) {
            cancelDeferredCast(deferredClaim, autoPass)
            return
        }

        val compatibility = bridge.cutCoordinator.compatibilityCostSelection.current()
        if (compatibility != null) {
            bridge.cutCoordinator.compatibilityCostSelection.cancel(compatibility.interactionId, greMsg.gameStateId)?.let { receipt ->
                deliverCompatibilityReceipt(receipt, autoPass)
                return
            }
        }
        val targeting =
            bridge.cutCoordinator.targeting
                .current()
                ?.takeIf { it.kind == TargetingInteractionKind.Targeting }
        if (targeting != null) {
            bridge.cutCoordinator.targeting.cancel(targeting.interactionId, greMsg.gameStateId)?.let { receipt ->
                deliverTargetingReceipt(receipt, autoPass)
                return
            }
        }

        val modal = bridge.cutCoordinator.modalChoices.current()
        if (modal != null && cancelModalChoice(modal, greMsg.gameStateId, autoPass)) return

        val distribution = bridge.cutCoordinator.distribution.current()
        if (distribution != null) {
            if (bridge.cutCoordinator.distribution.cancel(distribution.interactionId, greMsg.gameStateId)) {
                bridge.awaitPriority()
                autoPass()
                return
            }
        }

        if (manaSourcePaymentHandler.tryHandleCancel(greMsg, autoPass)) return
        if (manaSourcePaymentHandler.tryHandleOneShotCancel(greMsg, autoPass)) return

        log.warn("TargetingHandler: CancelActionReq but no coordinator-owned window")
        DevCheck.failOnAutoPass { "CancelActionReq but no coordinator-owned window" }
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
    ) = deliverReceipt(receipt, autoPass) { interactionId, deliveryToken ->
        ctx.bridge.cutCoordinator.targeting
            .acknowledgeDelivery(interactionId, deliveryToken)
    }

    private fun deliverCompatibilityReceipt(
        receipt: TargetingCommandReceipt,
        autoPass: () -> Unit,
    ) = deliverReceipt(receipt, autoPass) { interactionId, deliveryToken ->
        ctx.bridge.cutCoordinator.compatibilityCostSelection
            .acknowledgeDelivery(interactionId, deliveryToken)
    }

    private fun deliverReceipt(
        receipt: TargetingCommandReceipt,
        autoPass: () -> Unit,
        acknowledge: (interactionId: String, deliveryToken: Long) -> Boolean,
    ) {
        val bridge = ctx.bridge
        receipt.deliveryToken?.let { deliveryToken ->
            val batches = bridge.cutCoordinator.drain(counters.seatId)
            try {
                batches.forEach(sink::sendBundledGRE)
            } catch (ex: Exception) {
                bridge.cutCoordinator.failDelivery(ex)
            }
            check(acknowledge(receipt.interactionId, deliveryToken)) {
                "Targeting delivery acknowledgement was stale"
            }
        }
        if (receipt.engineWillResume) {
            bridge.awaitPriority()
            autoPass()
        }
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
}
