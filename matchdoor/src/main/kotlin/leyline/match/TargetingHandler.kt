package leyline.match

import forge.game.Game
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.bridge.InteractivePromptBridge
import leyline.bridge.SeatId
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import leyline.game.GsmBuilder
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

    /** Saved state for pending modal prompt — maps response grpIds back to prompt indices. */
    data class PendingModal(val promptId: String, val childGrpIds: List<Int>)

    /** Saved state for two-phase targeting — stores selection between SelectTargetsResp and SubmitTargetsReq. */
    data class PendingTargetSelection(val promptId: String, val selectedIndices: List<Int>)

    @Volatile
    private var pendingModal: PendingModal? = null

    @Volatile
    private var pendingTargetSelection: PendingTargetSelection? = null

    /**
     * Handle SelectTargetsResp (phase 1): map client instanceIds to prompt indices,
     * store selection, send echo-back GSM + re-prompt SelectTargetsReq.
     * Does NOT submit to engine — waits for [onSubmitTargets].
     *
     * Player targets use seatId (1/2) as instanceId — these won't be in the card
     * instanceId registry, so we check for player refs first.
     * See `docs/plans/player-targeting.md`.
     */
    fun onSelectTargets(
        greMsg: ClientToGREMessage,
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val resp = greMsg.selectTargetsResp
        val pendingPrompt = bridge.promptBridge.getPendingPrompt() ?: run {
            log.warn("TargetingHandler: SelectTargetsResp but no pending prompt")
            return
        }

        val selectedTarget = resp.target
        val selectedIndices = selectedTarget.targetsList.mapNotNull { target ->
            val instanceId = target.targetInstanceId
            val playerIdx = resolvePlayerTarget(instanceId, bridge, pendingPrompt)
            if (playerIdx != null) return@mapNotNull playerIdx
            val forgeCardId = bridge.getForgeCardId(InstanceId(instanceId)) ?: return@mapNotNull null
            pendingPrompt.request.candidateRefs.indexOfFirst { it.entityId == forgeCardId.value }
        }.filter { it >= 0 }

        log.info("TargetingHandler: SelectTargetsResp indices={} (stored, awaiting SubmitTargetsReq)", selectedIndices)

        // Store selection for phase 2
        pendingTargetSelection = PendingTargetSelection(pendingPrompt.promptId, selectedIndices)

        // Send echo-back: actions-only GSM diff + re-prompt SelectTargetsReq
        sendTargetEchoBack(bridge, pendingPrompt)
    }

    /**
     * Handle SubmitTargetsReq (phase 2): submit stored target selection to engine.
     * No payload — uses selection stored by [onSelectTargets].
     */
    fun onSubmitTargets(
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val pending = pendingTargetSelection
        if (pending == null) {
            log.warn("TargetingHandler: SubmitTargetsReq but no pending selection")
            return
        }
        pendingTargetSelection = null

        log.info("TargetingHandler: SubmitTargetsReq — submitting indices={}", pending.selectedIndices)

        ops.sendBundledGRE(
            listOf(
                ops.makeGRE(GREMessageType.SubmitTargetsResp_695e, ops.counter.currentGsId(), ops.counter.nextMsgId()) {
                    it.submitTargetsResp = SubmitTargetsResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        bridge.promptBridge.submitResponse(pending.promptId, pending.selectedIndices)
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
        val resp = greMsg.selectNResp
        val pendingPrompt = bridge.promptBridge.getPendingPrompt() ?: run {
            log.warn("TargetingHandler: SelectNResp but no pending prompt")
            return
        }

        val selectedIndices = resp.idsList.mapNotNull { instanceId ->
            val forgeCardId = bridge.getForgeCardId(InstanceId(instanceId))
            if (forgeCardId == null) return@mapNotNull null
            pendingPrompt.request.candidateRefs.indexOfFirst { it.entityId == forgeCardId.value }
        }.filter { it >= 0 }

        log.info("TargetingHandler: SelectNResp indices={}", selectedIndices)

        bridge.promptBridge.submitResponse(pendingPrompt.promptId, selectedIndices)
        bridge.awaitPriority()
        autoPass(bridge)
    }

    /**
     * After a cast, check for a pending targeting prompt or intermediate stack state.
     * Returns true if handled (caller should return), false to continue normal flow.
     */
    fun handlePostCastPrompt(bridge: GameBridge): Boolean {
        val pendingPrompt = bridge.promptBridge.getPendingPrompt()
        if (pendingPrompt != null) {
            // Modal prompt (ETB trigger fires during resolution)
            if (pendingPrompt.request.promptType == "modal") {
                val game = bridge.getGame() ?: return false
                ops.traceEvent(MatchEventType.TARGET_PROMPT, game, "post-cast modal: ${pendingPrompt.request.message}")
                sendCastingTimeOptionsReq(bridge, pendingPrompt)
                return true
            }
            // Targeting prompt
            if (pendingPrompt.request.candidateRefs.isNotEmpty()) {
                val game = bridge.getGame() ?: return false
                ops.traceEvent(
                    MatchEventType.TARGET_PROMPT,
                    game,
                    "cast-target targets=${pendingPrompt.request.candidateRefs.size}",
                )
                sendSelectTargetsReq(bridge, pendingPrompt)
                return true
            }
        }
        val g = bridge.getGame()
        if (g != null && !g.stack.isEmpty) {
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
        val pendingPrompt = bridge.promptBridge.getPendingPrompt() ?: return PromptResult.NONE

        // Surveil/scry prompts: check before candidateRefs — surveil now carries
        // candidateRefs for card identity, but should route to GroupReq not SelectTargetsReq.
        val groupingContext = detectSurveilScry(pendingPrompt.request)
        if (groupingContext != null) {
            sendGroupReqForSurveilScry(bridge, pendingPrompt, groupingContext)
            return PromptResult.SENT_TO_CLIENT
        }

        // Modal prompt → send CastingTimeOptionsReq to client
        if (pendingPrompt.request.promptType == "modal") {
            ops.traceEvent(MatchEventType.TARGET_PROMPT, game, "modal: ${pendingPrompt.request.message}")
            sendCastingTimeOptionsReq(bridge, pendingPrompt)
            return PromptResult.SENT_TO_CLIENT
        }

        if (pendingPrompt.request.candidateRefs.isNotEmpty()) {
            // Targeting prompt → send SelectTargetsReq to client
            ops.traceEvent(MatchEventType.TARGET_PROMPT, game, "targets=${pendingPrompt.request.candidateRefs.size}")
            sendSelectTargetsReq(bridge, pendingPrompt)
            return PromptResult.SENT_TO_CLIENT
        }

        // Non-targeting prompt → auto-resolve with default
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
        bridge.promptBridge.submitResponse(pendingPrompt.promptId, listOf(req.defaultIndex))
        bridge.awaitPriority()
        return PromptResult.AUTO_RESOLVED
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
        val pendingPrompt = bridge.promptBridge.getPendingPrompt() ?: run {
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

        bridge.promptBridge.submitResponse(pendingPrompt.promptId, selectedIndices)
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
        val pendingPrompt = bridge.promptBridge.getPendingPrompt()
        if (pendingPrompt == null) {
            log.warn("TargetingHandler: CancelActionReq but no pending prompt")
            return
        }

        log.info("TargetingHandler: CancelActionReq — submitting empty targets to unwind spell")

        // Submit empty list → engine sees no targets → spell fails → unwind
        bridge.promptBridge.submitResponse(pendingPrompt.promptId, emptyList())
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
        pendingModal = PendingModal(pendingPrompt.promptId, modalInfo.childGrpIds)

        val result = BundleBuilder.castingTimeOptionsBundle(game, bridge, ops.matchId, ops.seatId, ops.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq seat=${ops.seatId} card=$cardName")
        ops.sendBundledGRE(result.messages)
    }

    /**
     * Handle CastingTimeOptionsResp: map client grpIds back to prompt indices
     * and submit to the bridge to unblock the engine.
     */
    fun onCastingTimeOptions(
        greMsg: ClientToGREMessage,
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val modal = pendingModal
        if (modal == null) {
            log.warn("TargetingHandler: CastingTimeOptionsResp but no pending modal")
            return
        }

        val resp = greMsg.castingTimeOptionsResp
        val chosenGrpIds = resp.castingTimeOptionResp.chooseModalResp.grpIdsList

        // Map grpIds back to indices into the childGrpIds list
        val selectedIndices = chosenGrpIds.mapNotNull { grpId ->
            modal.childGrpIds.indexOf(grpId).takeIf { it >= 0 }
        }

        log.info("TargetingHandler: CastingTimeOptionsResp grpIds={} → indices={}", chosenGrpIds, selectedIndices)

        bridge.promptBridge.submitResponse(modal.promptId, selectedIndices)
        pendingModal = null
        bridge.awaitPriority()
        autoPass(bridge)
    }

    /**
     * Send echo-back after SelectTargetsResp: actions-only GSM diff + re-prompt SelectTargetsReq.
     * Mirrors the real server behavior (wire spec: gsId advances, re-prompt shows selection as Unselect).
     */
    private fun sendTargetEchoBack(
        bridge: GameBridge,
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) {
        val game = bridge.getGame() ?: return

        // Actions-only GSM diff (no zone/object changes)
        val gsId = ops.counter.nextGsId()
        val echoDiff = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .addSystemSeatIds(ops.seatId)
            .setMsgId(ops.counter.nextMsgId())
            .setGameStateId(gsId)
            .setGameStateMessage(
                GameStateMessage.newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(gsId)
                    .setPrevGameStateId(gsId - 1),
            )
            .build()

        // Re-prompt SelectTargetsReq (client sees selection reflected)
        val rePrompt = BundleBuilder.selectTargetsBundle(game, bridge, ops.matchId, ops.seatId, ops.counter, pendingPrompt)

        Tap.outboundTemplate("SelectTargetsReq echo seat=${ops.seatId}")
        ops.sendBundledGRE(listOf(echoDiff) + rePrompt.messages)
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

    /**
     * Detect surveil/scry prompts by message prefix pattern.
     * WebPlayerController uses "Surveil:" / "Scry:" prefixes in arrangeTopNCards.
     */
    private fun detectSurveilScry(req: leyline.bridge.PromptRequest): GroupingContext? {
        val msg = req.message
        return when {
            msg.startsWith("Surveil:") -> GroupingContext.Surveil
            msg.startsWith("Scry:") -> GroupingContext.Scry_a0f6
            else -> null
        }
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
            bridge.promptBridge.submitResponse(pendingPrompt.promptId, listOf(req.defaultIndex))
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
        bridge.promptBridge.submitResponse(prompt.promptId, listOf(prompt.request.defaultIndex))
        bridge.awaitPriority()
    }
}
