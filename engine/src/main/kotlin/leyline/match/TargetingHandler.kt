package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptResponseMapper
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SelectNPromptRoute
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.handoff.TargetingCommandReceipt
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.bundle.CastingTimeOptionsBuilder
import leyline.game.bundle.CastingTimeOptionsBuilder.ModalOptionSpec
import leyline.game.bundle.UnclassifiedCandidateRequestBuilder
import leyline.game.bundle.envelope
import leyline.game.mapping.FrameIdResolver
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

        internal fun mapModalGrpIdsToPromptIndices(
            selectedGrpIds: List<Int>,
            childGrpIds: List<Int>,
        ): List<Int> = selectedGrpIds.mapNotNull { grpId -> childGrpIds.indexOf(grpId).takeIf { it >= 0 } }
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
            when (checkPendingPrompt()) {
                PromptResult.SENT_TO_CLIENT -> return true
                PromptResult.AUTO_RESOLVED,
                PromptResult.NONE,
                -> Unit
            }
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

        /** Non-targeting prompt auto-resolved — caller should re-evaluate (loop continues). */
        AUTO_RESOLVED,
    }

    /**
     * Check for a residual pending interactive prompt.
     * - Targeting prompts (candidateRefs non-empty) → send SelectTargetsReq to client.
     * - Other non-targeting prompts (confirm, choose_cards, order) → auto-resolve with
     *   defaultIndex. Coordinator-owned prompts never enter this fallback.
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
                is ResolvedPromptRoute.AutoResolve -> {
                    val req = pendingPrompt.request
                    // Multi-option generic prompts are real gameplay choices (for
                    // example, odd/even effects) unless a narrower semantic has
                    // resolved them. Keep known safe defaults quiet, but make this
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
                    seatBridge.prompt.submitResponse(pendingPrompt.promptId, listOf(req.defaultIndex))
                    bridge.awaitPriority()
                    PromptResult.AUTO_RESOLVED
                }

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
                is ResolvedPromptRoute.SelectN,
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
                coordinator.manaSourcePayments.current() != null ||
                coordinator.oneShotPayCosts.current() != null
        }

    private fun sendPrompt(pendingPrompt: InteractivePromptBridge.PendingPrompt): Boolean =
        when (val route = pendingPrompt.request.route) {
            is ResolvedPromptRoute.Grouping -> {
                error("Grouping prompts must be published by MatchGroupingInteractionRuntime")
            }

            is ResolvedPromptRoute.ModalChoice -> {
                sendCastingTimeOptionsReq(pendingPrompt)
                true
            }

            is ResolvedPromptRoute.SelectN -> {
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
        when (val interaction = pendingInteraction) {
            is PendingClientInteraction.OptionalCost -> {
                return cancelDeferredCast(interaction.actionClaim, autoPass)
            }
            is PendingClientInteraction.AlternateCostChoice -> {
                return cancelDeferredCast(interaction.actionClaim, autoPass)
            }
            is PendingClientInteraction.HybridManaType -> {
                return cancelDeferredCast(interaction.actionClaim, autoPass)
            }
            is PendingClientInteraction.ModalChoice,
            is PendingClientInteraction.UnclassifiedCandidateSelection,
            null,
            -> Unit
        }

        val targeting = bridge.cutCoordinator.targeting.current()
        if (targeting != null) {
            val receipt = bridge.cutCoordinator.targeting.cancel(targeting.interactionId, greMsg.gameStateId) ?: return
            deliverTargetingReceipt(receipt, autoPass)
            return
        }

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
        // (Spree/Tiered paths, and any Charm cast where Forge filtered at least one
        // mode), translate via card-DB childGrpIds — keeps the modal ordering
        // aligned with `possible[]` upstream. Otherwise fall back to unfiltered
        // (legacy Charm-with-all-modes-legal path).
        val modalChoice = req.modalChoice
        val modalOptions: List<ModalOptionSpec>
        val excludedOptions: List<ModalOptionSpec>
        if (modalChoice != null && modalChoice.possible.all { it.fullIndex in modalInfo.childGrpIds.indices }) {
            modalOptions =
                modalChoice.possible.map { option ->
                    ModalOptionSpec(modalInfo.childGrpIds[option.fullIndex], option.cost)
                }
            excludedOptions =
                modalChoice.excluded
                    .filter { it.fullIndex in modalInfo.childGrpIds.indices }
                    .map { option -> ModalOptionSpec(modalInfo.childGrpIds[option.fullIndex], option.cost) }
        } else {
            // Silent fallback: bridge populated full-list indices but they fell
            // outside card-DB childGrpIds (Forge SVar count vs. card-DB
            // modalChildIds count drift). Modal-cost picked-mode mapping will
            // regress here. Loud in tests, soft in prod.
            if (modalChoice != null) {
                DevCheck.fail {
                    "modal full-list indices out of card-DB range: " +
                        "indices=${modalChoice.possible.map { it.fullIndex }} " +
                        "childCount=${modalInfo.childGrpIds.size} " +
                        "card='$cardName' grpId=$cardGrpId"
                }
            }
            modalOptions = modalInfo.childGrpIds.map(::ModalOptionSpec)
            excludedOptions = emptyList()
        }

        val ctoReq =
            CastingTimeOptionsBuilder.buildModalCastingTimeOptionsReq(
                parentGrpId = modalInfo.parentGrpId,
                modalOptions = modalOptions,
                excludedOptions = excludedOptions,
                minSel = req.min,
                maxSel = req.max,
                sourceInstanceId = sourceInstanceId,
                grpId = ctoGrpId,
                ctoId = ctoId,
                playerIdToPrompt = counters.seatId.value,
            )

        // Save pending state for response mapping. Store the *effective* child
        // grpIds so `onCastingTimeOptions`'s `indexOf(pickedGrpId)` returns an
        // index that aligns with `possible[]` upstream — not an unfiltered index.
        pendingInteraction =
            PendingClientInteraction.ModalChoice(
                pendingPrompt.promptId,
                modalOptions.map { it.grpId },
                stackAbilityInstanceId = sourceInstanceId.takeIf { isTriggered && it > 0 },
                sourceForgeCardId = req.sourceEntityId?.let(::ForgeCardId),
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
        if (deferredCastCostInteractionHandler.onCastingTimeOptions(greMsg, autoPass)) {
            return
        }
        val bridge = ctx.bridge
        when (val pending = pendingInteraction) {
            is PendingClientInteraction.ModalChoice -> {
                val prompt = bridge.seat(counters.seatId).prompt.getPendingPrompt()
                if (prompt == null || !prompt.request.route.accepts(PromptResponseKind.ModalChoice)) {
                    log.warn("TargetingHandler: CastingTimeOptionsResp modal does not match bound route")
                    DevCheck.failOnAutoPass { "CastingTimeOptionsResp modal does not match bound route" }
                    return
                }
                val resp = greMsg.castingTimeOptionsResp
                val chosenGrpIds = resp.castingTimeOptionResp.chooseModalResp.grpIdsList

                val selectedIndices = mapModalGrpIdsToPromptIndices(chosenGrpIds, pending.childGrpIds)

                chosenGrpIds.singleOrNull()?.let { selectedGrpId ->
                    pending.sourceForgeCardId?.let { source ->
                        bridge.recordSelectedModalAbilityGrpId(source, selectedGrpId)
                    }
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

            is PendingClientInteraction.AlternateCostChoice,
            is PendingClientInteraction.OptionalCost,
            is PendingClientInteraction.HybridManaType,
            -> error("deferred cast-cost handler did not consume ${pending::class.simpleName}")

            else -> {
                log.warn("TargetingHandler: CastingTimeOptionsResp but no pending modal or optional cost (likely timeout race)")
                DevCheck.failOnAutoPass { "CastingTimeOptionsResp but no pending modal or optional cost" }
            }
        }
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
            ) { req -> route.envelope(req) { learnPromptId(pendingPrompt) } }
        Tap.outboundTemplate("SelectNReq seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    private fun learnPromptId(pendingPrompt: InteractivePromptBridge.PendingPrompt): Int {
        val hasHandChoice = pendingPrompt.request.candidateRefs.any { it.zone == "Hand" }
        return if (hasHandChoice) PromptIds.LEARN_LESSON_OR_DISCARD else PromptIds.LEARN_LESSON_ONLY
    }

    /** Submit default response and wait — used when modal lookup fails. */
    private fun autoResolvePrompt(prompt: InteractivePromptBridge.PendingPrompt) {
        val bridge = ctx.bridge
        bridge.seat(counters.seatId).prompt.submitResponse(prompt.promptId, listOf(prompt.request.defaultIndex))
        bridge.awaitPriority()
    }
}
