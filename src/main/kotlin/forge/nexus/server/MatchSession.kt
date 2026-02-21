package forge.nexus.server

import forge.game.Game
import forge.game.phase.PhaseType
import forge.nexus.debug.GameStateCollector
import forge.nexus.debug.NexusDebugCollector
import forge.nexus.debug.NexusTap
import forge.nexus.game.BundleBuilder
import forge.nexus.game.CardDb
import forge.nexus.game.GameBridge
import forge.nexus.game.StateMapper
import forge.nexus.protocol.HandshakeMessages
import forge.nexus.protocol.ProtoDump
import forge.web.game.PlayerAction
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.Visibility

/**
 * Game orchestration session — all post-mulligan game logic.
 *
 * Transport-agnostic: sends messages through [MessageSink].
 * [MatchHandler] creates one per connection and delegates GRE messages here.
 */
class MatchSession(
    val seatId: Int,
    val matchId: String,
    val sink: MessageSink,
    val registry: MatchRegistry,
    val paceDelayMs: Long = 200L,
) {
    private val log = LoggerFactory.getLogger(MatchSession::class.java)

    /** Serializes all game-logic entry points (Netty I/O threads are concurrent). */
    private val sessionLock = Any()

    var msgIdCounter = 1
        private set
    var gameStateId = 0
        private set
    var gameBridge: GameBridge? = null
        private set

    /** Saved client settings for echoing in SetSettingsResp. */
    var clientSettings: SettingsMessage? = null

    /** Legal attacker instanceIds from the last DeclareAttackersReq we sent. */
    private var pendingLegalAttackers: List<Int> = emptyList()

    /** Wire the game bridge (called by [MatchHandler] after bridge creation). */
    fun connectBridge(bridge: GameBridge) {
        gameBridge = bridge
    }

    /** Advance gameStateId and return the new value. Used by [MatchHandler] handshake senders. */
    fun nextGameStateId(): Int {
        gameStateId++
        return gameStateId
    }

    /** Update counters from a handshake result. Used by [MatchHandler] handshake senders. */
    fun applyHandshakeCounters(nextMsgId: Int) {
        msgIdCounter = nextMsgId
    }

    companion object {
        private const val AUTO_PASS_MAX_ITERATIONS = 50
    }

    // --- Public entry points (called by MatchHandler) ---

    /**
     * After keep: wait for engine to reach priority, send real game state bundle.
     * Then auto-pass through phases where only Pass is available.
     */
    fun onMulliganKeep() = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        log.info("MatchSession: waiting for engine to reach priority after keep")
        bridge.awaitPriority()

        val game = bridge.getGame() ?: return

        // Discard any AI action diffs queued during awaitPriority — stale diffs
        // would conflict with the fresh phaseTransitionDiff we're about to send.
        bridge.playback?.drainQueue()
        // Don't clearPreviousState — it's already null (handshake doesn't snapshot).
        // phaseTransitionDiff builds thin metadata Diffs, no snapshot needed.
        // TODO: when handshake sends a proper Full with real game objects,
        // the first Diff here needs diffDeletedInstanceIds to retire stale IDs
        // from the handshake Full (real server does this post-mulligan).

        traceEvent(GameStateCollector.EventType.GAME_START, game, "post-mulligan, entering Main1")

        val result = BundleBuilder.phaseTransitionDiff(game, bridge, matchId, seatId, msgIdCounter, gameStateId)
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId

        sendBundle(result)

        // Seed playback counters so AI action diffs use correct sequence
        bridge.playback?.seedCounters(msgIdCounter, gameStateId)

        // Seed state snapshot for subsequent diff computation
        bridge.snapshotState(StateMapper.buildFromGame(game, gameStateId, matchId, bridge))

        // Auto-pass through phases where human has no real actions
        autoPassAndAdvance(bridge)
    }

    /**
     * Handle a client action (land play, spell cast, pass) and advance the engine.
     */
    fun onPerformAction(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        log.info("MatchSession: onPerformAction enter gsId={} (current={})", greMsg.gameStateId, gameStateId)

        // Reject stale actions — client may resend with outdated gameStateId
        val clientGsId = greMsg.gameStateId
        if (clientGsId != 0 && clientGsId < gameStateId) {
            log.warn("MatchSession: stale PerformActionResp gsId={} (current={}), ignoring", clientGsId, gameStateId)
            return
        }

        val pending = bridge.actionBridge.getPending() ?: run {
            log.warn("MatchSession: PerformActionResp but no pending action — recovering")
            autoPassAndAdvance(bridge)
            return
        }

        val action = greMsg.performActionResp.actionsList.firstOrNull()
        if (action == null) {
            log.warn("MatchSession: PerformActionResp with no actions")
            return
        }

        NexusTap.inboundAction(action)

        val isCast = action.actionType == ActionType.Cast
        val game = bridge.getGame()
        val stackWasNonEmpty = game != null && !game.stack.isEmpty
        if (game != null) {
            val actionName = action.actionType.name.removeSuffix("_add3")
            val cardName = if (action.instanceId != 0) {
                CardDb.getCardName(action.grpId)?.let { " ($it)" } ?: ""
            } else {
                ""
            }
            traceEvent(GameStateCollector.EventType.CLIENT_ACTION, game, "$actionName iid=${action.instanceId}$cardName")
        }

        // Seed playback BEFORE submit — submitAction unblocks the game thread
        // immediately via CompletableFuture.complete(), and the game thread may
        // fire events captured by NexusGamePlayback before we reach awaitPriority.
        bridge.playback?.seedCounters(msgIdCounter, gameStateId)

        when (action.actionType) {
            ActionType.Pass -> {
                bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            }
            ActionType.Play_add3 -> {
                val forgeCardId = bridge.getForgeCardId(action.instanceId)
                val submitted = if (forgeCardId != null) {
                    bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(forgeCardId))
                } else {
                    bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
                }
                NexusTap.actionResult(action.actionType, action.instanceId, forgeCardId, submitted)
            }
            ActionType.Cast -> {
                val forgeCardId = bridge.getForgeCardId(action.instanceId)
                val submitted = if (forgeCardId != null) {
                    bridge.actionBridge.submitAction(pending.actionId, PlayerAction.CastSpell(forgeCardId))
                } else {
                    bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
                }
                NexusTap.actionResult(action.actionType, action.instanceId, forgeCardId, submitted)
            }
            else -> {
                log.info("MatchSession: unhandled action type {}, passing", action.actionType)
                bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            }
        }

        // Wait for engine to reach next priority stop
        bridge.awaitPriority()

        // After a cast, check for targeting prompt or intermediate stack state
        if (isCast && handlePostCastPrompt(bridge)) return

        // After stack resolution: send intermediate state so the client sees the
        // creature move from stack to battlefield with resolution annotations.
        if (stackWasNonEmpty) {
            val g = bridge.getGame()
            if (g != null && g.stack.isEmpty) {
                log.info("MatchSession: stack resolved, sending intermediate resolution state")
                sendRealGameState(bridge)
                return
            }
        }

        autoPassAndAdvance(bridge)
    }

    /**
     * Handle DeclareAttackersResp: map instanceIds to forge card IDs and submit.
     */
    fun onDeclareAttackers(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        val pending = bridge.actionBridge.getPending() ?: run {
            log.warn("MatchSession: DeclareAttackersResp but no pending action — recovering")
            sendRealGameState(bridge)
            return
        }

        val resp = greMsg.declareAttackersResp
        val selectedInstanceIds = resp.selectedAttackersList.map { it.attackerInstanceId }
            .ifEmpty { pendingLegalAttackers }

        log.info(
            "MatchSession: DeclareAttackers instanceIds={} (fromResp={} pending={})",
            selectedInstanceIds,
            resp.selectedAttackersList.size,
            pendingLegalAttackers.size,
        )
        val attackerCardIds = selectedInstanceIds.mapNotNull { instanceId ->
            val forgeId = bridge.getForgeCardId(instanceId)
            if (forgeId == null) {
                log.warn("MatchSession: instanceId {} not in map (map size={})", instanceId, bridge.getInstanceIdMap().size)
            }
            forgeId
        }
        pendingLegalAttackers = emptyList()

        log.info("MatchSession: DeclareAttackers forgeCardIds={}", attackerCardIds)

        sendBundledGRE(
            listOf(
                makeGRE(GREMessageType.SubmitAttackersResp_695e, gameStateId, msgIdCounter++) {
                    it.submitAttackersResp = SubmitAttackersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        // Resolve the defending player: the opponent of the active (attacking) player.
        val game = bridge.getGame()
        val humanPlayer = bridge.getPlayer(seatId)
        val defenderPlayerId = game?.players
            ?.firstOrNull { it != humanPlayer }?.id

        // Seed BEFORE submit — submitAction unblocks the game thread immediately
        bridge.playback?.seedCounters(msgIdCounter, gameStateId)
        bridge.actionBridge.submitAction(
            pending.actionId,
            PlayerAction.DeclareAttackers(attackerCardIds, defenderPlayerId = defenderPlayerId),
        )
        bridge.awaitPriority()
        autoPassAndAdvance(bridge)
    }

    /**
     * Handle DeclareBlockersResp: map blocker->attacker assignments and submit.
     */
    fun onDeclareBlockers(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return

        val pending = bridge.actionBridge.getPending() ?: run {
            log.warn("MatchSession: DeclareBlockersResp but no pending action — recovering")
            sendRealGameState(bridge)
            return
        }

        val resp = greMsg.declareBlockersResp
        val blockAssignments = mutableMapOf<Int, Int>()
        for (blocker in resp.selectedBlockersList) {
            val blockerCardId = bridge.getForgeCardId(blocker.blockerInstanceId) ?: continue
            val attackerInstanceId = blocker.selectedAttackerInstanceIdsList.firstOrNull() ?: continue
            val attackerCardId = bridge.getForgeCardId(attackerInstanceId) ?: continue
            blockAssignments[blockerCardId] = attackerCardId
        }

        log.info("MatchSession: DeclareBlockersResp blocks={}", blockAssignments)

        sendBundledGRE(
            listOf(
                makeGRE(GREMessageType.SubmitBlockersResp_695e, gameStateId, msgIdCounter++) {
                    it.submitBlockersResp = SubmitBlockersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        // Seed BEFORE submit — submitAction unblocks the game thread immediately
        bridge.playback?.seedCounters(msgIdCounter, gameStateId)
        bridge.actionBridge.submitAction(
            pending.actionId,
            PlayerAction.DeclareBlockers(blockAssignments),
        )
        bridge.awaitPriority()
        autoPassAndAdvance(bridge)
    }

    /**
     * Handle SelectTargetsResp: map client instanceIds back to prompt option indices and submit.
     */
    fun onSelectTargets(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return

        val resp = greMsg.selectTargetsResp
        val pendingPrompt = bridge.promptBridge.getPendingPrompt() ?: run {
            log.warn("MatchSession: SelectTargetsResp but no pending prompt")
            return
        }

        val selectedTarget = resp.target
        val selectedIndices = selectedTarget.targetsList.mapNotNull { target ->
            val forgeCardId = bridge.getForgeCardId(target.targetInstanceId)
            if (forgeCardId == null) return@mapNotNull null
            pendingPrompt.request.candidateRefs.indexOfFirst { it.entityId == forgeCardId }
        }.filter { it >= 0 }

        log.info("MatchSession: SelectTargetsResp indices={}", selectedIndices)

        sendBundledGRE(
            listOf(
                makeGRE(GREMessageType.SubmitTargetsResp_695e, gameStateId, msgIdCounter++) {
                    it.submitTargetsResp = SubmitTargetsResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        // Seed BEFORE submit — submitResponse unblocks the game thread immediately
        bridge.playback?.seedCounters(msgIdCounter, gameStateId)
        bridge.promptBridge.submitResponse(pendingPrompt.promptId, selectedIndices)
        bridge.awaitPriority()
        autoPassAndAdvance(bridge)
    }

    /** Handle concede: remove bridge and send game-over. */
    fun onConcede() = synchronized(sessionLock) {
        registry.removeBridge(matchId)?.shutdown()
        sendGameOver()
    }

    /** Handle SetSettingsReq: save settings and echo response. */
    fun onSettings(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val reqSettings = greMsg.setSettingsReq
        clientSettings = reqSettings.settings
        log.info("MatchSession: SetSettingsReq (stops={})", reqSettings.settings.stopsCount)
        val (msg, nextMsgId) = HandshakeMessages.settingsResp(seatId, msgIdCounter, gameStateId, clientSettings)
        msgIdCounter = nextMsgId
        ProtoDump.dump(msg, "SettingsResp")
        sink.sendRaw(msg)
    }

    /**
     * After a cast, check for a pending targeting prompt or intermediate stack state.
     * Returns true if handled (caller should return), false to continue normal flow.
     */
    private fun handlePostCastPrompt(bridge: GameBridge): Boolean {
        val pendingPrompt = bridge.promptBridge.getPendingPrompt()
        if (pendingPrompt != null && pendingPrompt.request.candidateRefs.isNotEmpty()) {
            traceEvent(
                GameStateCollector.EventType.TARGET_PROMPT,
                bridge.getGame()!!,
                "cast-target targets=${pendingPrompt.request.candidateRefs.size}",
            )
            val req = BundleBuilder.buildSelectTargetsReq(pendingPrompt, bridge)
            sendSelectTargetsReq(bridge, req)
            return true
        }
        val g = bridge.getGame()
        if (g != null && !g.stack.isEmpty) {
            sendRealGameState(bridge)
            return true
        }
        return false
    }

    // --- Auto-pass engine ---

    /** Signals from sub-steps back to the auto-pass loop. */
    private enum class LoopSignal { STOP, SEND_STATE, CONTINUE }

    /**
     * Auto-pass through phases where the player has no meaningful actions.
     * Detects combat phases and sends appropriate combat prompts.
     */
    private fun autoPassAndAdvance(bridge: GameBridge) {
        repeat(AUTO_PASS_MAX_ITERATIONS) {
            val game = bridge.getGame() ?: return
            if (game.isGameOver) {
                traceEvent(GameStateCollector.EventType.GAME_OVER, game, "game over detected")
                sendGameOver()
                return
            }

            // Drain pending AI-action diffs
            if (drainPlayback(bridge, game)) return@repeat

            val human = bridge.getPlayer(seatId)
            val phase = game.phaseHandler.phase
            val isHumanTurn = human != null && game.phaseHandler.playerTurn == human
            val isAiTurn = human != null && !isHumanTurn

            // Combat phase handling
            val combatSignal = checkCombatPhase(bridge, game, phase, isHumanTurn, isAiTurn)
            if (combatSignal == LoopSignal.STOP) return
            if (combatSignal == LoopSignal.SEND_STATE) { sendRealGameState(bridge); return }

            // Interactive prompt (targeting, sacrifice, etc.)
            if (checkPendingPrompt(bridge, game)) return

            // Action check — prompt human if meaningful actions exist
            if (checkHumanActions(bridge, game, isAiTurn)) { sendRealGameState(bridge); return }

            // Auto-pass or wait
            advanceOrWait(bridge, game, phase, isAiTurn) ?: return
        }

        log.warn("autoPassAndAdvance: hit max iterations ({})", AUTO_PASS_MAX_ITERATIONS)
        sendRealGameState(bridge)
    }

    /**
     * Drain pending AI-action playback diffs. Returns true if diffs were sent
     * (caller should re-evaluate in next iteration), false if nothing pending.
     */
    private fun drainPlayback(bridge: GameBridge, game: Game): Boolean {
        val playback = bridge.playback ?: return false
        if (!playback.hasPendingMessages()) return false
        val batches = playback.drainQueue()
        for ((idx, batch) in batches.withIndex()) {
            if (idx > 0) paceDelay(1)
            sendBundledGRE(batch)
        }
        val (nextMsg, nextGs) = playback.getCounters()
        msgIdCounter = nextMsg
        gameStateId = nextGs
        bridge.snapshotState(StateMapper.buildFromGame(game, gameStateId, matchId, bridge))
        return true
    }

    /**
     * Check combat phases and send appropriate prompts or state.
     * Returns [LoopSignal.STOP] if a combat prompt was sent (return from loop),
     * [LoopSignal.SEND_STATE] if state should be sent, or [LoopSignal.CONTINUE] to proceed.
     */
    private fun checkCombatPhase(
        bridge: GameBridge,
        game: Game,
        phase: PhaseType?,
        isHumanTurn: Boolean,
        isAiTurn: Boolean,
    ): LoopSignal {
        val combat = game.phaseHandler.combat

        when (phase) {
            PhaseType.COMBAT_DECLARE_ATTACKERS -> {
                if (isHumanTurn) {
                    val req = BundleBuilder.buildDeclareAttackersReq(game, seatId, bridge)
                    if (req.attackersCount > 0) {
                        traceEvent(GameStateCollector.EventType.COMBAT_PROMPT, game, "DeclareAttackers attackers=${req.attackersCount}")
                        sendDeclareAttackersReq(bridge, req)
                        return LoopSignal.STOP
                    }
                } else if (isAiTurn && combat != null && combat.attackers.isNotEmpty()) {
                    traceEvent(GameStateCollector.EventType.SEND_STATE, game, "AI attacking, ${combat.attackers.size} attackers")
                    paceDelay(2)
                    return LoopSignal.SEND_STATE
                }
            }
            PhaseType.COMBAT_DECLARE_BLOCKERS -> {
                if (isAiTurn && combat != null && combat.attackers.isNotEmpty()) {
                    traceEvent(GameStateCollector.EventType.COMBAT_PROMPT, game, "DeclareBlockers attackers=${combat.attackers.size}")
                    sendDeclareBlockersReq(bridge)
                    return LoopSignal.STOP
                } else if (isHumanTurn && combat != null && combat.attackers.isNotEmpty()) {
                    traceEvent(GameStateCollector.EventType.SEND_STATE, game, "AI blocking result")
                    paceDelay(2)
                    return LoopSignal.SEND_STATE
                }
            }
            PhaseType.COMBAT_DAMAGE -> {
                traceEvent(GameStateCollector.EventType.SEND_STATE, game, "combat damage")
                paceDelay(2)
                return LoopSignal.SEND_STATE
            }
            PhaseType.COMBAT_END -> {
                if (combat != null && combat.attackers.isNotEmpty()) {
                    traceEvent(GameStateCollector.EventType.SEND_STATE, game, "combat end")
                    return LoopSignal.SEND_STATE
                }
            }
            else -> {}
        }
        return LoopSignal.CONTINUE
    }

    /** Check for pending interactive prompt. Returns true if prompt was sent (caller should return). */
    private fun checkPendingPrompt(bridge: GameBridge, game: Game): Boolean {
        val pendingPrompt = bridge.promptBridge.getPendingPrompt() ?: return false
        if (pendingPrompt.request.candidateRefs.isEmpty()) return false
        traceEvent(GameStateCollector.EventType.TARGET_PROMPT, game, "targets=${pendingPrompt.request.candidateRefs.size}")
        val req = BundleBuilder.buildSelectTargetsReq(pendingPrompt, bridge)
        sendSelectTargetsReq(bridge, req)
        return true
    }

    /** Check if human has meaningful actions. Returns true if state should be sent. */
    private fun checkHumanActions(bridge: GameBridge, game: Game, isAiTurn: Boolean): Boolean {
        if (isAiTurn) return false
        val actions = BundleBuilder.buildActions(game, seatId, bridge)
        if (BundleBuilder.shouldAutoPass(actions)) return false
        val actionSummary = actions.actionsList
            .groupBy { it.actionType.name.removeSuffix("_add3") }
            .map { (t, v) -> "$t=${v.size}" }
            .joinToString(" ")
        traceEvent(GameStateCollector.EventType.SEND_STATE, game, "actions: $actionSummary")
        return true
    }

    /**
     * Submit auto-pass or wait for AI/engine. Returns Unit on success (loop continues),
     * or null to signal the caller should return (game over / timeout).
     */
    private fun advanceOrWait(bridge: GameBridge, game: Game, phase: PhaseType?, isAiTurn: Boolean): Unit? {
        val pending = bridge.actionBridge.getPending()
        log.debug("autoPass: phase={} turn={} aiTurn={} pending={}", phase, game.phaseHandler.turn, isAiTurn, pending != null)

        if (pending != null) {
            traceEvent(GameStateCollector.EventType.AUTO_PASS, game, "human priority, pass-only")
            // During AI turn, skip sending EdictalMessage — real server never
            // sends edictal passes during AI turn. Sending them interrupts the
            // client's animation pipeline (enters post-pass "waiting" state).
            if (!isAiTurn) {
                val edictal = BundleBuilder.edictalPass(seatId, msgIdCounter, gameStateId)
                msgIdCounter = edictal.nextMsgId
                sendBundledGRE(edictal.messages)
            }
            // Seed BEFORE submit — submitAction unblocks the game thread immediately
            // and it may fire events captured by NexusGamePlayback with stale counters.
            bridge.playback?.seedCounters(msgIdCounter, gameStateId)
            bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            bridge.awaitPriority()
        } else if (isAiTurn) {
            traceEvent(GameStateCollector.EventType.AI_TURN_WAIT, game, "waiting for AI")
            bridge.playback?.seedCounters(msgIdCounter, gameStateId)
            val reachedPriority = bridge.awaitPriorityWithTimeout(GameBridge.AI_TURN_WAIT_MS)
            if (!reachedPriority) {
                val g = bridge.getGame()
                if (g != null && g.isGameOver) {
                    traceEvent(GameStateCollector.EventType.GAME_OVER, game, "game over during AI wait")
                    sendGameOver()
                    return null
                }
                traceEvent(GameStateCollector.EventType.AI_TURN_TIMEOUT, game, "AI turn timed out")
                log.warn("autoPass: AI turn timed out, sending current state")
                sendRealGameState(bridge)
                return null
            }
        } else {
            traceEvent(GameStateCollector.EventType.PRIORITY_GRANT, game, "waiting for engine")
            log.warn("autoPass: no pending action, waiting for priority")
            bridge.playback?.seedCounters(msgIdCounter, gameStateId)
            bridge.awaitPriority()
        }
        return Unit
    }

    // --- Sending helpers ---

    /**
     * Build and send current game state + available actions from the live Forge engine.
     */
    private fun sendRealGameState(bridge: GameBridge) {
        val game = bridge.getGame() ?: run {
            log.warn("MatchSession: sendRealGameState but game is null")
            return
        }

        val result = BundleBuilder.postAction(game, bridge, matchId, seatId, msgIdCounter, gameStateId)
        sendBundle(result)
        bridge.playback?.seedCounters(msgIdCounter, gameStateId)
    }

    private fun sendDeclareAttackersReq(
        bridge: GameBridge,
        req: DeclareAttackersReq? = null,
    ) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.declareAttackersBundle(game, bridge, matchId, seatId, msgIdCounter, gameStateId, req)
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId

        val builtReq = result.messages.firstOrNull { it.hasDeclareAttackersReq() }?.declareAttackersReq
        pendingLegalAttackers = builtReq?.attackersList?.map { it.attackerInstanceId } ?: emptyList()
        log.debug("DeclareAttackersReq: pendingLegalAttackers={}", pendingLegalAttackers)

        NexusTap.outboundTemplate("DeclareAttackersReq seat=$seatId")
        sendBundledGRE(result.messages)
        bridge.playback?.seedCounters(msgIdCounter, gameStateId)
    }

    private fun sendDeclareBlockersReq(bridge: GameBridge) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.declareBlockersBundle(game, bridge, matchId, seatId, msgIdCounter, gameStateId)
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId

        NexusTap.outboundTemplate("DeclareBlockersReq seat=$seatId")
        sendBundledGRE(result.messages)
        bridge.playback?.seedCounters(msgIdCounter, gameStateId)
    }

    private fun sendSelectTargetsReq(bridge: GameBridge, req: SelectTargetsReq) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.selectTargetsBundle(game, bridge, matchId, seatId, msgIdCounter, gameStateId, req)
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId
        NexusTap.outboundTemplate("SelectTargetsReq seat=$seatId")
        sendBundledGRE(result.messages)
        bridge.playback?.seedCounters(msgIdCounter, gameStateId)
    }

    /** Apply a [BundleBuilder.BundleResult]: update counters, tap-log, and send. */
    private fun sendBundle(result: BundleBuilder.BundleResult) {
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId
        for (gre in result.messages) {
            if (gre.hasGameStateMessage()) NexusTap.outboundState(gre.gameStateMessage)
            if (gre.hasActionsAvailableReq()) NexusTap.outboundActions(gre.actionsAvailableReq)
        }
        sendBundledGRE(result.messages)
    }

    /**
     * Send game-over sequence: 3x GS Diff + IntermissionReq.
     */
    private fun sendGameOver() {
        val bridge = gameBridge
        val humanPlayer = bridge?.getPlayer(1)
        val humanWon = humanPlayer?.getOutcome()?.hasWon() ?: false
        val winningTeam = if (humanWon) 1 else 2

        val result = BundleBuilder.gameOverBundle(winningTeam, seatId, msgIdCounter, gameStateId)
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId
        sendBundledGRE(result.messages)
        log.info("MatchSession: sent game-over sequence (winner=team{})", winningTeam)
    }

    // --- Low-level helpers ---

    /** Build a single GRE message with an explicit msgId (no side-effect on counters). */
    private fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val gre = GREToClientMessage.newBuilder()
            .setType(type).setMsgId(msgId).setGameStateId(gsId).addSystemSeatIds(seatId)
        configure(gre)
        return gre.build()
    }

    /** Send multiple GRE messages bundled in one GreToClientEvent + mirror to peer. */
    private fun sendBundledGRE(messages: List<GREToClientMessage>) {
        NexusDebugCollector.recordOutbound(messages, seatId)
        GameStateCollector.collectOutbound(messages, NexusDebugCollector.currentSeq())
        sink.send(messages)
        mirrorToFamiliar(messages)
    }

    /** Send a copy of GRE messages to the Familiar (seat 2) via registry. */
    private fun mirrorToFamiliar(messages: List<GREToClientMessage>) {
        if (seatId != 1) return
        val peer = registry.getPeer(matchId, seatId) ?: return
        val mirrorSeat = 2
        val mirrored = messages.map { gre ->
            val builder = gre.toBuilder().clearSystemSeatIds().addSystemSeatIds(mirrorSeat)
            // Strip Private gameObjects not visible to mirror seat (real server
            // omits Limbo objects from non-owner messages).
            if (builder.hasGameStateMessage()) {
                val gsm = builder.gameStateMessage.toBuilder()
                val filtered = gsm.gameObjectsList.filter { obj ->
                    obj.visibility != Visibility.Private || obj.viewersList.contains(mirrorSeat)
                }
                gsm.clearGameObjects().addAllGameObjects(filtered)
                builder.setGameStateMessage(gsm.build())
            }
            builder.build()
        }
        peer.sink.send(mirrored)
    }

    /** Emit a priority trace event to [GameStateCollector]. */
    private fun traceEvent(type: GameStateCollector.EventType, game: Game, detail: String) {
        val phase = game.phaseHandler.phase?.name
        val turn = game.phaseHandler.turn
        val human = gameBridge?.getPlayer(seatId)
        val priority = when (game.phaseHandler.priorityPlayer) {
            human -> "human"
            else -> "ai"
        }
        val stackDepth = game.stack?.size() ?: 0
        GameStateCollector.recordEvent(gameStateId, type, phase, turn, detail, priority, stackDepth, NexusDebugCollector.currentSeq())
    }

    /** Pacing delay — skipped when paceDelayMs == 0 (tests). */
    private fun paceDelay(multiplier: Int) {
        val delay = paceDelayMs * multiplier
        if (delay > 0) Thread.sleep(delay)
    }
}
