package leyline.match

import forge.game.Game
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.bridge.InteractivePromptBridge
import leyline.bridge.SeatId
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import leyline.game.GsmBuilder
import leyline.game.RequestBuilder
import leyline.game.mapper.ObjectMapper
import leyline.game.mapper.ZoneIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles targeting-related client messages and prompt detection.
 *
 * Extracted from [MatchSession] for independent testability.
 * Uses [SessionOps] for message sending and tracing. Protocol sequencing
 * uses the shared [MessageCounter][leyline.game.MessageCounter] via
 * `ops.counter` — no seeding or syncing needed.
 */
class TargetingHandler(private val ops: SessionOps) {
    private val log = LoggerFactory.getLogger(TargetingHandler::class.java)

    @Volatile
    private var pendingInteraction: PendingClientInteraction? = null

    /**
     * Handle SelectTargetsResp (phase 1): store selection, send echo-back re-prompt.
     *
     * Does NOT submit to engine — waits for [onSubmitTargets] (SubmitTargetsReq).
     * The echo-back re-prompt reflects the selection per real server wire spec:
     * only selected targets, legalAction=Unselect, selectedTargets count set.
     *
     * Player targets use seatId (1/2) as instanceId.
     * Wire spec: docs/plans/2026-03-14-submit-targets-wire-spec.md
     */
    fun onSelectTargets(
        greMsg: ClientToGREMessage,
        bridge: GameBridge,
    ) {
        val seatBridge = bridge.seat(ops.seatId)
        val resp = greMsg.selectTargetsResp
        val pendingPrompt = seatBridge.prompt.getPendingPrompt() ?: run {
            log.warn("TargetingHandler: SelectTargetsResp but no pending prompt")
            return
        }

        val selectedTarget = resp.target
        val selectedInstanceIds = selectedTarget.targetsList.map { it.targetInstanceId }
        val selectedIndices = selectedTarget.targetsList.mapNotNull { target ->
            val instanceId = target.targetInstanceId
            val playerIdx = resolvePlayerTarget(instanceId, bridge, pendingPrompt)
            if (playerIdx != null) return@mapNotNull playerIdx
            val forgeCardId = bridge.getForgeCardId(InstanceId(instanceId)) ?: return@mapNotNull null
            pendingPrompt.request.candidateRefs.indexOfFirst { it.entityId == forgeCardId.value }
        }.filter { it >= 0 }

        log.info("TargetingHandler: SelectTargetsResp iids={} indices={} (awaiting SubmitTargetsReq)", selectedInstanceIds, selectedIndices)

        pendingInteraction = PendingClientInteraction.TargetSelection(pendingPrompt.promptId, selectedIndices)

        // Echo-back: actions-only GSM diff + re-prompt with selection reflected
        val game = bridge.getGame() ?: return
        val gsId = ops.counter.nextGsId()
        val echoDiff = ops.makeGRE(GREMessageType.GameStateMessage_695e, gsId, ops.counter.nextMsgId()) {
            it.gameStateMessage = GameStateMessage.newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gsId)
                .setPrevGameStateId(gsId - 1)
                .build()
        }
        val rePrompt = RequestBuilder.buildSelectTargetsRePrompt(pendingPrompt, bridge, selectedInstanceIds, ops.seatId)
        val rePromptMsg = ops.makeGRE(GREMessageType.SelectTargetsReq_695e, gsId, ops.counter.nextMsgId()) {
            it.selectTargetsReq = rePrompt
        }
        Tap.outboundTemplate("SelectTargetsReq re-prompt seat=${ops.seatId}")
        ops.sendBundledGRE(listOf(echoDiff, rePromptMsg))
    }

    /**
     * Handle SubmitTargetsReq (phase 2): submit stored selection to engine.
     *
     * Type-only message (no payload). Uses selection stored by [onSelectTargets].
     * Wire spec: docs/plans/2026-03-14-submit-targets-wire-spec.md
     */
    fun onSubmitTargets(
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val pending = pendingInteraction as? PendingClientInteraction.TargetSelection
        if (pending == null) {
            log.warn("TargetingHandler: SubmitTargetsReq but no pending target selection")
            return
        }
        pendingInteraction = null

        log.info("TargetingHandler: SubmitTargetsReq — submitting indices={}", pending.selectedIndices)

        ops.sendBundledGRE(
            listOf(
                ops.makeGRE(GREMessageType.SubmitTargetsResp_695e, ops.counter.currentGsId(), ops.counter.nextMsgId()) {
                    it.submitTargetsResp = SubmitTargetsResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        bridge.seat(ops.seatId).prompt.submitResponse(pending.promptId, pending.selectedIndices)
        bridge.awaitPriority()
        autoPass(bridge)
    }

    /**
     * Handle SelectNResp: map client instanceIds back to prompt option indices and submit.
     * Mirrors [onSelectTargets] but for "choose N cards" prompts.
     */
    fun onSelectN(
        greMsg: ClientToGREMessage,
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val seatBridge = bridge.seat(ops.seatId)
        val resp = greMsg.selectNResp
        val pendingPrompt = seatBridge.prompt.getPendingPrompt() ?: run {
            log.warn("TargetingHandler: SelectNResp but no pending prompt")
            return
        }

        val selectedIndices = resp.idsList.mapNotNull { instanceId ->
            val forgeCardId = bridge.getForgeCardId(InstanceId(instanceId))
            if (forgeCardId == null) return@mapNotNull null
            pendingPrompt.request.candidateRefs.indexOfFirst { it.entityId == forgeCardId.value }
        }.filter { it >= 0 }

        log.info("TargetingHandler: SelectNResp indices={}", selectedIndices)

        seatBridge.prompt.submitResponse(pendingPrompt.promptId, selectedIndices)
        bridge.awaitPriority()
        autoPass(bridge)
    }

    /**
     * After a cast, check for a pending targeting prompt or intermediate stack state.
     * Returns true if handled (caller should return), false to continue normal flow.
     *
     * @param clientAutoResolve true when the client's autoPassOption signals
     *   "resolve my stack effects" — skips the stack prompt when the player has
     *   no meaningful responses, matching real server behavior (#92).
     */
    fun handlePostCastPrompt(bridge: GameBridge, clientAutoResolve: Boolean = false): Boolean {
        val pendingPrompt = bridge.seat(ops.seatId).prompt.getPendingPrompt()
        if (pendingPrompt != null) {
            when (val classified = PromptClassifier.classify(pendingPrompt)) {
                is ClassifiedPrompt.ModalChoice -> {
                    val game = bridge.getGame() ?: return false
                    ops.traceEvent(MatchEventType.TARGET_PROMPT, game, "post-cast modal: ${pendingPrompt.request.message}")
                    sendCastingTimeOptionsReq(bridge, classified.pendingPrompt)
                    return true
                }

                is ClassifiedPrompt.Targeting -> {
                    val game = bridge.getGame() ?: return false
                    ops.traceEvent(
                        MatchEventType.TARGET_PROMPT,
                        game,
                        "cast-target targets=${pendingPrompt.request.candidateRefs.size}",
                    )
                    sendSelectTargetsReq(bridge, classified.pendingPrompt)
                    return true
                }

                else -> {}
            }
        }
        val g = bridge.getGame()
        if (g != null && !g.stack.isEmpty) {
            // When auto-resolve is active and the player has no meaningful responses
            // (only Pass), skip the prompt — let autoPassAndAdvance() handle stack
            // resolution transparently, matching the real server (#92).
            if (clientAutoResolve && BundleBuilder.shouldAutoPass(BundleBuilder.buildActions(g, ops.seatId, bridge))) {
                return false
            }
            ops.sendRealGameState(bridge)
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
    fun checkPendingPrompt(bridge: GameBridge, game: Game): PromptResult {
        val seatBridge = bridge.seat(ops.seatId)
        val pendingPrompt = seatBridge.prompt.getPendingPrompt() ?: return PromptResult.NONE
        val classified = PromptClassifier.classify(pendingPrompt)

        return when (classified) {
            is ClassifiedPrompt.Grouping -> {
                sendGroupReqForSurveilScry(bridge, classified.pendingPrompt, classified.context)
                PromptResult.SENT_TO_CLIENT
            }

            is ClassifiedPrompt.ModalChoice -> {
                ops.traceEvent(MatchEventType.TARGET_PROMPT, game, "modal: ${pendingPrompt.request.message}")
                sendCastingTimeOptionsReq(bridge, classified.pendingPrompt)
                PromptResult.SENT_TO_CLIENT
            }

            is ClassifiedPrompt.SelectN -> {
                ops.traceEvent(
                    MatchEventType.TARGET_PROMPT,
                    game,
                    "legend_rule candidates=${pendingPrompt.request.candidateRefs.size}",
                )
                sendSelectNReq(bridge, classified.pendingPrompt)
                PromptResult.SENT_TO_CLIENT
            }

            is ClassifiedPrompt.Targeting -> {
                ops.traceEvent(MatchEventType.TARGET_PROMPT, game, "targets=${pendingPrompt.request.candidateRefs.size}")
                sendSelectTargetsReq(bridge, classified.pendingPrompt)
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
                ops.traceEvent(
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
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val seatBridge = bridge.seat(ops.seatId)
        val pendingPrompt = seatBridge.prompt.getPendingPrompt() ?: run {
            log.warn("TargetingHandler: GroupResp but no pending prompt")
            return
        }

        val groups = greMsg.groupResp.groupsList
        val req = pendingPrompt.request

        val selectedIndices = if (req.promptType == "confirm" && req.options.size == 2) {
            // Single-card surveil/scry: "Top of library" (0) vs "Graveyard"/"Bottom" (1)
            // Group 1 (away zone) has the card → user chose "away" → index 1
            val awayGroup = if (groups.size >= 2) groups[1] else null
            if (awayGroup != null && awayGroup.idsList.isNotEmpty()) {
                listOf(1) // away (graveyard for surveil, bottom for scry)
            } else {
                listOf(0) // keep on top
            }
        } else if (req.promptType == "choose_cards") {
            // Multi-card surveil/scry: away group IDs → indices into options
            val awayIds = if (groups.size >= 2) groups[1].idsList else emptyList()
            awayIds.mapNotNull { iid ->
                val forgeCardId = bridge.getForgeCardId(InstanceId(iid)) ?: return@mapNotNull null
                // Options are card names from topN — match by forge card name
                val player = bridge.getPlayer(SeatId(ops.seatId)) ?: return@mapNotNull null
                val card = player.allCards.firstOrNull { it.id == forgeCardId.value }
                card?.let { req.options.indexOf(it.name) }
            }.filter { it >= 0 }
        } else {
            listOf(req.defaultIndex)
        }

        log.info("TargetingHandler: GroupResp → prompt indices={}", selectedIndices)

        seatBridge.prompt.submitResponse(pendingPrompt.promptId, selectedIndices)
        bridge.awaitPriority()

        // Send intermediate state so the client sees the zone transfer
        // (card moving to graveyard or staying on top of library).
        ops.sendRealGameState(bridge)
        autoPass(bridge)
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
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val seatBridge = bridge.seat(ops.seatId)
        val pendingPrompt = seatBridge.prompt.getPendingPrompt()
        if (pendingPrompt == null) {
            log.warn("TargetingHandler: CancelActionReq but no pending prompt")
            return
        }

        log.info("TargetingHandler: CancelActionReq — submitting empty targets to unwind spell")

        // Submit empty list → engine sees no targets → spell fails → unwind
        seatBridge.prompt.submitResponse(pendingPrompt.promptId, emptyList())
        bridge.awaitPriority()
        autoPass(bridge)
    }

    // --- Helpers ---

    /**
     * Resolve a player target: if [instanceId] is a seatId (1 or 2), find the
     * matching `kind="player"` candidateRef in the pending prompt.
     * Returns the candidateRef index, or null if this isn't a player target.
     */
    private fun resolvePlayerTarget(
        instanceId: Int,
        bridge: GameBridge,
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ): Int? {
        // Arena uses seatId as instanceId for player targets (1 or 2)
        val player = bridge.getPlayer(SeatId(instanceId)) ?: return null
        val idx = pendingPrompt.request.candidateRefs.indexOfFirst {
            it.kind == "player" && it.entityId == player.id
        }
        return if (idx >= 0) idx else null
    }

    /**
     * Build and send CastingTimeOptionsReq for a modal prompt.
     * Looks up card grpId and modal option grpIds from CardRepository,
     * saves PendingModal state for response mapping.
     */
    private fun sendCastingTimeOptionsReq(
        bridge: GameBridge,
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) {
        val game = bridge.getGame() ?: return
        val req = pendingPrompt.request
        val cardName = req.modalSourceCardName
        if (cardName == null) {
            log.warn("TargetingHandler: modal prompt but no modalSourceCardName, auto-resolving")
            autoResolvePrompt(bridge, pendingPrompt)
            return
        }

        // Look up card grpId and modal options
        val cardGrpId = bridge.cards.findGrpIdByName(cardName)
        if (cardGrpId == null) {
            log.warn("TargetingHandler: card '{}' not in card DB, auto-resolving modal", cardName)
            autoResolvePrompt(bridge, pendingPrompt)
            return
        }

        val modalInfo = bridge.cards.lookupModalOptions(cardGrpId)
        if (modalInfo == null) {
            log.warn("TargetingHandler: no modal options for grpId={}, auto-resolving", cardGrpId)
            autoResolvePrompt(bridge, pendingPrompt)
            return
        }

        // Resolve source instanceId from sourceEntityId (forge card ID)
        val sourceInstanceId = if (req.sourceEntityId != null) {
            bridge.getOrAllocInstanceId(ForgeCardId(req.sourceEntityId)).value
        } else {
            0
        }

        // Build ModalReq
        val modalReq = ModalReq.newBuilder()
            .setAbilityGrpId(modalInfo.parentGrpId)
            .setMinSel(req.min)
            .setMaxSel(req.max)
        for (childGrpId in modalInfo.childGrpIds) {
            modalReq.addModalOptions(ModalOption.newBuilder().setGrpId(childGrpId))
        }

        // Build CastingTimeOptionsReq
        val ctoReq = CastingTimeOptionsReq.newBuilder()
            .addCastingTimeOptionReq(
                CastingTimeOptionReq.newBuilder()
                    .setCtoId(1)
                    .setCastingTimeOptionType(CastingTimeOptionType.Modal_a7b4)
                    .setAffectedId(sourceInstanceId)
                    .setAffectorId(sourceInstanceId)
                    .setGrpId(cardGrpId)
                    .setIsRequired(true)
                    .setModalReq(modalReq),
            )
            .build()

        // Save pending state for response mapping
        pendingInteraction = PendingClientInteraction.ModalChoice(pendingPrompt.promptId, modalInfo.childGrpIds)

        val result = BundleBuilder.castingTimeOptionsBundle(game, bridge, ops.matchId, ops.seatId, ops.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq seat=${ops.seatId} card=$cardName")
        ops.sendBundledGRE(result.messages)
    }

    /**
     * Handle CastingTimeOptionsResp: dispatches to modal or kicker/optional cost handler.
     */
    fun onCastingTimeOptions(
        greMsg: ClientToGREMessage,
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        when (val pending = pendingInteraction) {
            is PendingClientInteraction.OptionalCost -> {
                pendingInteraction = null
                onOptionalCostResponse(greMsg, bridge, pending, autoPass)
                return
            }

            is PendingClientInteraction.ModalChoice -> {
                val resp = greMsg.castingTimeOptionsResp
                val chosenGrpIds = resp.castingTimeOptionResp.chooseModalResp.grpIdsList

                val selectedIndices = chosenGrpIds.mapNotNull { grpId ->
                    pending.childGrpIds.indexOf(grpId).takeIf { it >= 0 }
                }

                log.info("TargetingHandler: CastingTimeOptionsResp (modal) grpIds={} → indices={}", chosenGrpIds, selectedIndices)

                bridge.seat(ops.seatId).prompt.submitResponse(pending.promptId, selectedIndices)
                pendingInteraction = null
                bridge.awaitPriority()
                autoPass(bridge)
            }

            else -> {
                log.warn("TargetingHandler: CastingTimeOptionsResp but no pending modal or optional cost")
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
        bridge: GameBridge,
    ): Boolean {
        val forgeCardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return false
        val game = bridge.getGame() ?: return false
        val card = game.findById(forgeCardId.value) ?: return false

        // Find the castable SpellAbility to check for optional costs
        val sa = card.spellAbilities?.firstOrNull { it.isSpell && !it.isLandAbility } ?: return false
        sa.setActivatingPlayer(bridge.getPlayer(SeatId(ops.seatId)) ?: return false)

        val optionalCosts = forge.game.GameActionUtil.getOptionalCostValues(sa)
        if (optionalCosts.isEmpty()) return false

        log.info("TargetingHandler: card '{}' has {} optional costs — sending prompt", card.name, optionalCosts.size)

        // Build CastingTimeOptionsReq with one entry per optional cost + Done
        val instanceId = action.instanceId
        val ctoReqBuilder = CastingTimeOptionsReq.newBuilder()
        val costCtoIds = mutableListOf<Int>()

        for ((i, cost) in optionalCosts.withIndex()) {
            val ctoId = i + 1 // 1-based; 0 is reserved for Done
            costCtoIds.add(ctoId)

            // Map OptionalCost type to CastingTimeOptionType
            val ctoType = when (cost.type) {
                forge.game.spellability.OptionalCost.Kicker1,
                forge.game.spellability.OptionalCost.Kicker2,
                -> CastingTimeOptionType.Kicker
                forge.game.spellability.OptionalCost.Buyback -> CastingTimeOptionType.AdditionalCost
                forge.game.spellability.OptionalCost.Entwine -> CastingTimeOptionType.AdditionalCost
                else -> CastingTimeOptionType.OptionalCost
            }

            // Find the abilityGrpId for this cost from card data
            val cardData = bridge.cards.findByGrpId(action.grpId)
            val abilityGrpId = cardData?.abilityIds?.getOrNull(
                (cardData.keywordAbilityGrpIds.size) + i,
            )?.first ?: 0

            ctoReqBuilder.addCastingTimeOptionReq(
                CastingTimeOptionReq.newBuilder()
                    .setCtoId(ctoId)
                    .setCastingTimeOptionType(ctoType)
                    .setAffectedId(instanceId)
                    .setAffectorId(instanceId)
                    .setGrpId(abilityGrpId),
            )
        }

        // Add Done option (ctoId=0, required)
        ctoReqBuilder.addCastingTimeOptionReq(
            CastingTimeOptionReq.newBuilder()
                .setCtoId(0)
                .setCastingTimeOptionType(CastingTimeOptionType.Done)
                .setIsRequired(true),
        )

        // Stash the Cast action for replay after response
        pendingInteraction = PendingClientInteraction.OptionalCost(
            pendingActionId = pendingActionId,
            action = leyline.bridge.PlayerAction.CastSpell(forgeCardId),
            costCtoIds = costCtoIds,
        )

        // Send prompt
        val result = BundleBuilder.castingTimeOptionsBundle(
            game,
            bridge,
            ops.matchId,
            ops.seatId,
            ops.counter,
            ctoReqBuilder.build(),
        )
        Tap.outboundTemplate("CastingTimeOptionsReq (optional costs) seat=${ops.seatId} card=${card.name}")
        ops.sendBundledGRE(result.messages)
        return true
    }

    /**
     * Handle CastingTimeOptionsResp for optional costs (kicker, buyback, etc.).
     * Stores chosen cost indices, then submits the Cast action to the engine.
     */
    private fun onOptionalCostResponse(
        greMsg: ClientToGREMessage,
        bridge: GameBridge,
        pending: PendingClientInteraction.OptionalCost,
        autoPass: (GameBridge) -> Unit,
    ) {
        // Check which optional costs the client chose
        val resp = greMsg.castingTimeOptionsResp
        val chosenCtoId = resp.castingTimeOptionResp?.ctoId ?: 0

        // ctoId=0 means Done (declined all costs)
        // ctoId>0 means accepted that cost
        val accepted = chosenCtoId != 0 && chosenCtoId in pending.costCtoIds
        val acceptedIndices = if (accepted) {
            // For now, accept all costs up to the chosen one (single kicker = index 0)
            listOf(chosenCtoId - 1) // 1-based ctoId → 0-based index
        } else {
            emptyList()
        }

        log.info("TargetingHandler: optional cost response ctoId={} accepted={} indices={}", chosenCtoId, accepted, acceptedIndices)

        // Stash decision for WebPlayerController.chooseOptionalCosts to read
        val seatBridge = bridge.seat(ops.seatId)
        seatBridge.prompt.stashedOptionalCostIndices = acceptedIndices

        // Now submit the Cast action to the engine
        val actionBridge = seatBridge.action
        val pendingAction = actionBridge.getPending()
        if (pendingAction != null) {
            actionBridge.submitAction(pendingAction.actionId, pending.action)
            bridge.awaitPriority()
            autoPass(bridge)
        } else {
            log.warn("TargetingHandler: optional cost response but no pending engine action")
        }
    }

    private fun sendSelectTargetsReq(
        bridge: GameBridge,
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.selectTargetsBundle(game, bridge, ops.matchId, ops.seatId, ops.counter, pendingPrompt)
        Tap.outboundTemplate("SelectTargetsReq seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
    }

    private fun sendSelectNReq(
        bridge: GameBridge,
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) {
        val game = bridge.getGame() ?: return
        val isLegendRule = pendingPrompt.request.promptType == "legend_rule"
        val req = BundleBuilder.buildSelectNReq(pendingPrompt, bridge)
        val result = BundleBuilder.selectNBundle(game, bridge, ops.matchId, ops.seatId, ops.counter, req, isLegendRule = isLegendRule)
        Tap.outboundTemplate("SelectNReq seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
    }

    /**
     * Build and send a GroupReq for surveil/scry. Looks up instanceIds for
     * the cards being surveilled from the library top.
     *
     * Real server sends a GSM diff that exposes the library top card(s) as
     * `visibility=Private, viewers=[seatId]` before the GroupReq — this makes
     * the card visible (face-up) in the client's surveil/scry modal.
     */
    private fun sendGroupReqForSurveilScry(
        bridge: GameBridge,
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        context: GroupingContext,
    ) {
        val game = bridge.getGame() ?: return
        val player = bridge.getPlayer(SeatId(ops.seatId)) ?: return
        val req = pendingPrompt.request

        // Resolve surveil/scry cards from candidateRefs (forge card IDs set by WebPlayerController).
        // We can't read library top here — the engine already removed the card from the zone
        // while blocking in requestChoice(). CandidateRefs carry the correct forge card IDs.
        // Use Game.findById() which visits all cards including those in limbo (no zone).
        data class ResolvedCard(val card: forge.game.card.Card, val instanceId: Int)

        val resolved = req.candidateRefs
            .filter { it.kind == "card" }
            .mapNotNull { ref ->
                val card = game.findById(ref.entityId)
                if (card != null) ResolvedCard(card, bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value) else null
            }

        if (resolved.isEmpty()) {
            log.warn("TargetingHandler: surveil/scry but no cards resolved from candidateRefs (falling back to library top)")
            bridge.seat(ops.seatId).prompt.submitResponse(pendingPrompt.promptId, listOf(req.defaultIndex))
            bridge.awaitPriority()
            return
        }

        val topCards = resolved.map { it.card }
        val cardInstanceIds = resolved.map { it.instanceId }

        // sourceId: the card that triggered surveil — check stack for the trigger source
        val sourceId = game.stack.firstOrNull()?.let { bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value } ?: 0

        val contextLabel = if (context == GroupingContext.Surveil) "Surveil" else "Scry"
        log.info("TargetingHandler: sending GroupReq for {} cards={}", contextLabel, cardInstanceIds)
        ops.traceEvent(MatchEventType.TARGET_PROMPT, game, "$contextLabel GroupReq cards=${cardInstanceIds.size}")

        // Build GSM diff that reveals card objects (Private + viewer) so client shows face-up
        val libZoneId = if (ops.seatId == 1) ZoneIds.P1_LIBRARY else ZoneIds.P2_LIBRARY
        val revealedObjects = topCards.map { card ->
            ObjectMapper.buildCardObject(card, bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value, libZoneId, ops.seatId, bridge, Visibility.Private)
                .toBuilder().addViewers(ops.seatId).build()
        }
        val gsId = ops.counter.nextGsId()
        val revealDiff = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .addSystemSeatIds(ops.seatId)
            .setMsgId(ops.counter.nextMsgId())
            .setGameStateId(gsId)
            .setGameStateMessage(
                GameStateMessage.newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(gsId)
                    .setPrevGameStateId(gsId - 1)
                    .addAllGameObjects(revealedObjects),
            )
            .build()

        val groupReq = GsmBuilder.buildSurveilScryGroupReq(
            msgId = ops.counter.nextMsgId(),
            gameStateId = gsId,
            seatId = ops.seatId,
            cardInstanceIds = cardInstanceIds,
            context = context,
            sourceInstanceId = sourceId,
        )
        Tap.outboundTemplate("GroupReq($contextLabel) seat=${ops.seatId}")
        ops.sendBundledGRE(listOf(revealDiff, groupReq))
    }

    /** Submit default response and wait — used when modal lookup fails. */
    private fun autoResolvePrompt(bridge: GameBridge, prompt: InteractivePromptBridge.PendingPrompt) {
        bridge.seat(ops.seatId).prompt.submitResponse(prompt.promptId, listOf(prompt.request.defaultIndex))
        bridge.awaitPriority()
    }
}
