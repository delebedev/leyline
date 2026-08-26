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
 * Handles targeting-related client messages.
 *
 * Protocol sequencing uses the shared
 * [MessageCounter][leyline.game.bundle.MessageCounter] via `counters.counter` —
 * no seeding or syncing needed.
 */
class TargetingHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
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
            ctx = ctx,
        )

    /** Clear targeting state for puzzle hot-swap. */
    fun reset() {
        ctx.bridge.cutCoordinator
            .deferredCast
            .discard()
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
    internal fun onSelectTargets(greMsg: ClientToGREMessage): HandlerResult {
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
                ) ?: return HandlerResult.Waiting
            return deliverCompatibilityReceipt(receipt)
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
                ) ?: return HandlerResult.Waiting
            return deliverTargetingReceipt(receipt)
        }
        log.warn("TargetingHandler: SelectTargetsResp did not match a coordinator-owned window")
        DevCheck.failOnAutoPass { "SelectTargetsResp but no coordinator-owned window" }
        return HandlerResult.NotHandled
    }

    /**
     * Handle SubmitTargetsReq (phase 2): submit stored selection to engine.
     *
     * Type-only message (no payload). Uses selection stored by [onSelectTargets].
     */
    internal fun onSubmitTargets(greMsg: ClientToGREMessage): HandlerResult {
        val bridge = ctx.bridge
        val compatibility = bridge.cutCoordinator.compatibilityCostSelection.current()
        val compatibilityReceipt =
            bridge.cutCoordinator.compatibilityCostSelection.submitTargets(
                compatibility?.interactionId,
                greMsg.gameStateId,
            )
        if (compatibilityReceipt != null) {
            return deliverCompatibilityReceipt(compatibilityReceipt)
        }
        val targeting =
            bridge.cutCoordinator.targeting
                .current()
                ?.takeIf { it.kind == TargetingInteractionKind.Targeting }
        val migrated = bridge.cutCoordinator.targeting.submitTargets(targeting?.interactionId, greMsg.gameStateId)
        if (migrated != null) {
            return deliverTargetingReceipt(migrated)
        }
        log.warn("TargetingHandler: SubmitTargetsReq did not match a coordinator-owned window")
        DevCheck.failOnAutoPass { "SubmitTargetsReq but no coordinator-owned window" }
        return HandlerResult.NotHandled
    }

    /**
     * Handle SelectNResp: map client instanceIds back to prompt option indices and submit.
     * Mirrors [onSelectTargets] but for "choose N cards" prompts.
     */
    internal fun onSelectN(greMsg: ClientToGREMessage): HandlerResult {
        if (revealChoiceInteractionHandler.tryHandleSelectN(greMsg)) return HandlerResult.Resume
        if (staticChoiceInteractionHandler.tryHandleSelectN(greMsg)) return HandlerResult.Resume
        if (cardSelectInteractionHandler.tryHandleSelectN(greMsg)) return HandlerResult.Resume
        log.warn("TargetingHandler: SelectNResp did not match a coordinator-owned window")
        DevCheck.failOnAutoPass { "SelectNResp but no coordinator-owned window" }
        return HandlerResult.NotHandled
    }

    internal fun onEffectCost(greMsg: ClientToGREMessage): HandlerResult {
        val bridge = ctx.bridge
        val payment = manaSourcePaymentHandler.tryHandleEffectCost(greMsg)
        if (payment != HandlerResult.NotHandled) return payment
        val gather = manaSourcePaymentHandler.tryHandleGatherCounters(greMsg)
        if (gather != HandlerResult.NotHandled) return gather
        val oneShot = manaSourcePaymentHandler.tryHandleOneShotEffectCost(greMsg)
        if (oneShot != HandlerResult.NotHandled) return oneShot
        if (cardSelectInteractionHandler.tryHandleEffectCost(greMsg)) {
            return if (bridge.cutCoordinator.cardSelect.current() == null) HandlerResult.Resume else HandlerResult.Waiting
        }
        log.warn("TargetingHandler: EffectCostResp did not match a coordinator-owned window")
        DevCheck.failOnAutoPass { "EffectCostResp but no coordinator-owned window" }
        return HandlerResult.NotHandled
    }

    /**
     * Native PayCostsReq mana-payment UIs answer through PerformActionResp.
     * Waterbend reducer clicks are MakePayment actions; the Done button is Pass.
     */
    internal fun tryHandlePayCostsPerformAction(greMsg: ClientToGREMessage): HandlerResult =
        manaSourcePaymentHandler.tryHandlePerformAction(greMsg)

    /**
     * Handle CancelActionReq: player backed out of targeting (cancel spell cast).
     *
     * Submits an empty target list to the pending prompt. The engine interprets
     * empty indices as "no targets chosen" → `TargetSelectionResult(false, false)`
     * → spell targeting fails → engine unwinds the cast (removes from stack,
     * returns mana). We then resend the game state so the client sees the
     * board return to pre-cast state with available actions.
     */
    internal fun onCancelAction(greMsg: ClientToGREMessage): HandlerResult {
        val bridge = ctx.bridge
        if (bridge.cutCoordinator.deferredCast.hasPrompt()) {
            return cancelDeferredCast(greMsg.gameStateId)
        }

        val compatibility = bridge.cutCoordinator.compatibilityCostSelection.current()
        if (compatibility != null) {
            bridge.cutCoordinator.compatibilityCostSelection.cancel(compatibility.interactionId, greMsg.gameStateId)?.let { receipt ->
                return deliverCompatibilityReceipt(receipt)
            }
        }
        val targeting =
            bridge.cutCoordinator.targeting
                .current()
                ?.takeIf { it.kind == TargetingInteractionKind.Targeting }
        if (targeting != null) {
            bridge.cutCoordinator.targeting.cancel(targeting.interactionId, greMsg.gameStateId)?.let { receipt ->
                return deliverTargetingReceipt(receipt)
            }
        }

        val modal = bridge.cutCoordinator.modalChoices.current()
        if (modal != null) return cancelModalChoice(modal, greMsg.gameStateId)

        val distribution = bridge.cutCoordinator.distribution.current()
        if (distribution != null) {
            if (bridge.cutCoordinator.distribution.cancel(distribution.interactionId, greMsg.gameStateId)) {
                return HandlerResult.Resume
            }
        }

        val payment = manaSourcePaymentHandler.tryHandleCancel(greMsg)
        if (payment != HandlerResult.NotHandled) return payment
        val oneShot = manaSourcePaymentHandler.tryHandleOneShotCancel(greMsg)
        if (oneShot != HandlerResult.NotHandled) return oneShot

        log.warn("TargetingHandler: CancelActionReq but no coordinator-owned window")
        DevCheck.failOnAutoPass { "CancelActionReq but no coordinator-owned window" }
        return HandlerResult.NotHandled
    }

    private fun cancelModalChoice(
        modal: leyline.bridge.handoff.PublishedModalChoiceInteraction,
        gameStateId: Int,
    ): HandlerResult {
        val bridge = ctx.bridge
        val cleanup = bridge.cutCoordinator.modalChoices.cancelAndClaim(modal.interactionId, gameStateId)
        if (cleanup == null) {
            log.warn("TargetingHandler: CancelActionReq did not match current modal window")
            DevCheck.failOnAutoPass { "CancelActionReq did not match current modal window" }
            return HandlerResult.Waiting
        }
        log.info("TargetingHandler: CancelActionReq — cancelling modal choice")
        return HandlerResult.ResumeAfterEngineResume(cleanup)
    }

    private fun cancelDeferredCast(gameStateId: Int): HandlerResult {
        val deferredCast = ctx.bridge.cutCoordinator.deferredCast
        if (!deferredCast.cancel(gameStateId)) {
            log.warn("TargetingHandler: CancelActionReq did not match current deferred cast window")
            return HandlerResult.Waiting
        }
        log.info("TargetingHandler: CancelActionReq — cancelling deferred cast before engine submit")
        return HandlerResult.Resume
    }

    private fun deliverTargetingReceipt(receipt: TargetingCommandReceipt): HandlerResult =
        deliverReceipt(receipt) { interactionId, deliveryToken ->
            ctx.bridge.cutCoordinator.targeting
                .acknowledgeDelivery(interactionId, deliveryToken)
        }

    private fun deliverCompatibilityReceipt(receipt: TargetingCommandReceipt): HandlerResult =
        deliverReceipt(receipt) { interactionId, deliveryToken ->
            ctx.bridge.cutCoordinator.compatibilityCostSelection
                .acknowledgeDelivery(interactionId, deliveryToken)
        }

    private fun deliverReceipt(
        receipt: TargetingCommandReceipt,
        acknowledge: (interactionId: String, deliveryToken: Long) -> Boolean,
    ): HandlerResult {
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
        return if (receipt.engineWillResume) HandlerResult.Resume else HandlerResult.Waiting
    }

    /**
     * Handle SearchResp: resolve the pending search prompt with the client's choice.
     *
     * @param itemsFound instanceIds the client selected (from SearchResp.itemsFound).
     *        Empty = player declined ("fail to find").
     */
    internal fun onSearchResp(greMsg: ClientToGREMessage): HandlerResult {
        val bridge = ctx.bridge
        val pending = bridge.cutCoordinator.search.current()
        if (pending == null) {
            log.warn("SearchResp but no coordinator-owned search window")
            DevCheck.failOnAutoPass { "SearchResp but no search window" }
            return HandlerResult.Waiting
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
            return HandlerResult.Waiting
        }
        return HandlerResult.Resume
    }

    // --- Helpers ---

    /**
     * Handle CastingTimeOptionsResp: dispatches to modal or kicker/optional cost handler.
     */
    internal fun onCastingTimeOptions(greMsg: ClientToGREMessage): HandlerResult {
        // Deferred cast costs retain the cast action claim; modal ability choices use the coordinator window below.
        val deferredResult = deferredCastCostInteractionHandler.onCastingTimeOptions(greMsg)
        if (deferredResult != HandlerResult.NotHandled) {
            return deferredResult
        }
        val bridge = ctx.bridge
        val modal = bridge.cutCoordinator.modalChoices.current()
        if (modal == null) {
            if (bridge.cutCoordinator.deferredCast.hasPrompt()) {
                error("deferred cast-cost handler did not consume the response")
            }
            log.warn("TargetingHandler: CastingTimeOptionsResp but no modal or deferred-cost window")
            DevCheck.failOnAutoPass { "CastingTimeOptionsResp but no modal or deferred-cost window" }
            return HandlerResult.Waiting
        }
        val chosenGrpIds = greMsg.castingTimeOptionsResp.castingTimeOptionResp.chooseModalResp.grpIdsList
        val cleanup = bridge.cutCoordinator.modalChoices.submitAndClaim(modal.interactionId, greMsg.gameStateId, chosenGrpIds)
        if (cleanup == null) {
            log.warn("TargetingHandler: CastingTimeOptionsResp did not match current modal window")
            DevCheck.failOnAutoPass { "CastingTimeOptionsResp did not match current modal window" }
            return HandlerResult.Waiting
        }
        log.info("TargetingHandler: CastingTimeOptionsResp (modal) grpIds={}", chosenGrpIds)
        return HandlerResult.ResumeAfterEngineResume(cleanup)
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
