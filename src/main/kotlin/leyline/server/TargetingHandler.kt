package leyline.server

import forge.game.Game
import leyline.debug.GameStateCollector
import leyline.debug.Tap
import leyline.game.BundleBuilder
import leyline.game.GameBridge
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

    /**
     * Handle SelectTargetsResp: map client instanceIds back to prompt option indices and submit.
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
            // Player targets: instanceId == seatId (1 or 2). Match against player candidateRefs.
            val playerIdx = resolvePlayerTarget(instanceId, bridge, pendingPrompt)
            if (playerIdx != null) return@mapNotNull playerIdx

            // Card targets: normal instanceId → forgeCardId reverse lookup
            val forgeCardId = bridge.getForgeCardId(instanceId) ?: return@mapNotNull null
            pendingPrompt.request.candidateRefs.indexOfFirst { it.entityId == forgeCardId }
        }.filter { it >= 0 }

        log.info("TargetingHandler: SelectTargetsResp indices={}", selectedIndices)

        ops.sendBundledGRE(
            listOf(
                ops.makeGRE(GREMessageType.SubmitTargetsResp_695e, ops.counter.currentGsId(), ops.counter.nextMsgId()) {
                    it.submitTargetsResp = SubmitTargetsResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        bridge.promptBridge.submitResponse(pendingPrompt.promptId, selectedIndices)
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
            val forgeCardId = bridge.getForgeCardId(instanceId)
            if (forgeCardId == null) return@mapNotNull null
            pendingPrompt.request.candidateRefs.indexOfFirst { it.entityId == forgeCardId }
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
        if (pendingPrompt != null && pendingPrompt.request.candidateRefs.isNotEmpty()) {
            val game = bridge.getGame() ?: return false
            ops.traceEvent(
                GameStateCollector.EventType.TARGET_PROMPT,
                game,
                "cast-target targets=${pendingPrompt.request.candidateRefs.size}",
            )
            sendSelectTargetsReq(bridge, pendingPrompt)
            return true
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
     * - Non-targeting prompts (confirm, choose_cards, order) → auto-resolve with
     *   defaultIndex. Covers discard-to-hand-size at Cleanup and similar engine prompts.
     *   These can be wired to client UI later.
     */
    fun checkPendingPrompt(bridge: GameBridge, game: Game): PromptResult {
        val pendingPrompt = bridge.promptBridge.getPendingPrompt() ?: return PromptResult.NONE
        if (pendingPrompt.request.candidateRefs.isNotEmpty()) {
            // Targeting prompt → send SelectTargetsReq to client
            ops.traceEvent(GameStateCollector.EventType.TARGET_PROMPT, game, "targets=${pendingPrompt.request.candidateRefs.size}")
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
            GameStateCollector.EventType.AUTO_PASS,
            game,
            "auto-resolve prompt [${req.promptType}] default=${req.defaultIndex}",
        )
        bridge.promptBridge.submitResponse(pendingPrompt.promptId, listOf(req.defaultIndex))
        bridge.awaitPriority()
        return PromptResult.AUTO_RESOLVED
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
        pendingPrompt: leyline.bridge.InteractivePromptBridge.PendingPrompt,
    ): Int? {
        // Arena uses seatId as instanceId for player targets (1 or 2)
        val player = bridge.getPlayer(instanceId) ?: return null
        val idx = pendingPrompt.request.candidateRefs.indexOfFirst {
            it.kind == "player" && it.entityId == player.id
        }
        return if (idx >= 0) idx else null
    }

    private fun sendSelectTargetsReq(
        bridge: GameBridge,
        pendingPrompt: leyline.bridge.InteractivePromptBridge.PendingPrompt,
    ) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.selectTargetsBundle(game, bridge, ops.matchId, ops.seatId, ops.counter, pendingPrompt)
        Tap.outboundTemplate("SelectTargetsReq seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
    }
}
