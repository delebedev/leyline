package forge.nexus.server

import forge.game.Game
import forge.nexus.debug.GameStateCollector
import forge.nexus.debug.NexusDebugCollector
import forge.nexus.debug.NexusTap
import forge.nexus.debug.SessionRecorder
import forge.nexus.game.BundleBuilder
import forge.nexus.game.CardDb
import forge.nexus.game.GameBridge
import forge.nexus.game.MessageCounter
import forge.nexus.game.StateMapper
import forge.nexus.game.StopTypeMapping
import forge.nexus.protocol.HandshakeMessages
import forge.nexus.protocol.ProtoDump
import forge.web.game.PlayerAction
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.Visibility

/**
 * Game orchestration session — thin dispatcher for post-mulligan game logic.
 *
 * Delegates combat flows to [CombatHandler], targeting to [TargetingHandler],
 * and the auto-pass loop to [AutoPassEngine]. Owns the [sessionLock], message
 * sending, and Familiar mirroring.
 *
 * Protocol sequencing uses a shared [MessageCounter] — same instance is passed
 * to [NexusGamePlayback][forge.nexus.game.NexusGamePlayback]. No seeding or
 * syncing needed.
 *
 * Transport-agnostic: sends messages through [MessageSink].
 * [MatchHandler] creates one per connection and delegates GRE messages here.
 */
class MatchSession(
    override val seatId: Int,
    override val matchId: String,
    val sink: MessageSink,
    val registry: MatchRegistry,
    val paceDelayMs: Long = 200L,
    val recorder: SessionRecorder? = null,
    override val counter: MessageCounter = MessageCounter(),
) : SessionOps {
    private val log = LoggerFactory.getLogger(MatchSession::class.java)

    /** Serializes all game-logic entry points (Netty I/O threads are concurrent). */
    private val sessionLock = Any()

    var gameBridge: GameBridge? = null
        private set

    /** Saved client settings for echoing in SetSettingsResp. */
    var clientSettings: SettingsMessage? = null

    /** Sub-handlers for combat, targeting, and auto-pass flows. */
    val combatHandler = CombatHandler(this)
    val targetingHandler = TargetingHandler(this)
    val autoPassEngine = AutoPassEngine(this, combatHandler, targetingHandler)

    /** Wire the game bridge (called by [MatchHandler] after bridge creation). */
    fun connectBridge(bridge: GameBridge) {
        gameBridge = bridge
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

        traceEvent(GameStateCollector.EventType.GAME_START, game, "post-mulligan, entering Main1")

        // Drain AI action diffs queued during awaitPriority.
        // These have gsIds allocated by the engine thread via the shared counter
        // during awaitPriority. Send them first (lower gsIds).
        val playback = bridge.playback
        if (playback != null) {
            for (batch in playback.drainQueue()) {
                sendBundledGRE(batch)
            }
        }

        // phaseTransitionDiff after AI diffs — uses the shared counter which is
        // now past whatever the engine allocated. gsIds are higher than AI diffs
        // but the prevGsId chain is valid (references last AI diff's gsId).
        val result = BundleBuilder.phaseTransitionDiff(game, bridge, matchId, seatId, counter)
        sendBundle(result)

        // Seed state snapshot for subsequent diff computation.
        bridge.snapshotState(StateMapper.buildFromGame(game, counter.currentGsId(), matchId, bridge))

        // Auto-pass through phases where human has no real actions
        autoPassEngine.autoPassAndAdvance(bridge)
    }

    /**
     * Puzzle start: seed snapshot, enter auto-pass loop.
     * Similar to [onMulliganKeep] but without mulligan seeding or phaseTransitionDiff
     * — the puzzle initial bundle already sent a Full GSM with the board state.
     */
    fun onPuzzleStart() = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        log.info("MatchSession: puzzle start, seeding snapshot and entering game loop")

        val game = bridge.getGame() ?: return

        traceEvent(GameStateCollector.EventType.GAME_START, game, "puzzle-start")

        // Seed state snapshot for subsequent diff computation.
        // The puzzle initial bundle already sent the Full GSM, so the bridge
        // needs a matching snapshot for the first Diff to be correct.
        bridge.snapshotState(StateMapper.buildFromGame(game, counter.currentGsId(), matchId, bridge))

        // Auto-pass through phases where human has no real actions
        autoPassEngine.autoPassAndAdvance(bridge)
    }

    /**
     * Handle a client action (land play, spell cast, pass) and advance the engine.
     */
    fun onPerformAction(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        log.info("MatchSession: onPerformAction enter gsId={} (current={})", greMsg.gameStateId, counter.currentGsId())

        // Reject stale actions — client may resend with outdated gameStateId
        val clientGsId = greMsg.gameStateId
        if (clientGsId != 0 && clientGsId < counter.currentGsId()) {
            log.warn("MatchSession: stale PerformActionResp gsId={} (current={}), ignoring", clientGsId, counter.currentGsId())
            return
        }

        val pending = bridge.actionBridge.getPending() ?: run {
            log.warn("MatchSession: PerformActionResp but no pending action — recovering")
            autoPassEngine.autoPassAndAdvance(bridge)
            return
        }

        val action = greMsg.performActionResp.actionsList.firstOrNull()
        if (action == null) {
            log.warn("MatchSession: PerformActionResp with no actions")
            return
        }

        NexusTap.inboundAction(action)
        recorder?.recordClientAction(greMsg)

        val isCastOrActivate = action.actionType == ActionType.Cast ||
            action.actionType == ActionType.Activate_add3
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
            ActionType.Activate_add3 -> {
                val forgeCardId = bridge.getForgeCardId(action.instanceId)
                // Map abilityGrpId → index in card's non-mana activated abilities.
                // For single-ability cards (most common), index 0 is correct.
                // TODO: correlate abilityGrpId with CardDb.abilityIds for multi-ability cards
                val abilityIndex = 0
                val submitted = if (forgeCardId != null) {
                    bridge.actionBridge.submitAction(
                        pending.actionId,
                        PlayerAction.ActivateAbility(forgeCardId, abilityIndex),
                    )
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

        // After a cast or activate, check for targeting prompt or intermediate stack state
        if (isCastOrActivate && targetingHandler.handlePostCastPrompt(bridge)) return

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

        autoPassEngine.autoPassAndAdvance(bridge)
    }

    /** Handle DeclareAttackersResp — delegates to [CombatHandler]. */
    fun onDeclareAttackers(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        combatHandler.onDeclareAttackers(greMsg, bridge) { autoPassEngine.autoPassAndAdvance(it) }
    }

    /** Handle DeclareBlockersResp — delegates to [CombatHandler]. */
    fun onDeclareBlockers(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        combatHandler.onDeclareBlockers(greMsg, bridge) { autoPassEngine.autoPassAndAdvance(it) }
    }

    /** Handle SelectTargetsResp — delegates to [TargetingHandler]. */
    fun onSelectTargets(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        targetingHandler.onSelectTargets(greMsg, bridge) { autoPassEngine.autoPassAndAdvance(it) }
    }

    /** Handle SelectNResp — delegates to [TargetingHandler]. */
    fun onSelectN(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        targetingHandler.onSelectN(greMsg, bridge) { autoPassEngine.autoPassAndAdvance(it) }
    }

    /** Handle concede: remove bridge and send game-over. */
    fun onConcede() = synchronized(sessionLock) {
        registry.removeBridge(matchId)?.shutdown()
        sendGameOver()
    }

    /** Handle SetSettingsReq: save settings, apply stops to PhaseStopProfile, echo response. */
    fun onSettings(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val reqSettings = greMsg.setSettingsReq
        clientSettings = reqSettings.settings
        log.info("MatchSession: SetSettingsReq (stops={})", reqSettings.settings.stopsCount)

        // Apply stop changes to the live PhaseStopProfile so the engine
        // respects client's phase ladder toggles.
        applyStopsToProfile(reqSettings.settings)

        val (msg, nextMsgId) = HandshakeMessages.settingsResp(seatId, counter.currentMsgId(), counter.currentGsId(), clientSettings)
        counter.setMsgId(nextMsgId)
        ProtoDump.dump(msg, "SettingsResp")
        sink.sendRaw(msg)
    }

    /**
     * Map client [SettingsMessage] stops to [PhaseStopProfile] updates.
     *
     * Team scope → human player's own-turn stops.
     * Opponents scope → logged but deferred (v1: AI_DEFAULTS handle opponent turns).
     */
    private fun applyStopsToProfile(settings: SettingsMessage) {
        val bridge = gameBridge ?: return
        val profile = bridge.phaseStopProfile ?: return
        val humanPlayer = bridge.getPlayer(seatId) ?: return

        val stops = settings.stopsList
        if (stops.isEmpty()) return

        // Parse Team-scope stops (human's own turn)
        val teamEnabled = StopTypeMapping.parseStops(stops, SettingScope.Team_ac6e)
        val teamDisabled = stops
            .filter { it.status == SettingStatus.Clear_a3fe }
            .filter { it.appliesTo == SettingScope.Team_ac6e || it.appliesTo == SettingScope.AnyPlayer }
            .mapNotNull { StopTypeMapping.toPhaseType(it.stopType) }
            .toSet()

        for (phase in teamEnabled) {
            profile.setEnabled(humanPlayer.id, phase, true)
        }
        for (phase in teamDisabled) {
            profile.setEnabled(humanPlayer.id, phase, false)
        }

        log.debug(
            "MatchSession: applied stops — enabled={} disabled={} profile={}",
            teamEnabled.map { it.name },
            teamDisabled.map { it.name },
            profile.getEnabled(humanPlayer.id).map { it.name },
        )
    }

    // --- Sending helpers ---

    /**
     * Build and send current game state + available actions from the live Forge engine.
     */
    override fun sendRealGameState(bridge: GameBridge) {
        val game = bridge.getGame() ?: run {
            log.warn("MatchSession: sendRealGameState but game is null")
            return
        }

        val result = BundleBuilder.postAction(game, bridge, matchId, seatId, counter)
        sendBundle(result)
    }

    /** Apply a [BundleBuilder.BundleResult]: tap-log and send. */
    override fun sendBundle(result: BundleBuilder.BundleResult) {
        for (gre in result.messages) {
            if (gre.hasGameStateMessage()) NexusTap.outboundState(gre.gameStateMessage)
            if (gre.hasActionsAvailableReq()) NexusTap.outboundActions(gre.actionsAvailableReq)
        }
        sendBundledGRE(result.messages)
    }

    /**
     * Send game-over sequence: 3x GS Diff + IntermissionReq + MatchCompleted room state.
     *
     * Per mtga-internals/docs/post-game-protocol.md, the full sequence is:
     * 1. Server sends 3x GSM (GameOver) + IntermissionReq
     * 2. Client responds with CheckpointReq (handled in MatchHandler)
     * 3. Server sends MatchGameRoomStateChangedEvent (MatchCompleted)
     *
     * We send MatchCompleted immediately after IntermissionReq rather than
     * waiting for CheckpointReq — the client tolerates this ordering and it
     * avoids needing cross-layer coordination between MatchHandler and MatchSession.
     */
    override fun sendGameOver() {
        val bridge = gameBridge
        val humanPlayer = bridge?.getPlayer(1)
        val humanWon = humanPlayer?.getOutcome()?.hasWon() ?: false
        val winningTeam = if (humanWon) 1 else 2

        val result = BundleBuilder.gameOverBundle(winningTeam, seatId, counter)
        sendBundledGRE(result.messages)
        log.info("MatchSession: sent game-over GRE sequence (winner=team{})", winningTeam)

        // Send MatchCompleted room state — triggers the client's result screen
        val matchCompletedMsg = HandshakeMessages.matchCompleted(matchId, winningTeam)
        sink.sendRaw(matchCompletedMsg)
        log.info("MatchSession: sent MatchCompleted room state")

        // Trigger post-game analysis
        recorder?.markGameOver()
        recorder?.close()
        recorder?.let { SessionRecorder.unregister(it) }
    }

    // --- Low-level helpers ---

    /** Build a single GRE message with an explicit msgId (no side-effect on counters). */
    override fun makeGRE(
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
    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        NexusDebugCollector.recordOutbound(messages, seatId)
        GameStateCollector.collectOutbound(messages, NexusDebugCollector.currentSeq())
        // Track last-sent TurnInfo so BundleBuilder.postAction() can detect phase
        // transitions even when PhaseStopProfile causes the engine to skip phases.
        val bridge = gameBridge
        if (bridge != null) {
            for (gre in messages) {
                if (gre.hasGameStateMessage()) {
                    bridge.updateLastSentTurnInfo(gre.gameStateMessage)
                }
            }
        }
        recorder?.recordOutbound(messages)
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
    override fun traceEvent(type: GameStateCollector.EventType, game: Game, detail: String) {
        val phase = game.phaseHandler.phase?.name
        val turn = game.phaseHandler.turn
        val human = gameBridge?.getPlayer(seatId)
        val priority = when (game.phaseHandler.priorityPlayer) {
            human -> "human"
            else -> "ai"
        }
        val stackDepth = game.stack?.size() ?: 0
        GameStateCollector.recordEvent(counter.currentGsId(), type, phase, turn, detail, priority, stackDepth, NexusDebugCollector.currentSeq())
    }

    /** Pacing delay — skipped when paceDelayMs == 0 (tests). */
    override fun paceDelay(multiplier: Int) {
        val delay = paceDelayMs * multiplier
        if (delay > 0) Thread.sleep(delay)
    }
}
