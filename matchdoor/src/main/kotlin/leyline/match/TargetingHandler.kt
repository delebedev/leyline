package leyline.match

import leyline.DevCheck
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.RequestBuilder
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
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
    companion object {
        private const val EATEN_ALIVE_GRP_ID = 93885

        /** Stash optional cost indices after client response — writes to journal only. */
        fun stashOptionalCostIndices(
            prompt: InteractivePromptBridge,
            indices: List<Int>,
        ) {
            prompt.journal.record(PromptSideEffect.OptionalCostStash(indices))
        }
    }

    private val log = LoggerFactory.getLogger(TargetingHandler::class.java)

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
    fun onSelectTargets(greMsg: ClientToGREMessage) {
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
                    pendingPrompt.request.candidateRefs.indexOfFirst {
                        it.kind == "card" && it.entityId == cardId.value
                    }
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

        // Echo-back: actions-only GSM diff + re-prompt with selection reflected
        val echoDiff = bundles.bundleBuilder.buildEchoDiffGsm(counters.counter)
        val gsId = counters.counter.currentGsId()
        val rePrompt = RequestBuilder.buildSelectTargetsRePrompt(pendingPrompt, bridge, selectedInstanceIds, counters.seatId.value)
        val rePromptMsg =
            sink.makeGRE(GREMessageType.SelectTargetsReq_695e, gsId, counters.counter.nextMsgId()) {
                it.selectTargetsReq = rePrompt
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
        val bridge = ctx.bridge
        val pending = pendingInteraction as? PendingClientInteraction.TargetSelection
        if (pending == null) {
            log.warn("TargetingHandler: SubmitTargetsReq but no pending target selection (likely timeout race)")
            DevCheck.failOnAutoPass { "SubmitTargetsReq but no pending target selection" }
            return
        }
        pendingInteraction = null

        log.info("TargetingHandler: SubmitTargetsReq — submitting indices={}", pending.selectedIndices)

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

        val selectedIndices = mapSelectedInstanceIdsToPromptIndices(greMsg.selectNResp.idsList, pendingPrompt)

        log.info("TargetingHandler: SelectNResp indices={}", selectedIndices)

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

        log.info("TargetingHandler: EffectCostResp indices={}", selectedIndices)

        seatBridge.prompt.submitResponse(pendingPrompt.promptId, selectedIndices)
        bridge.awaitPriority()
        autoPass()
    }

    private fun mapSelectedInstanceIdsToPromptIndices(
        selectedInstanceIds: List<Int>,
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ): List<Int> =
        selectedInstanceIds
            .mapNotNull { instanceId ->
                val cardId = ctx.bridge.getForgeCardId(InstanceId(instanceId))
                if (cardId == null) return@mapNotNull null
                pendingPrompt.request.candidateRefs.indexOfFirst { it.entityId == cardId.value }
            }.filter { it >= 0 }

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
        if (pendingPrompt != null) {
            when (val classified = PromptClassifier.classify(pendingPrompt)) {
                is ClassifiedPrompt.ModalChoice -> {
                    tracer.traceEvent(MatchEventType.TARGET_PROMPT, game, "post-cast modal: ${pendingPrompt.request.message}")
                    sendCastingTimeOptionsReq(classified.pendingPrompt)
                    return true
                }

                is ClassifiedPrompt.Targeting -> {
                    tracer.traceEvent(
                        MatchEventType.TARGET_PROMPT,
                        game,
                        "cast-target targets=${pendingPrompt.request.candidateRefs.size}",
                    )
                    sendSelectTargetsReq(classified.pendingPrompt)
                    return true
                }

                is ClassifiedPrompt.SelectN -> {
                    tracer.traceEvent(
                        MatchEventType.TARGET_PROMPT,
                        game,
                        "post-cast selectN reason=${classified.reason} candidates=${pendingPrompt.request.candidateRefs.size}",
                    )
                    when (classified.reason) {
                        ClassifiedPrompt.SelectN.Reason.Sacrifice ->
                            sendSacrificePayCostsReq(classified.pendingPrompt)
                        ClassifiedPrompt.SelectN.Reason.ExileFromGrave ->
                            sendExileFromGravePayCostsReq(classified.pendingPrompt)
                        else -> sendSelectNReq(classified.pendingPrompt, classified.reason)
                    }
                    return true
                }

                is ClassifiedPrompt.Search -> {
                    tracer.traceEvent(MatchEventType.TARGET_PROMPT, game, "post-cast search")
                    sendSearchReq(classified.pendingPrompt)
                    return true
                }

                else -> {}
            }
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

        return when (classified) {
            is ClassifiedPrompt.Grouping -> {
                sendGroupReqForSurveilScry(classified.pendingPrompt, classified.context)
                PromptResult.SENT_TO_CLIENT
            }

            is ClassifiedPrompt.ModalChoice -> {
                tracer.traceEvent(MatchEventType.TARGET_PROMPT, game, "modal: ${pendingPrompt.request.message}")
                sendCastingTimeOptionsReq(classified.pendingPrompt)
                PromptResult.SENT_TO_CLIENT
            }

            is ClassifiedPrompt.SelectN -> {
                tracer.traceEvent(
                    MatchEventType.TARGET_PROMPT,
                    game,
                    "select_n(${classified.reason}) candidates=${pendingPrompt.request.candidateRefs.size}",
                )
                if (classified.reason == ClassifiedPrompt.SelectN.Reason.Sacrifice) {
                    sendSacrificePayCostsReq(classified.pendingPrompt)
                } else {
                    sendSelectNReq(classified.pendingPrompt, classified.reason)
                }
                PromptResult.SENT_TO_CLIENT
            }

            is ClassifiedPrompt.Targeting -> {
                tracer.traceEvent(MatchEventType.TARGET_PROMPT, game, "targets=${pendingPrompt.request.candidateRefs.size}")
                sendSelectTargetsReq(classified.pendingPrompt)
                PromptResult.SENT_TO_CLIENT
            }

            is ClassifiedPrompt.Search -> {
                tracer.traceEvent(MatchEventType.TARGET_PROMPT, game, "search: ${pendingPrompt.request.message}")
                sendSearchReq(classified.pendingPrompt)
                PromptResult.SENT_TO_CLIENT
            }

            is ClassifiedPrompt.AutoResolve -> {
                val req = pendingPrompt.request
                log.info(
                    "TargetingHandler: auto-resolving non-targeting prompt [{}] \"{}\" opts={} default={}",
                    req.promptType,
                    req.message,
                    req.options.size,
                    req.defaultIndex,
                )
                tracer.traceEvent(
                    MatchEventType.AUTO_PASS,
                    game,
                    "auto-resolve prompt [${req.promptType}] default=${req.defaultIndex}",
                )
                seatBridge.prompt.submitResponse(pendingPrompt.promptId, listOf(req.defaultIndex))
                bridge.awaitPriority()
                PromptResult.AUTO_RESOLVED
            }
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
                    if (req.max == 1 && req.options.size == 2) {
                        // Single-card surveil/scry: "Top of library" (0) vs "Graveyard"/"Bottom" (1)
                        // Group 1 (away zone) has the card → user chose "away" → index 1
                        val awayGroup = if (groups.size >= 2) groups[1] else null
                        if (awayGroup != null && awayGroup.idsList.isNotEmpty()) {
                            listOf(1) // away (graveyard for surveil, bottom for scry)
                        } else {
                            listOf(0) // keep on top
                        }
                    } else {
                        // Multi-card surveil/scry: away group IDs → indices into options
                        val awayIds = if (groups.size >= 2) groups[1].idsList else emptyList()
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

                else -> listOf(req.defaultIndex)
            }

        log.info("TargetingHandler: GroupResp → prompt indices={}", selectedIndices)

        seatBridge.prompt.submitResponse(pendingPrompt.promptId, selectedIndices)
        bridge.awaitPriority()

        // Send intermediate state so the client sees the zone transfer
        // (card moving to graveyard or staying on top of library).
        sink.sendRealGameState(bridge)
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
            val responseIndex =
                if (itemsFound.isEmpty()) {
                    // Declined — submit index past the last option (= "none")
                    log.info("SearchResp: player declined (fail to find)")
                    prompt.request.options.size
                } else {
                    // Map instanceId back to prompt option index via candidateRefs
                    // TODO: multi-pick support — currently only maps first selected card.
                    //  Future spells with maxFind > 1 will silently ignore subsequent picks.
                    val chosenInstanceId = itemsFound.first()
                    val cardId = bridge.getForgeCardId(InstanceId(chosenInstanceId))
                    val idx =
                        if (cardId != null) {
                            prompt.request.candidateRefs.indexOfFirst { it.entityId == cardId.value }
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
            seatBridge.prompt.submitResponse(pending.promptId, listOf(responseIndex))
            bridge.awaitPriority()
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
            pendingPrompt.request.candidateRefs.indexOfFirst {
                it.kind == "player" && it.entityId == player.id
            }
        return if (idx >= 0) idx else null
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
        pendingInteraction = PendingClientInteraction.ModalChoice(pendingPrompt.promptId, effectiveChildGrpIds)

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
            }

            else -> {
                log.warn("TargetingHandler: CastingTimeOptionsResp but no pending modal or optional cost (likely timeout race)")
                DevCheck.failOnAutoPass { "CastingTimeOptionsResp but no pending modal or optional cost" }
            }
        }
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
        // Drop any stale keyword-cost stash from a prior cast: every
        // `checkOptionalCosts` invocation either emits a fresh CTO (and
        // overwrites the stash via `onOptionalCostResponse`) or no CTO at
        // all. Without this clear, a prior cast's stale `Offspring=true`
        // leaks into a subsequent non-CTO `chooseNumberForKeywordCost` call
        // (e.g., a triggered cast that doesn't go through this gate).
        bridge
            .seat(counters.seatId)
            .prompt.journal
            .clearKeywordCostStash()

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
        if (action.grpId != EATEN_ALIVE_GRP_ID) return false
        val bridge = ctx.bridge
        val game = ctx.game
        val cardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return false
        val card = game.findById(cardId.value) ?: return false
        if (card.keywords.none { it.original.startsWith("AlternateAdditionalCost") }) return false

        val player = bridge.getPlayer(counters.seatId) ?: return false
        val castable = leyline.bridge.getAllCastableAbilities(card, player)
        if (castable.size <= 1) return false

        val optionPromptIds: List<Int> =
            when (action.grpId) {
                EATEN_ALIVE_GRP_ID ->
                    listOf(
                        PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE,
                        PromptIds.CHOOSE_OR_COST_PAY_MANA,
                    )
                else -> emptyList()
            }

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

        // Source instanceId — for activated-ability searches (cycling,
        // typecycling) the protocol expects the AB instance iid here, not
        // the host card iid. Mint via the same SA-id-keyed surrogate the
        // AbilityInstance lifecycle uses so SearchReq.sourceId, the prompt's
        // CardId parameter, and the existing AbilityInstanceCreated annotation
        // all reference the same iid. Falls back to host card iid for plain
        // spell searches (sa is a Spell, not an Ability gameObject).
        val stackTop = ctx.game.stack.firstOrNull()
        val saId = stackTop?.spellAbility?.id
        val isAbilityOnStack = stackTop?.isAbility == true
        val sourceId =
            when {
                isAbilityOnStack && saId != null -> {
                    val abForgeId = FrameIdResolver.triggerStackAbilityForgeId(saId)
                    bridge.getOrAllocInstanceId(abForgeId).value
                }
                req.sourceEntityId != null ->
                    bridge.getOrAllocInstanceId(ForgeCardId(req.sourceEntityId)).value
                stackTop != null ->
                    bridge.getOrAllocInstanceId(ForgeCardId(stackTop.id)).value
                else -> 0
            }

        val msgId = counters.counter.nextMsgId()
        val gsId = counters.counter.currentGsId()
        val msg =
            bundles.bundleBuilder.buildSearchReq(
                msgId = msgId,
                gsId = gsId,
                sourceInstanceId = sourceId,
                seatId = counters.seatId.value,
                libraryZoneId = libZoneId,
                allLibraryIds = allLibIds,
                validTargetIds = validIds,
                maxFind = req.max,
                allowFailToFind = req.min == 0,
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
        reason: ClassifiedPrompt.SelectN.Reason,
    ) {
        val game = ctx.game
        val bb = bundles.bundleBuilder
        val req = bb.buildSelectNReq(pendingPrompt)
        val result =
            bb.selectNBundle(
                game,
                counters.counter,
                req,
                isLegendRule = reason == ClassifiedPrompt.SelectN.Reason.LegendRule,
                isRevealChoose = reason == ClassifiedPrompt.SelectN.Reason.RevealChoose,
                isResolution = reason == ClassifiedPrompt.SelectN.Reason.Resolution,
            )
        Tap.outboundTemplate("SelectNReq seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    private fun sendSacrificePayCostsReq(pendingPrompt: InteractivePromptBridge.PendingPrompt) {
        val bridge = ctx.bridge
        val (req, prompt) = RequestBuilder.buildSacrificePayCostsReq(pendingPrompt, bridge)
        val result = bundles.bundleBuilder.payCostsBundle(ctx.game, counters.counter, req, prompt)
        Tap.outboundTemplate("PayCostsReq(sacrifice) seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    private fun sendExileFromGravePayCostsReq(pendingPrompt: InteractivePromptBridge.PendingPrompt) {
        val bridge = ctx.bridge
        val (req, prompt) =
            RequestBuilder.buildSelectCostPayCostsReq(
                pendingPrompt,
                bridge,
                leyline.game.mapping.PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE,
            )
        val result = bundles.bundleBuilder.payCostsBundle(ctx.game, counters.counter, req, prompt)
        Tap.outboundTemplate("PayCostsReq(exile-from-grave) seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
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

    /** Submit default response and wait — used when modal lookup fails. */
    private fun autoResolvePrompt(prompt: InteractivePromptBridge.PendingPrompt) {
        val bridge = ctx.bridge
        bridge.seat(counters.seatId).prompt.submitResponse(prompt.promptId, listOf(prompt.request.defaultIndex))
        bridge.awaitPriority()
    }
}
