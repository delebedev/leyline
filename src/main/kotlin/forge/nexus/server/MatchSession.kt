package forge.nexus.server

import forge.game.Game
import forge.game.phase.PhaseType
import forge.nexus.debug.ArenaDebugCollector
import forge.nexus.debug.GameStateCollector
import forge.nexus.debug.NexusTap
import forge.nexus.game.BundleBuilder
import forge.nexus.game.CardDb
import forge.nexus.game.GameBridge
import forge.nexus.game.StateMapper
import forge.nexus.protocol.ProtoDump
import forge.nexus.protocol.Templates
import forge.web.game.PlayerAction
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

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

    var msgIdCounter = 1
    var gameStateId = 0
    var gameBridge: GameBridge? = null

    /** Saved client settings for echoing in SetSettingsResp. */
    var clientSettings: SettingsMessage? = null

    /** True while this seat is in an interactive prompt (attackers/blockers/targets). */
    private var inInteractivePrompt = false

    /** Legal attacker instanceIds from the last DeclareAttackersReq we sent. */
    private var pendingLegalAttackers: List<Int> = emptyList()

    companion object {
        private const val AUTO_PASS_MAX_ITERATIONS = 50
    }

    // --- Public entry points (called by MatchHandler) ---

    /**
     * After keep: wait for engine to reach priority, send real game state bundle.
     * Then auto-pass through phases where only Pass is available.
     */
    fun onMulliganKeep() {
        val bridge = gameBridge ?: return
        log.info("MatchSession: waiting for engine to reach priority after keep")
        bridge.awaitPriority()

        val game = bridge.getGame() ?: return

        // Discard any AI action diffs queued during awaitPriority — the game-start
        // Full state already captures the post-AI board. Stale diffs would conflict.
        bridge.playback?.drainQueue()

        traceEvent(GameStateCollector.EventType.GAME_START, game, "post-mulligan, entering Main1")

        val result = BundleBuilder.gameStart(game, bridge, matchId, seatId, msgIdCounter, gameStateId)
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId

        sendBundle(result)

        // Seed playback counters so AI action diffs use correct sequence
        bridge.playback?.seedCounters(msgIdCounter, gameStateId)

        // Seed state snapshot for subsequent diff computation
        bridge.snapshotState(game)

        // Auto-pass through phases where human has no real actions
        autoPassAndAdvance(bridge)
    }

    /**
     * Handle a client action (land play, spell cast, pass) and advance the engine.
     */
    fun onPerformAction(greMsg: ClientToGREMessage) {
        val bridge = gameBridge ?: return
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

        // After a cast, the spell is on the stack. Send state with Pass
        // so the client shows the spell on stack.
        if (isCast) {
            val g = bridge.getGame()
            if (g != null && !g.stack.isEmpty) {
                sendRealGameState(bridge)
                return
            }
        }

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
    fun onDeclareAttackers(greMsg: ClientToGREMessage) {
        val bridge = gameBridge ?: return
        inInteractivePrompt = false

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

        bridge.actionBridge.submitAction(
            pending.actionId,
            PlayerAction.DeclareAttackers(attackerCardIds, defenderPlayerId = null),
        )

        sendBundledGRE(
            listOf(
                makeGRE(GREMessageType.SubmitAttackersResp_695e, gameStateId) {
                    it.submitAttackersResp = SubmitAttackersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        bridge.awaitPriority()
        autoPassAndAdvance(bridge)
    }

    /**
     * Handle DeclareBlockersResp: map blocker->attacker assignments and submit.
     */
    fun onDeclareBlockers(greMsg: ClientToGREMessage) {
        val bridge = gameBridge ?: return
        inInteractivePrompt = false

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

        bridge.actionBridge.submitAction(
            pending.actionId,
            PlayerAction.DeclareBlockers(blockAssignments),
        )

        sendBundledGRE(
            listOf(
                makeGRE(GREMessageType.SubmitBlockersResp_695e, gameStateId) {
                    it.submitBlockersResp = SubmitBlockersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        bridge.awaitPriority()
        autoPassAndAdvance(bridge)
    }

    /**
     * Handle SelectTargetsResp: map Arena instanceIds back to prompt option indices and submit.
     */
    fun onSelectTargets(greMsg: ClientToGREMessage) {
        val bridge = gameBridge ?: return
        inInteractivePrompt = false

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
        bridge.promptBridge.submitResponse(pendingPrompt.promptId, selectedIndices)

        sendBundledGRE(
            listOf(
                makeGRE(GREMessageType.SubmitTargetsResp_695e, gameStateId) {
                    it.submitTargetsResp = SubmitTargetsResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        bridge.awaitPriority()
        autoPassAndAdvance(bridge)
    }

    /** Handle concede: remove bridge and send game-over. */
    fun onConcede() {
        registry.removeBridge(matchId)?.shutdown()
        sendGameOver()
    }

    /** Handle SetSettingsReq: save settings and echo response. */
    fun onSettings(greMsg: ClientToGREMessage) {
        val reqSettings = greMsg.setSettingsReq
        clientSettings = reqSettings.settings
        log.info("MatchSession: SetSettingsReq (stops={})", reqSettings.settings.stopsCount)
        val (msg, nextMsgId) = Templates.settingsResp(seatId, msgIdCounter, gameStateId, clientSettings)
        msgIdCounter = nextMsgId
        ProtoDump.dump(msg, "SettingsResp")
        sink.sendRaw(msg)
    }

    // --- Auto-pass engine ---

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

            // Send any pending AI-action diffs to the client
            val playback = bridge.playback
            if (playback != null && playback.hasPendingMessages()) {
                val batches = playback.drainQueue()
                for (batch in batches) {
                    sendBundledGRE(batch)
                }
                val (nextMsg, nextGs) = playback.getCounters()
                msgIdCounter = nextMsg
                gameStateId = nextGs
                bridge.snapshotState(game)
            }

            val human = bridge.getPlayer(seatId)
            val phase = game.phaseHandler.phase
            val isHumanTurn = human != null && game.phaseHandler.playerTurn == human
            val isAiTurn = human != null && !isHumanTurn

            // Combat: DeclareAttackers on human's attacking turn
            if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS && isHumanTurn) {
                val req = StateMapper.buildDeclareAttackersReq(game, seatId, bridge)
                if (req.attackersCount > 0) {
                    traceEvent(GameStateCollector.EventType.COMBAT_PROMPT, game, "DeclareAttackers attackers=${req.attackersCount}")
                    sendDeclareAttackersReq(bridge, req)
                    return
                }
            }

            // AI attacking: send state so client sees creatures with attackState
            if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS && isAiTurn) {
                val combat = game.phaseHandler.combat
                if (combat != null && combat.attackers.isNotEmpty()) {
                    traceEvent(GameStateCollector.EventType.SEND_STATE, game, "AI attacking, ${combat.attackers.size} attackers")
                    paceDelay(2)
                    sendRealGameState(bridge)
                    return
                }
            }

            // Combat: DeclareBlockers on AI's attacking turn (human is defending)
            if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS && isAiTurn) {
                val combat = game.phaseHandler.combat
                if (combat != null && combat.attackers.isNotEmpty()) {
                    traceEvent(GameStateCollector.EventType.COMBAT_PROMPT, game, "DeclareBlockers attackers=${combat.attackers.size}")
                    sendDeclareBlockersReq(bridge)
                    return
                }
            }

            // AI blocking (defending against human attack): send state
            if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS && isHumanTurn) {
                val combat = game.phaseHandler.combat
                if (combat != null && combat.attackers.isNotEmpty()) {
                    traceEvent(GameStateCollector.EventType.SEND_STATE, game, "AI blocking result")
                    paceDelay(2)
                    sendRealGameState(bridge)
                    return
                }
            }

            // Combat damage: send state so damage is visible
            if (phase == PhaseType.COMBAT_DAMAGE) {
                traceEvent(GameStateCollector.EventType.SEND_STATE, game, "combat damage")
                paceDelay(2)
                sendRealGameState(bridge)
                return
            }

            // Combat end: send state if combat actually happened
            if (phase == PhaseType.COMBAT_END) {
                val combat = game.phaseHandler.combat
                if (combat != null && combat.attackers.isNotEmpty()) {
                    traceEvent(GameStateCollector.EventType.SEND_STATE, game, "combat end")
                    sendRealGameState(bridge)
                    return
                }
            }

            // Check for pending interactive prompt (targeting, sacrifice, etc.)
            val pendingPrompt = bridge.promptBridge.getPendingPrompt()
            if (pendingPrompt != null && pendingPrompt.request.candidateRefs.isNotEmpty()) {
                traceEvent(GameStateCollector.EventType.TARGET_PROMPT, game, "targets=${pendingPrompt.request.candidateRefs.size}")
                val req = StateMapper.buildSelectTargetsReq(pendingPrompt, bridge)
                sendSelectTargetsReq(bridge, req)
                return
            }

            val actions = StateMapper.buildActions(game, seatId, bridge)
            if (!BundleBuilder.shouldAutoPass(actions)) {
                val actionSummary = actions.actionsList
                    .groupBy { it.actionType.name.removeSuffix("_add3") }
                    .map { (t, v) -> "$t=${v.size}" }
                    .joinToString(" ")
                traceEvent(GameStateCollector.EventType.SEND_STATE, game, "actions: $actionSummary")
                sendRealGameState(bridge)
                return
            }

            val pending = bridge.actionBridge.getPending()
            log.debug("autoPass: phase={} turn={} aiTurn={} pending={}", phase, game.phaseHandler.turn, isAiTurn, pending != null)

            if (pending != null) {
                traceEvent(GameStateCollector.EventType.AUTO_PASS, game, "human priority, pass-only")
                // EdictalMessage: tell client we're server-forcing a pass (breaks autoPassPriority mode)
                val edictal = BundleBuilder.edictalPass(seatId, msgIdCounter, gameStateId)
                msgIdCounter = edictal.nextMsgId
                sendBundledGRE(edictal.messages)
                bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
                bridge.awaitPriority()
            } else if (isAiTurn) {
                traceEvent(GameStateCollector.EventType.AI_TURN_WAIT, game, "waiting for AI")
                val reachedPriority = bridge.awaitPriorityWithTimeout(GameBridge.AI_TURN_WAIT_MS)
                if (!reachedPriority) {
                    val g = bridge.getGame()
                    if (g != null && g.isGameOver) {
                        traceEvent(GameStateCollector.EventType.GAME_OVER, game, "game over during AI wait")
                        sendGameOver()
                        return
                    }
                    traceEvent(GameStateCollector.EventType.AI_TURN_TIMEOUT, game, "AI turn timed out")
                    log.warn("autoPass: AI turn timed out, sending current state")
                    sendRealGameState(bridge)
                    return
                }
            } else {
                traceEvent(GameStateCollector.EventType.PRIORITY_GRANT, game, "waiting for engine")
                log.warn("autoPass: no pending action, waiting for priority")
                bridge.awaitPriority()
            }
        }

        log.warn("autoPassAndAdvance: hit max iterations ({})", AUTO_PASS_MAX_ITERATIONS)
        sendRealGameState(bridge)
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
        inInteractivePrompt = true

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
        inInteractivePrompt = true

        NexusTap.outboundTemplate("DeclareBlockersReq seat=$seatId")
        sendBundledGRE(result.messages)
        bridge.playback?.seedCounters(msgIdCounter, gameStateId)
    }

    private fun sendSelectTargetsReq(bridge: GameBridge, req: SelectTargetsReq) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.selectTargetsBundle(game, bridge, matchId, seatId, msgIdCounter, gameStateId, req)
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId
        inInteractivePrompt = true
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

        val gameResult = ResultSpec.newBuilder()
            .setScope(MatchScope.Game_a146)
            .setResult(ResultType.WinLoss)
            .setWinningTeamId(winningTeam)

        val matchResult = ResultSpec.newBuilder()
            .setScope(MatchScope.Match)
            .setResult(ResultType.WinLoss)
            .setWinningTeamId(winningTeam)

        val gameInfo = GameInfo.newBuilder()
            .setStage(GameStage.GameOver)
            .setMatchState(MatchState.MatchComplete)
            .setMulliganType(MulliganType.London)
            .setMatchWinCondition(MatchWinCondition.SingleElimination)
            .addResults(gameResult)
            .addResults(matchResult)

        val players = listOf(
            PlayerInfo.newBuilder().setSystemSeatNumber(1).setStatus(PlayerStatus.Removed_a1c6).setTeamId(1),
            PlayerInfo.newBuilder().setSystemSeatNumber(2).setStatus(PlayerStatus.Removed_a1c6).setTeamId(2),
        )

        gameStateId++
        val gs1 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff).setGameStateId(gameStateId)
            .setGameInfo(gameInfo).setUpdate(GameStateUpdate.SendAndRecord)
        players.forEach { gs1.addPlayers(it) }

        gameStateId++
        val gs2 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff).setGameStateId(gameStateId)
            .setGameInfo(gameInfo).setUpdate(GameStateUpdate.SendAndRecord)

        gameStateId++
        val gs3 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff).setGameStateId(gameStateId)
            .setUpdate(GameStateUpdate.SendAndRecord)

        val messages = mutableListOf(
            makeGRE(GREMessageType.GameStateMessage_695e, gameStateId - 2) { it.gameStateMessage = gs1.build() },
            makeGRE(GREMessageType.GameStateMessage_695e, gameStateId - 1) { it.gameStateMessage = gs2.build() },
            makeGRE(GREMessageType.GameStateMessage_695e, gameStateId) { it.gameStateMessage = gs3.build() },
        )

        messages.add(
            makeGRE(GREMessageType.IntermissionReq_695e, gameStateId) {
                it.intermissionReq = IntermissionReq.newBuilder()
                    .setResult(
                        ResultSpec.newBuilder()
                            .setScope(MatchScope.Match)
                            .setResult(ResultType.WinLoss)
                            .setWinningTeamId(winningTeam),
                    )
                    .build()
            },
        )

        sendBundledGRE(messages)
        log.info("MatchSession: sent game-over sequence (winner=team{})", winningTeam)
    }

    // --- Low-level helpers ---

    /** Build a single GRE message (doesn't send). */
    private fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val gre = GREToClientMessage.newBuilder()
            .setType(type).setMsgId(msgIdCounter++).setGameStateId(gsId).addSystemSeatIds(seatId)
        configure(gre)
        return gre.build()
    }

    /** Send multiple GRE messages bundled in one GreToClientEvent + mirror to peer. */
    private fun sendBundledGRE(messages: List<GREToClientMessage>) {
        ArenaDebugCollector.recordOutbound(messages, seatId)
        GameStateCollector.collectOutbound(messages, ArenaDebugCollector.currentSeq())
        sink.send(messages)
        mirrorToFamiliar(messages)
    }

    /** Send a copy of GRE messages to the Familiar (seat 2) via registry. */
    private fun mirrorToFamiliar(messages: List<GREToClientMessage>) {
        if (seatId != 1) return
        val peer = registry.getPeer(matchId, seatId) ?: return
        val mirrored = messages.map { gre ->
            gre.toBuilder().clearSystemSeatIds().addSystemSeatIds(2).build()
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
        GameStateCollector.recordEvent(gameStateId, type, phase, turn, detail, priority, stackDepth, ArenaDebugCollector.currentSeq())
    }

    /** Pacing delay — skipped when paceDelayMs == 0 (tests). */
    private fun paceDelay(multiplier: Int) {
        val delay = paceDelayMs * multiplier
        if (delay > 0) Thread.sleep(delay)
    }
}
