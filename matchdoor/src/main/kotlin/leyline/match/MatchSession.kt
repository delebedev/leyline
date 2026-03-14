package leyline.match

import forge.game.Game
import leyline.bridge.ClientAutoPassState
import leyline.bridge.InstanceId
import leyline.bridge.PhaseStopProfile
import leyline.bridge.PlayerAction
import leyline.bridge.SeatId
import leyline.frontdoor.service.MatchCoordinator
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import leyline.game.MessageCounter
import leyline.game.StateMapper
import leyline.game.mapper.StopTypeMapping
import leyline.infra.MessageSink
import leyline.protocol.HandshakeMessages
import leyline.protocol.ProtoDump
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
 * to [GamePlayback][leyline.game.GamePlayback]. No seeding or
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
    override val recorder: MatchRecorder? = null,
    override var counter: MessageCounter = MessageCounter(),
    /** Debug diagnostics sink — protocol messages + game state collector. Null in tests. */
    private val debugSink: MatchDebugSink? = null,
    /** Cross-BC coordinator — match results flow back to FD services. */
    val coordinator: MatchCoordinator? = null,
) : SessionOps {
    private val log = LoggerFactory.getLogger(MatchSession::class.java)

    /** Serializes all game-logic entry points (Netty I/O threads are concurrent). */
    private val sessionLock = Any()

    override var gameBridge: GameBridge? = null
        private set

    /** Client player ID — set by MatchHandler after auth, used in MatchCompleted room state. */
    var playerId: String = "forge-player-1"

    /** Saved client settings for echoing in SetSettingsResp. */
    var clientSettings: SettingsMessage? = null

    /** Client auto-pass settings (autoPassOption / stackAutoPassOption). */
    val autoPassState = ClientAutoPassState()

    /** Sub-handlers for combat, targeting, and auto-pass flows. */
    val combatHandler = CombatHandler(this)
    val targetingHandler = TargetingHandler(this)
    val autoPassEngine = AutoPassEngine(this, combatHandler, targetingHandler, autoPassState)

    /**
     * Wire the game bridge (called by [MatchHandler] after bridge creation).
     *
     * When the bridge was created by a different seat (seat 2 creates, seat 1
     * reuses), the bridge's [MessageCounter] is a different instance from this
     * session's counter. Handshake messages already advanced this session's
     * counter, so we sync the bridge's counter up to our values, then adopt
     * it as our own — ensuring the engine thread (GamePlayback) and this
     * session share a single counter.
     */
    override fun connectBridge(bridge: GameBridge): Unit = synchronized(sessionLock) {
        gameBridge = bridge
        val bridgeCounter = bridge.messageCounter
        if (bridgeCounter !== counter) {
            // Advance bridge counter to at least where our handshake left off
            if (counter.currentGsId() > bridgeCounter.currentGsId()) {
                bridgeCounter.setGsId(counter.currentGsId())
            }
            if (counter.currentMsgId() > bridgeCounter.currentMsgId()) {
                bridgeCounter.setMsgId(counter.currentMsgId())
            }
            counter = bridgeCounter
        }
        // Wire autoPassState to WebPlayerController so full control mode works
        bridge.humanController?.setAutoPassState(autoPassState)
    }

    // --- Public entry points (called by MatchHandler) ---

    /**
     * After keep: wait for engine to reach priority, send real game state bundle.
     * Then auto-pass through phases where only Pass is available.
     */
    override fun onMulliganKeep() = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        log.info("MatchSession: waiting for engine to reach priority after keep")

        bridge.awaitPriority()

        val game = bridge.getGame() ?: return

        traceEvent(MatchEventType.GAME_START, game, "post-mulligan, entering Main1")

        // Drain AI action diffs queued during awaitPriority.
        // These have gsIds allocated by the engine thread via the shared counter
        // during awaitPriority. Send them first (lower gsIds).
        val playback = bridge.playbacks[SeatId(seatId)]
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
    /**
     * Trigger autoPassAndAdvance without submitting an action first.
     * Used by tests when the engine is already at a combat phase and
     * CombatHandler needs to send the prompt (DeclareBlockersReq).
     */
    fun triggerAutoPass(bridge: GameBridge) = synchronized(sessionLock) {
        autoPassEngine.autoPassAndAdvance(bridge)
    }

    override fun onPuzzleStart() = synchronized(sessionLock) {
        val bridge = gameBridge ?: return

        // FamiliarSession inherits a no-op onPuzzleStart from SessionOps, so this
        // path only fires for MatchSession. Warn if somehow called for a non-seat-1
        // MatchSession — it would consume seat 1's pending priority via the shared
        // ActionBridge, advancing the engine past Main1.
        if (seatId != 1) {
            log.warn("MatchSession: onPuzzleStart called for seat {} — expected seat 1 only", seatId)
            return
        }

        log.info("MatchSession: puzzle start, seeding snapshot and entering game loop")

        val game = bridge.getGame() ?: return

        traceEvent(MatchEventType.GAME_START, game, "puzzle-start")

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
    override fun onPerformAction(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
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

        // Track autoPassPriority from PerformActionResp (full control / auto-pass OK)
        val autoPassPriority = greMsg.performActionResp.autoPassPriority
        if (autoPassPriority != AutoPassPriority.None_a099) {
            autoPassState.updateAutoPassPriority(autoPassPriority)
            log.debug("MatchSession: autoPassPriority={}", autoPassPriority)
        }

        val action = greMsg.performActionResp.actionsList.firstOrNull()
        if (action == null) {
            log.warn("MatchSession: PerformActionResp with no actions")
            return
        }

        // Stop decision timer — client responded
        if (bridge.matchConfig.game.timer) {
            val timerStop = BundleBuilder.timerStop(seatId, counter)
            sendBundledGRE(timerStop.messages)
        }

        Tap.inboundAction(action)
        recorder?.recordClientAction(greMsg)

        val isCastOrActivate = action.actionType == ActionType.Cast ||
            action.actionType == ActionType.Activate_add3
        val game = bridge.getGame()
        val stackWasNonEmpty = game != null && !game.stack.isEmpty
        if (game != null) {
            val actionName = action.actionType.name.removeSuffix("_add3")
            val cardName = if (action.instanceId != 0) {
                bridge.cards.findNameByGrpId(action.grpId)?.let { " ($it)" } ?: ""
            } else {
                ""
            }
            traceEvent(MatchEventType.CLIENT_ACTION, game, "$actionName iid=${action.instanceId}$cardName")
        }

        when (action.actionType) {
            ActionType.Pass -> {
                bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            }
            ActionType.Play_add3 -> {
                val forgeCardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val submitted = if (forgeCardId != null) {
                    bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(forgeCardId))
                } else {
                    bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
                }
                Tap.actionResult(action.actionType, action.instanceId, forgeCardId?.value, submitted)
            }
            ActionType.Cast -> {
                val forgeCardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val submitted = if (forgeCardId != null) {
                    bridge.actionBridge.submitAction(pending.actionId, PlayerAction.CastSpell(forgeCardId))
                } else {
                    bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
                }
                Tap.actionResult(action.actionType, action.instanceId, forgeCardId?.value, submitted)
            }
            ActionType.Activate_add3 -> {
                val forgeCardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val abilityIndex = resolveAbilityIndex(action, bridge)
                val submitted = if (forgeCardId != null) {
                    bridge.actionBridge.submitAction(
                        pending.actionId,
                        PlayerAction.ActivateAbility(forgeCardId, abilityIndex),
                    )
                } else {
                    bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
                }
                Tap.actionResult(action.actionType, action.instanceId, forgeCardId?.value, submitted)
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

        // After stack resolution: check for modal ETB prompt before sending state.
        // The engine may have fired a modal trigger (e.g. Charming Prince ETB)
        // during resolution, blocking in chooseModeForAbility.
        if (stackWasNonEmpty) {
            val g = bridge.getGame()
            if (g != null) {
                // Check for pending modal prompt from ETB trigger
                when (targetingHandler.checkPendingPrompt(bridge, g)) {
                    TargetingHandler.PromptResult.SENT_TO_CLIENT -> return
                    TargetingHandler.PromptResult.AUTO_RESOLVED -> {
                        // Fall through to autoPass
                    }
                    TargetingHandler.PromptResult.NONE -> {
                        if (g.stack.isEmpty) {
                            log.info("MatchSession: stack resolved, sending intermediate resolution state")
                            sendRealGameState(bridge)
                            return
                        }
                    }
                }
            }
        }

        autoPassEngine.autoPassAndAdvance(bridge)
    }

    /** Handle DeclareAttackersResp — delegates to [CombatHandler]. */
    override fun onDeclareAttackers(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        combatHandler.onDeclareAttackers(greMsg, bridge) { autoPassEngine.autoPassAndAdvance(it) }
    }

    /** Handle DeclareBlockersResp — delegates to [CombatHandler]. */
    override fun onDeclareBlockers(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        combatHandler.onDeclareBlockers(greMsg, bridge) { autoPassEngine.autoPassAndAdvance(it) }
    }

    /** Handle SelectTargetsResp — delegates to [TargetingHandler]. */
    override fun onSelectTargets(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        targetingHandler.onSelectTargets(greMsg, bridge) { autoPassEngine.autoPassAndAdvance(it) }
    }

    /** Handle SelectNResp — delegates to [TargetingHandler]. */
    override fun onSelectN(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        targetingHandler.onSelectN(greMsg, bridge) { autoPassEngine.autoPassAndAdvance(it) }
    }

    /** Handle GroupResp for surveil/scry — delegates to [TargetingHandler]. */
    override fun onGroupResp(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        targetingHandler.onGroupResp(greMsg, bridge) { autoPassEngine.autoPassAndAdvance(it) }
    }

    /** Handle CastingTimeOptionsResp — delegates to [TargetingHandler]. */
    override fun onCastingTimeOptions(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        targetingHandler.onCastingTimeOptions(greMsg, bridge) { autoPassEngine.autoPassAndAdvance(it) }
    }

    /**
     * Handle CancelActionReq — player cancelled targeting (backed out of spell cast).
     *
     * Submits an empty target list to the pending prompt, which causes the engine
     * to return `TargetSelectionResult(false, false)` → spell targeting fails →
     * engine unwinds the cast (removes from stack, returns mana).
     */
    override fun onCancelAction(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        // During combat declaration, cancel means "pass combat" (submit empty attackers).
        if (combatHandler.pendingLegalAttackers.isNotEmpty()) {
            combatHandler.onCancelAttackers(bridge) { autoPassEngine.autoPassAndAdvance(it) }
            return
        }
        targetingHandler.onCancelAction(bridge) { autoPassEngine.autoPassAndAdvance(it) }
    }

    /** Handle concede: send game-over sequence. Bridge stays alive (same as lethal path). */
    // TODO: clean up bridge after game-over (both concede and lethal paths leak the engine
    // thread until server restart). Needs a delayed cleanup — immediate shutdown breaks
    // concede because the client sends messages after game-over that need a live session.
    override fun onConcede() = synchronized(sessionLock) {
        sendGameOver(ResultReason.Concede)
    }

    /** Handle SetSettingsReq: merge settings, apply stops to PhaseStopProfile, echo response. */
    override fun onSettings(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
        val reqSettings = greMsg.setSettingsReq
        val incoming = reqSettings.settings
        log.info(
            "MatchSession: SetSettingsReq (stops={} transientStops={})",
            incoming.stopsCount,
            incoming.transientStopsCount,
        )

        // Merge incoming delta into accumulated clientSettings (client sends only changed fields).
        clientSettings = mergeSettings(clientSettings, incoming)

        // Apply stop changes to the live PhaseStopProfile so the engine
        // respects client's phase ladder toggles — both Team and Opponents scopes.
        applyStopsToProfile(incoming)

        // Track autoPassOption / stackAutoPassOption for priority decisions.
        autoPassState.update(incoming)
        log.debug("MatchSession: autoPassOption={} stackAutoPassOption={}", autoPassState.autoPassOption, autoPassState.stackAutoPassOption)

        val (msg, nextMsgId) = HandshakeMessages.settingsResp(seatId, counter.currentMsgId(), counter.currentGsId(), clientSettings)
        counter.setMsgId(nextMsgId)
        ProtoDump.dump(msg, "SettingsResp")
        sink.sendRaw(msg)
    }

    /**
     * Map client [SettingsMessage] stops + transientStops to [PhaseStopProfile] updates.
     *
     * Team scope → human player's own-turn stops.
     * Opponents scope → AI player's turn stops (seat math: if human=1, AI=2).
     *
     * TransientStops have the same [Stop] shape; v1 treats them as persistent
     * (no one-shot consume yet).
     */
    private fun applyStopsToProfile(settings: SettingsMessage) {
        val bridge = gameBridge ?: return
        val profile = bridge.phaseStopProfile ?: return
        val humanPlayer = bridge.getPlayer(SeatId(seatId)) ?: return
        val aiSeatId = if (seatId == 1) 2 else 1
        val aiPlayer = bridge.getPlayer(SeatId(aiSeatId)) ?: return

        // Honor clear-all flags even when no explicit stops present
        if (settings.clearAllStops == SettingStatus.Set || settings.clearAllYields == SettingStatus.Set) {
            profile.clearAll(humanPlayer.id)
            profile.clearAll(aiPlayer.id)
            autoPassState.clearOpponentStops()
            log.debug("MatchSession: clearAll — clearAllStops={} clearAllYields={}", settings.clearAllStops, settings.clearAllYields)
        }

        // Combine stops + transientStops (same proto shape)
        val allStops = settings.stopsList + settings.transientStopsList
        if (allStops.isEmpty()) return

        // Apply per-scope
        applyStopsForPlayer(allStops, SettingScope.Team_ac6e, humanPlayer.id, profile)
        applyStopsForPlayer(allStops, SettingScope.Opponents, aiPlayer.id, profile)

        // Mirror Opponents-scope stops into ClientAutoPassState for session-layer
        // opponent-turn check (separate from engine-internal AI_DEFAULTS).
        val opponentEnabled = StopTypeMapping.parseStops(allStops, SettingScope.Opponents)
        val opponentDisabled = allStops
            .filter { it.status == SettingStatus.Clear_a3fe }
            .filter { it.appliesTo == SettingScope.Opponents || it.appliesTo == SettingScope.AnyPlayer }
            .mapNotNull { StopTypeMapping.toPhaseType(it.stopType) }
            .toSet()
        for (phase in opponentEnabled) autoPassState.setOpponentStop(phase, true)
        for (phase in opponentDisabled) autoPassState.setOpponentStop(phase, false)

        log.debug(
            "MatchSession: applied stops — human={} ai={}",
            profile.getEnabled(humanPlayer.id).map { it.name },
            profile.getEnabled(aiPlayer.id).map { it.name },
        )
    }

    /** Apply Set/Clear stops matching [scope] to the given player in [profile]. */
    private fun applyStopsForPlayer(
        stops: List<Stop>,
        scope: SettingScope,
        playerId: Int,
        profile: PhaseStopProfile,
    ) {
        val enabled = StopTypeMapping.parseStops(stops, scope)
        val disabled = stops
            .filter { it.status == SettingStatus.Clear_a3fe }
            .filter { it.appliesTo == scope || it.appliesTo == SettingScope.AnyPlayer }
            .mapNotNull { StopTypeMapping.toPhaseType(it.stopType) }
            .toSet()

        for (phase in enabled) profile.setEnabled(playerId, phase, true)
        for (phase in disabled) profile.setEnabled(playerId, phase, false)
    }

    /**
     * Map Arena abilityGrpId → Forge ability index for multi-ability cards.
     *
     * CardData.abilityIds layout: [keyword0, ..., activate0, activate1, ...]
     * Keyword entries occupy the first keywordAbilityGrpIds.size slots.
     * Falls back to 0 if lookup fails (single-ability cards, missing data).
     */
    private fun resolveAbilityIndex(action: Action, bridge: GameBridge): Int {
        val abilityGrpId = action.abilityGrpId
        if (abilityGrpId == 0) return 0
        val cardData = bridge.cards.findByGrpId(action.grpId) ?: return 0
        val keywordCount = cardData.keywordAbilityGrpIds.size
        val slotIndex = cardData.abilityIds.indexOfFirst { it.first == abilityGrpId }
        if (slotIndex < 0) return 0
        val abilityIndex = slotIndex - keywordCount
        return if (abilityIndex >= 0) abilityIndex else 0
    }

    /**
     * Merge incoming settings delta into accumulated settings.
     * Stops keyed by (stopType, appliesTo) — incoming overrides existing.
     * Clear status marks a stop as disabled but does not remove it from the set.
     */
    companion object {
        fun mergeSettings(existing: SettingsMessage?, incoming: SettingsMessage): SettingsMessage {
            if (existing == null) return incoming
            val merged = existing.toBuilder()

            // Merge stops: build a map keyed by (stopType, appliesTo), incoming overrides existing
            val stopMap = linkedMapOf<Pair<Int, Int>, Stop>()
            for (stop in existing.stopsList) {
                stopMap[stop.stopType.number to stop.appliesTo.number] = stop
            }
            for (stop in incoming.stopsList) {
                stopMap[stop.stopType.number to stop.appliesTo.number] = stop
            }
            merged.clearStops().addAllStops(stopMap.values)

            // Merge transientStops the same way
            val transMap = linkedMapOf<Pair<Int, Int>, Stop>()
            for (stop in existing.transientStopsList) {
                transMap[stop.stopType.number to stop.appliesTo.number] = stop
            }
            for (stop in incoming.transientStopsList) {
                transMap[stop.stopType.number to stop.appliesTo.number] = stop
            }
            merged.clearTransientStops().addAllTransientStops(transMap.values)

            // Merge scalar fields only when incoming has non-default values
            if (incoming.autoPassOption != AutoPassOption.None_a465) {
                merged.autoPassOption = incoming.autoPassOption
            }
            if (incoming.stackAutoPassOption != AutoPassOption.None_a465) {
                merged.stackAutoPassOption = incoming.stackAutoPassOption
            }

            return merged.build()
        }
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

        // Decision timer — client shows rope countdown while waiting for action
        if (bridge.matchConfig.game.timer) {
            val timer = BundleBuilder.timerStart(seatId, counter)
            sendBundledGRE(timer.messages)
        }
    }

    /** Apply a [BundleBuilder.BundleResult]: tap-log and send. */
    override fun sendBundle(result: BundleBuilder.BundleResult) {
        for (gre in result.messages) {
            if (gre.hasGameStateMessage()) Tap.outboundState(gre.gameStateMessage)
            if (gre.hasActionsAvailableReq()) Tap.outboundActions(gre.actionsAvailableReq)
        }
        sendBundledGRE(result.messages)
    }

    /**
     * Send game-over sequence: 3x GS Diff + IntermissionReq + MatchCompleted room state.
     *
     * Per client decompilation (post-game protocol), the full sequence is:
     * 1. Server sends 3x GSM (GameOver) + IntermissionReq
     * 2. Client responds with CheckpointReq (handled in MatchHandler)
     * 3. Server sends MatchGameRoomStateChangedEvent (MatchCompleted)
     *
     * We send MatchCompleted immediately after IntermissionReq rather than
     * waiting for CheckpointReq — the client tolerates this ordering and it
     * avoids needing cross-layer coordination between MatchHandler and MatchSession.
     */
    override fun sendGameOver(reason: ResultReason) {
        val bridge = gameBridge
        val humanPlayer = bridge?.getPlayer(SeatId(seatId))
        val humanWon = humanPlayer?.getOutcome()?.hasWon() ?: false
        val winningTeam = if (humanWon) 1 else 2
        val losingPlayerSeatId = if (humanWon) 2 else 1
        val lossReason = if (reason == ResultReason.Concede) 3 else 0

        val result = BundleBuilder.gameOverBundle(
            winningTeam,
            matchId,
            seatId,
            counter,
            reason = reason,
            losingPlayerSeatId = losingPlayerSeatId,
            lossReason = lossReason,
            bridge = bridge,
        )
        sendBundledGRE(result.messages)
        log.info("MatchSession: sent game-over GRE sequence (winner=team{}, reason={})", winningTeam, reason)

        // Send MatchCompleted room state — triggers the client's result screen
        val matchCompletedMsg = HandshakeMessages.matchCompleted(matchId, winningTeam, playerId, reason)
        sink.sendRaw(matchCompletedMsg)
        log.info("MatchSession: sent MatchCompleted room state")

        // Notify coordinator (e.g. CourseService for sealed events)
        try {
            coordinator?.reportMatchResult(humanWon)
        } catch (e: Exception) {
            log.warn("MatchSession: reportMatchResult failed: {}", e.message)
        }

        // Trigger post-game analysis
        recorder?.run {
            markGameOver()
            shutdown()
        }
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
        debugSink?.recordOutbound(messages, seatId)
        debugSink?.collectOutbound(messages, debugSink?.currentSeq() ?: 0)
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
        // Only mirror to FamiliarSession — PvP peers build their own state
        // via per-seat GamePlayback.
        if (peer !is FamiliarSession) return
        val mirrorSeat = 2
        // Filter out CastingTimeOptionsReq — Familiar must not auto-respond to modal prompts
        val filtered = messages.filter { it.type != GREMessageType.CastingTimeOptionsReq_695e }
        if (filtered.isEmpty()) return
        val mirrored = filtered.map { gre ->
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
    override fun traceEvent(type: MatchEventType, game: Game, detail: String) {
        val phase = game.phaseHandler.phase?.name
        val turn = game.phaseHandler.turn
        val human = gameBridge?.getPlayer(SeatId(seatId))
        val priority = when (game.phaseHandler.priorityPlayer) {
            human -> "human"
            else -> "ai"
        }
        val stackDepth = game.stack?.size() ?: 0
        debugSink?.recordEvent(counter.currentGsId(), type, phase, turn, detail, priority, stackDepth, debugSink.currentSeq())
    }

    /** Pacing delay — skipped when paceDelayMs == 0 (tests). */
    override fun paceDelay(multiplier: Int) {
        val delay = paceDelayMs * multiplier
        if (delay > 0) Thread.sleep(delay)
    }
}
