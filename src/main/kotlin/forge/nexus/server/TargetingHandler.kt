package forge.nexus.server

import forge.game.Game
import forge.nexus.debug.GameStateCollector
import forge.nexus.debug.NexusTap
import forge.nexus.game.BundleBuilder
import forge.nexus.game.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles targeting-related client messages and prompt detection.
 *
 * Extracted from [MatchSession] for independent testability.
 * Uses [SessionOps] for counter management, message sending, and tracing.
 */
class TargetingHandler(private val ops: SessionOps) {
    private val log = LoggerFactory.getLogger(TargetingHandler::class.java)

    /**
     * Handle SelectTargetsResp: map client instanceIds back to prompt option indices and submit.
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
            val forgeCardId = bridge.getForgeCardId(target.targetInstanceId)
            if (forgeCardId == null) return@mapNotNull null
            pendingPrompt.request.candidateRefs.indexOfFirst { it.entityId == forgeCardId }
        }.filter { it >= 0 }

        log.info("TargetingHandler: SelectTargetsResp indices={}", selectedIndices)

        ops.sendBundledGRE(
            listOf(
                ops.makeGRE(GREMessageType.SubmitTargetsResp_695e, ops.gameStateId, ops.msgIdCounter++) {
                    it.submitTargetsResp = SubmitTargetsResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        // Seed BEFORE submit — submitResponse unblocks the game thread immediately
        bridge.playback?.seedCounters(ops.msgIdCounter, ops.gameStateId)
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

        // Seed BEFORE submit — submitResponse unblocks the game thread immediately
        bridge.playback?.seedCounters(ops.msgIdCounter, ops.gameStateId)
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
            val req = BundleBuilder.buildSelectTargetsReq(pendingPrompt, bridge)
            sendSelectTargetsReq(bridge, req)
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
            val req = BundleBuilder.buildSelectTargetsReq(pendingPrompt, bridge)
            sendSelectTargetsReq(bridge, req)
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

    // --- Sending helper ---

    private fun sendSelectTargetsReq(bridge: GameBridge, req: SelectTargetsReq) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.selectTargetsBundle(game, bridge, ops.matchId, ops.seatId, ops.msgIdCounter, ops.gameStateId, req)
        ops.msgIdCounter = result.nextMsgId
        ops.gameStateId = result.nextGsId
        NexusTap.outboundTemplate("SelectTargetsReq seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
        bridge.playback?.seedCounters(ops.msgIdCounter, ops.gameStateId)
    }
}
