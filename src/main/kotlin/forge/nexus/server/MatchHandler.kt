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
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Match Door handler (port 30003).
 *
 * Uses template+patch pattern: protocol template payloads are loaded and
 * patched with dynamic fields (matchId, msgId, gsId, seatId).
 * This preserves fields the client needs but our .proto may not fully model.
 */
class MatchHandler : SimpleChannelInboundHandler<ClientToMatchServiceMessage>() {
    private val log = LoggerFactory.getLogger(MatchHandler::class.java)

    private var msgIdCounter = 1
    private var gameStateId = 0
    private var mulliganCount = 0
    private var matchId = "forge-match-1"
    private var clientId = "forge-player-1"
    private var seatId = 1

    /** Saved client settings for echoing in SetSettingsResp. */
    private var clientSettings: SettingsMessage? = null

    private var gameBridge: GameBridge? = null
    private var seat1Hand: List<Int> = emptyList()
    private var seat2Hand: List<Int> = emptyList()

    /** True while this seat is in an interactive prompt (attackers/blockers/targets). */
    private var inInteractivePrompt = false

    /** Legal attacker instanceIds from the last DeclareAttackersReq we sent. */
    private var pendingLegalAttackers: List<Int> = emptyList()

    companion object {
        /** matchId -> (seatId -> handler). Cross-connection signaling. */
        private val peerContexts = ConcurrentHashMap<String, ConcurrentHashMap<Int, MatchHandler>>()

        /** matchId -> shared bridge. Created by seat 1, reused by seat 2. */
        internal val sharedBridges = ConcurrentHashMap<String, GameBridge>()

        private const val AUTO_PASS_MAX_ITERATIONS = 50

        // Pacing delays (ms) — match WebGamePlayback timing for visual breathing room
        private const val AI_PHASE_DELAY_MS = 200L
        private const val AI_COMBAT_DELAY_MS = 400L
    }

    private var myCtx: ChannelHandlerContext? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        log.info("Match Door: client connected from {}", ctx.channel().remoteAddress())
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        NexusTap.inbound(msg.clientToMatchServiceMessageType, msg.requestId)
        ArenaDebugCollector.recordInbound(msg)

        when (msg.clientToMatchServiceMessageType) {
            ClientToMatchServiceMessageType.AuthenticateRequest_f487 -> handleMatchAuth(ctx, msg)
            ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487 -> handleMatchDoorConnect(ctx, msg)
            ClientToMatchServiceMessageType.ClientToGremessage -> handleGREMessage(ctx, msg)
            else -> log.info("Match Door: unhandled type: {}", msg.clientToMatchServiceMessageType)
        }
    }

    private fun handleMatchAuth(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val authReq = AuthenticateRequest.parseFrom(msg.payload)
        clientId = authReq.clientId.ifEmpty { "forge-player-$seatId" }
        val playerName = authReq.playerName.ifEmpty { "ForgePlayer" }
        log.info("Match Door: auth clientId={} playerName={}", clientId, playerName)

        val resp = MatchServiceToClientMessage.newBuilder()
            .setRequestId(msg.requestId)
            .setAuthenticateResponse(
                AuthenticateResponse.newBuilder()
                    .setClientId(clientId)
                    .setSessionId("forge-session-1")
                    .setScreenName(playerName),
            )
            .build()
        ProtoDump.dump(resp, "AuthResp")
        ctx.writeAndFlush(resp)
    }

    private fun handleMatchDoorConnect(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val connectReq = ClientToMatchDoorConnectRequest.parseFrom(msg.payload)
        if (connectReq.matchId.isNotEmpty()) matchId = connectReq.matchId
        log.info("Match Door: connect matchId={}", matchId)

        if (!connectReq.clientToGreMessageBytes.isEmpty) {
            val greMsg = ClientToGREMessage.parseFrom(connectReq.clientToGreMessageBytes)
            if (greMsg.systemSeatId > 0) seatId = greMsg.systemSeatId
            log.info("Match Door: detected seatId={}", seatId)
            myCtx = ctx
            peerContexts.computeIfAbsent(matchId) { ConcurrentHashMap() }[seatId] = this
            processGREMessage(ctx, greMsg)
        }
    }

    private fun handleGREMessage(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        processGREMessage(ctx, ClientToGREMessage.parseFrom(msg.payload))
    }

    private fun processGREMessage(ctx: ChannelHandlerContext, greMsg: ClientToGREMessage) {
        NexusTap.inboundGRE(greMsg.type, greMsg.systemSeatId, greMsg.gameStateId)

        when (greMsg.type) {
            ClientMessageType.ConnectReq_097b -> {
                // Only one bridge per match — first seat to arrive creates it
                val bridge = sharedBridges.computeIfAbsent(matchId) {
                    GameBridge().also { it.start() }
                }
                gameBridge = bridge
                seat1Hand = bridge.getHandGrpIds(1)
                seat2Hand = bridge.getHandGrpIds(2)
                log.info("Match Door: seat {} connected, hands seat1={} seat2={}", seatId, seat1Hand, seat2Hand)
                sendRoomState(ctx)
                sendInitialBundle(ctx)
            }

            ClientMessageType.ChooseStartingPlayerResp_097b -> {
                log.info("Match Door GRE: seat {} chose starting player", seatId)
                sendDealHandAndMulligan(ctx) // seat 2: DealHand + MulliganReq
                val seat1 = peerContexts[matchId]?.get(1)
                if (seat1 != null) {
                    seat1.sendDealHand()
                    seat1.sendMulliganReq()
                } else {
                    log.warn("Match Door: seat 1 peer not found for matchId={}", matchId)
                }
            }

            ClientMessageType.MulliganResp_097b -> {
                val decision = greMsg.mulliganResp.decision
                log.info("Match Door GRE: seat {} mulligan decision={}", seatId, decision)
                val bridge = gameBridge
                if (seatId == 2) {
                    // Familiar responded — ignored (we already sent MulliganReq to seat 1)
                } else if (decision == MulliganOption.AcceptHand) {
                    bridge?.submitKeep(seatId)
                    sendGameStart(ctx)
                } else {
                    mulliganCount++
                    bridge?.submitMull(seatId) // blocks until engine re-deals
                    seat1Hand = bridge?.getHandGrpIds(1) ?: emptyList()
                    sendDealHandAndMulligan(ctx)
                }
            }

            ClientMessageType.SetSettingsReq_097b -> {
                val reqSettings = greMsg.setSettingsReq
                clientSettings = reqSettings.settings
                log.info("Match Door GRE: SetSettingsReq (stops={})", reqSettings.settings.stopsCount)
                sendSettingsResp(ctx)
            }

            ClientMessageType.ConcedeReq_097b -> {
                log.info("Match Door GRE: concede")
                sharedBridges.remove(matchId)?.shutdown()
                sendGameOver(ctx)
            }

            ClientMessageType.PerformActionResp_097b -> {
                if (seatId == 1) {
                    handlePerformAction(ctx, greMsg)
                } else {
                    log.debug("Match Door: ignoring PerformActionResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.DeclareAttackersResp_097b,
            ClientMessageType.SubmitAttackersReq,
            -> {
                if (seatId == 1) {
                    handleDeclareAttackers(ctx, greMsg)
                } else {
                    log.debug("Match Door: ignoring DeclareAttackersResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.DeclareBlockersResp_097b,
            ClientMessageType.SubmitBlockersReq,
            -> {
                if (seatId == 1) {
                    handleDeclareBlockers(ctx, greMsg)
                } else {
                    log.debug("Match Door: ignoring DeclareBlockersResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.SelectTargetsResp_097b -> {
                if (seatId == 1) {
                    handleSelectTargets(ctx, greMsg)
                }
            }

            else -> log.info("Match Door GRE: unhandled type: {}", greMsg.type)
        }
    }

    // --- Template-based senders ---

    private fun sendRoomState(ctx: ChannelHandlerContext) {
        val playerId = clientId.removeSuffix("_Familiar")
        val msg = Templates.roomState(matchId, playerId)
        NexusTap.outboundTemplate("RoomState matchId=$matchId")
        ProtoDump.dump(msg, "RoomState")
        ctx.writeAndFlush(msg)
    }

    private fun sendInitialBundle(ctx: ChannelHandlerContext) {
        gameStateId++
        val deckGrpIds = gameBridge?.getDeckGrpIds(seatId) ?: emptyList()
        val deck = StateMapper.buildDeckMessage(deckGrpIds)
        val (msg, nextMsgId) = Templates.initialBundle(seatId, matchId, msgIdCounter, gameStateId, deck)
        msgIdCounter = nextMsgId
        NexusTap.outboundTemplate("InitialBundle seat=$seatId")
        ProtoDump.dump(msg, "InitialBundle-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /** DealHand only (no MulliganReq) for seat 1. Called cross-connection. */
    fun sendDealHand() {
        val ctx = myCtx ?: return
        gameStateId++
        val (msg, nextMsgId) = Templates.dealHandSeat1(msgIdCounter, gameStateId, seat1Hand)
        msgIdCounter = nextMsgId
        NexusTap.outboundTemplate("DealHand seat=$seatId")
        ProtoDump.dump(msg, "DealHand-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /** MulliganReq sequence for seat 1: GameState(decision=1) + PromptReq + MulliganReq. */
    fun sendMulliganReq() {
        val ctx = myCtx ?: return
        gameStateId++
        val (msg, nextMsgId) = Templates.mulliganReqSeat1(msgIdCounter, gameStateId)
        msgIdCounter = nextMsgId
        NexusTap.outboundTemplate("MulliganReq seat=$seatId")
        ProtoDump.dump(msg, "MulliganReq-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /** DealHand + MulliganReq bundled (for seat 2). */
    private fun sendDealHandAndMulligan(ctx: ChannelHandlerContext) {
        gameStateId++
        val (msg, nextMsgId) = Templates.dealHandMulliganSeat2(msgIdCounter, gameStateId, seat2Hand)
        msgIdCounter = nextMsgId
        NexusTap.outboundTemplate("DealHand+MulliganReq seat=$seatId")
        ProtoDump.dump(msg, "DealHand+MullReq-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    private fun sendSettingsResp(ctx: ChannelHandlerContext) {
        val (msg, nextMsgId) = Templates.settingsResp(seatId, msgIdCounter, gameStateId, clientSettings)
        msgIdCounter = nextMsgId
        ProtoDump.dump(msg, "SettingsResp")
        ctx.writeAndFlush(msg)
    }

    // --- Real game state (post-mulligan) ---

    /**
     * After keep: wait for engine to reach priority, send real game state bundle.
     * Bundle built by [BundleBuilder.gameStart].
     */
    private fun sendGameStart(ctx: ChannelHandlerContext) {
        val bridge = gameBridge ?: return
        log.info("Match Door: waiting for engine to reach priority after keep")
        bridge.awaitPriority()

        val game = bridge.getGame() ?: return
        traceEvent(GameStateCollector.EventType.GAME_START, game, "post-mulligan, entering Main1")

        val result = BundleBuilder.gameStart(game, bridge, matchId, seatId, msgIdCounter, gameStateId)
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId

        sendBundle(ctx, result)

        // Seed state snapshot for subsequent diff computation
        bridge.snapshotState(game)
    }

    /**
     * Build and send current game state + available actions from the live Forge engine.
     * Bundle built by [BundleBuilder.postAction].
     */
    private fun sendRealGameState(ctx: ChannelHandlerContext, bridge: GameBridge) {
        val game = bridge.getGame() ?: run {
            log.warn("Match Door: sendRealGameState but game is null")
            return
        }

        val result = BundleBuilder.postAction(game, bridge, matchId, seatId, msgIdCounter, gameStateId)
        sendBundle(ctx, result)
    }

    /**
     * Send DeclareAttackersReq to the active player at COMBAT_DECLARE_ATTACKERS.
     * Accepts pre-built req to avoid double-building (caller already checked attackersCount > 0).
     */
    private fun sendDeclareAttackersReq(
        ctx: ChannelHandlerContext,
        bridge: GameBridge,
        req: wotc.mtgo.gre.external.messaging.Messages.DeclareAttackersReq? = null,
    ) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.declareAttackersBundle(
            game,
            bridge,
            matchId,
            seatId,
            msgIdCounter,
            gameStateId,
            req,
        )
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId
        inInteractivePrompt = true

        // Remember legal attackers — SubmitAttackersReq doesn't embed selections
        val builtReq = result.messages.firstOrNull { it.hasDeclareAttackersReq() }?.declareAttackersReq
        pendingLegalAttackers = builtReq?.attackersList?.map { it.attackerInstanceId } ?: emptyList()
        log.debug("DeclareAttackersReq: pendingLegalAttackers={}", pendingLegalAttackers)

        NexusTap.outboundTemplate("DeclareAttackersReq seat=$seatId")
        sendBundledGRE(ctx, result.messages)
    }

    /**
     * Send DeclareBlockersReq to the defending player at COMBAT_DECLARE_BLOCKERS.
     */
    private fun sendDeclareBlockersReq(ctx: ChannelHandlerContext, bridge: GameBridge) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.declareBlockersBundle(
            game,
            bridge,
            matchId,
            seatId,
            msgIdCounter,
            gameStateId,
        )
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId
        inInteractivePrompt = true

        NexusTap.outboundTemplate("DeclareBlockersReq seat=$seatId")
        sendBundledGRE(ctx, result.messages)
    }

    /**
     * Handle DeclareAttackersResp: map instanceIds to forge card IDs and submit.
     */
    private fun handleDeclareAttackers(ctx: ChannelHandlerContext, greMsg: ClientToGREMessage) {
        val bridge = gameBridge ?: return
        inInteractivePrompt = false

        val pending = bridge.actionBridge.getPending() ?: run {
            log.warn("Match Door: DeclareAttackersResp but no pending action — recovering")
            sendRealGameState(ctx, bridge)
            return
        }

        // SubmitAttackersReq (type 31) carries no attacker data — the client
        // communicates selections via UIMessages before submitting.
        // Fall back to all legal attackers from the DeclareAttackersReq we sent.
        val resp = greMsg.declareAttackersResp
        val selectedInstanceIds = resp.selectedAttackersList.map { it.attackerInstanceId }
            .ifEmpty { pendingLegalAttackers }

        log.info(
            "Match Door: DeclareAttackers instanceIds={} (fromResp={} pending={})",
            selectedInstanceIds,
            resp.selectedAttackersList.size,
            pendingLegalAttackers.size,
        )
        val attackerCardIds = selectedInstanceIds.mapNotNull { instanceId ->
            val forgeId = bridge.getForgeCardId(instanceId)
            if (forgeId == null) {
                log.warn(
                    "Match Door: instanceId {} not in map (map size={})",
                    instanceId,
                    bridge.getInstanceIdMap().size,
                )
            }
            forgeId
        }
        pendingLegalAttackers = emptyList() // consumed

        log.info("Match Door: DeclareAttackers forgeCardIds={}", attackerCardIds)

        // Submit attackers (empty list = no attack, just pass through combat)
        bridge.actionBridge.submitAction(
            pending.actionId,
            PlayerAction.DeclareAttackers(attackerCardIds, defenderPlayerId = null),
        )

        // Send confirmation
        sendBundledGRE(
            ctx,
            listOf(
                GREToClientMessage.newBuilder()
                    .setType(GREMessageType.SubmitAttackersResp_695e)
                    .setMsgId(msgIdCounter++)
                    .setGameStateId(gameStateId)
                    .addSystemSeatIds(seatId)
                    .setSubmitAttackersResp(SubmitAttackersResp.newBuilder().setResult(ResultCode.Success_a500))
                    .build(),
            ),
        )

        bridge.awaitPriority()
        autoPassAndAdvance(ctx, bridge)
    }

    /**
     * Handle DeclareBlockersResp: map blocker→attacker assignments and submit.
     */
    private fun handleDeclareBlockers(ctx: ChannelHandlerContext, greMsg: ClientToGREMessage) {
        val bridge = gameBridge ?: return
        inInteractivePrompt = false

        val pending = bridge.actionBridge.getPending() ?: run {
            log.warn("Match Door: DeclareBlockersResp but no pending action — recovering")
            sendRealGameState(ctx, bridge)
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

        log.info("Match Door: DeclareBlockersResp blocks={}", blockAssignments)

        bridge.actionBridge.submitAction(
            pending.actionId,
            PlayerAction.DeclareBlockers(blockAssignments),
        )

        // Send confirmation
        sendBundledGRE(
            ctx,
            listOf(
                GREToClientMessage.newBuilder()
                    .setType(GREMessageType.SubmitBlockersResp_695e)
                    .setMsgId(msgIdCounter++)
                    .setGameStateId(gameStateId)
                    .addSystemSeatIds(seatId)
                    .setSubmitBlockersResp(SubmitBlockersResp.newBuilder().setResult(ResultCode.Success_a500))
                    .build(),
            ),
        )

        bridge.awaitPriority()
        autoPassAndAdvance(ctx, bridge)
    }

    /**
     * Handle SelectTargetsResp: map Arena instanceIds back to prompt option indices and submit.
     */
    private fun handleSelectTargets(ctx: ChannelHandlerContext, greMsg: ClientToGREMessage) {
        val bridge = gameBridge ?: return
        inInteractivePrompt = false

        val resp = greMsg.selectTargetsResp
        val pendingPrompt = bridge.promptBridge.getPendingPrompt() ?: run {
            log.warn("Match Door: SelectTargetsResp but no pending prompt")
            return
        }

        // Map Arena instanceIds back to prompt option indices
        val selectedTarget = resp.target
        val selectedIndices = selectedTarget.targetsList.mapNotNull { target ->
            val forgeCardId = bridge.getForgeCardId(target.targetInstanceId)
            if (forgeCardId == null) return@mapNotNull null
            pendingPrompt.request.candidateRefs.indexOfFirst { it.entityId == forgeCardId }
        }.filter { it >= 0 }

        log.info("Match Door: SelectTargetsResp indices={}", selectedIndices)
        bridge.promptBridge.submitResponse(pendingPrompt.promptId, selectedIndices)

        // Send confirmation
        sendBundledGRE(
            ctx,
            listOf(
                GREToClientMessage.newBuilder()
                    .setType(GREMessageType.SubmitTargetsResp_695e)
                    .setMsgId(msgIdCounter++)
                    .setGameStateId(gameStateId)
                    .addSystemSeatIds(seatId)
                    .setSubmitTargetsResp(SubmitTargetsResp.newBuilder().setResult(ResultCode.Success_a500))
                    .build(),
            ),
        )

        // Wait for engine to process and reach next priority
        bridge.awaitPriority()
        autoPassAndAdvance(ctx, bridge)
    }

    /**
     * Send SelectTargetsReq to the active player for targeting prompts.
     */
    private fun sendSelectTargetsReq(
        ctx: ChannelHandlerContext,
        bridge: GameBridge,
        req: SelectTargetsReq,
    ) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.selectTargetsBundle(
            game,
            bridge,
            matchId,
            seatId,
            msgIdCounter,
            gameStateId,
            req,
        )
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId
        inInteractivePrompt = true
        NexusTap.outboundTemplate("SelectTargetsReq seat=$seatId")
        sendBundledGRE(ctx, result.messages)
    }

    /**
     * Auto-pass through phases where the player has no meaningful actions.
     * Detects combat phases and sends appropriate combat prompts.
     * Accumulates Diff messages for phase transitions and sends them all
     * when we finally reach a phase with real actions (or game over).
     */
    private fun autoPassAndAdvance(ctx: ChannelHandlerContext, bridge: GameBridge) {
        repeat(AUTO_PASS_MAX_ITERATIONS) {
            val game = bridge.getGame() ?: return
            if (game.isGameOver) {
                traceEvent(GameStateCollector.EventType.GAME_OVER, game, "game over detected")
                sendGameOver(ctx)
                return
            }

            val human = bridge.getPlayer(seatId)
            val phase = game.phaseHandler.phase
            val isHumanTurn = human != null && game.phaseHandler.playerTurn == human
            val isAiTurn = human != null && !isHumanTurn

            // Combat: DeclareAttackers on human's attacking turn
            // Only send if there are eligible attackers — otherwise auto-skip
            if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS && isHumanTurn) {
                val req = StateMapper.buildDeclareAttackersReq(game, seatId, bridge)
                if (req.attackersCount > 0) {
                    traceEvent(GameStateCollector.EventType.COMBAT_PROMPT, game, "DeclareAttackers attackers=${req.attackersCount}")
                    sendDeclareAttackersReq(ctx, bridge, req)
                    return
                }
                // No legal attackers — fall through to auto-pass
            }

            // AI attacking: send state so client sees creatures with attackState
            if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS && isAiTurn) {
                val combat = game.phaseHandler.combat
                if (combat != null && combat.attackers.isNotEmpty()) {
                    traceEvent(GameStateCollector.EventType.SEND_STATE, game, "AI attacking, ${combat.attackers.size} attackers")
                    Thread.sleep(AI_COMBAT_DELAY_MS)
                    sendRealGameState(ctx, bridge)
                    return
                }
                // No attackers — fall through to auto-pass
            }

            // Combat: DeclareBlockers on AI's attacking turn (human is defending)
            if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS && isAiTurn) {
                // Human is defending — check if there are attackers to block
                val combat = game.phaseHandler.combat
                if (combat != null && combat.attackers.isNotEmpty()) {
                    traceEvent(GameStateCollector.EventType.COMBAT_PROMPT, game, "DeclareBlockers attackers=${combat.attackers.size}")
                    sendDeclareBlockersReq(ctx, bridge)
                    return
                }
            }

            // AI blocking (defending against human attack): send state
            if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS && isHumanTurn) {
                val combat = game.phaseHandler.combat
                if (combat != null && combat.attackers.isNotEmpty()) {
                    traceEvent(GameStateCollector.EventType.SEND_STATE, game, "AI blocking result")
                    Thread.sleep(AI_COMBAT_DELAY_MS)
                    // AI blocks are handled by engine — just show the result
                    sendRealGameState(ctx, bridge)
                    return
                }
            }

            // Combat damage: send state for both players' turns so damage is visible
            if (phase == PhaseType.COMBAT_DAMAGE) {
                traceEvent(GameStateCollector.EventType.SEND_STATE, game, "combat damage")
                Thread.sleep(AI_COMBAT_DELAY_MS)
                sendRealGameState(ctx, bridge)
                return
            }

            // After combat resolves, send state so the client sees
            // life total changes before we continue auto-passing.
            if (phase == PhaseType.COMBAT_END) {
                traceEvent(GameStateCollector.EventType.SEND_STATE, game, "combat end")
                sendRealGameState(ctx, bridge)
                return
            }

            // Check for pending interactive prompt (targeting, sacrifice, etc.)
            val pendingPrompt = bridge.promptBridge.getPendingPrompt()
            if (pendingPrompt != null && pendingPrompt.request.candidateRefs.isNotEmpty()) {
                traceEvent(GameStateCollector.EventType.TARGET_PROMPT, game, "targets=${pendingPrompt.request.candidateRefs.size}")
                val req = StateMapper.buildSelectTargetsReq(pendingPrompt, bridge)
                sendSelectTargetsReq(ctx, bridge, req)
                return
            }

            val actions = StateMapper.buildActions(game, seatId, bridge)
            if (!BundleBuilder.shouldAutoPass(actions)) {
                // Player has real actions (castable spells, lands, etc.) — send state
                val actionSummary = actions.actionsList
                    .groupBy { it.actionType.name.removeSuffix("_add3") }
                    .map { (t, v) -> "$t=${v.size}" }
                    .joinToString(" ")
                traceEvent(GameStateCollector.EventType.SEND_STATE, game, "actions: $actionSummary")
                sendRealGameState(ctx, bridge)
                return
            }
            // shouldAutoPass: only Pass available.
            // Auto-pass silently even when stack is non-empty, so spells resolve
            // to the battlefield in one smooth step instead of requiring repeated
            // client round-trips for redundant priority checks.

            // Skip intermediate Diffs/Edictals — just advance the engine silently
            val pending = bridge.actionBridge.getPending()
            log.debug("autoPass: phase={} turn={} aiTurn={} pending={}", phase, game.phaseHandler.turn, isAiTurn, pending != null)

            if (pending != null) {
                traceEvent(GameStateCollector.EventType.AUTO_PASS, game, "human priority, pass-only")
                // Human has priority (may be on AI's turn to respond) — submit pass
                bridge.actionBridge.submitAction(
                    pending.actionId,
                    PlayerAction.PassPriority,
                )
                bridge.awaitPriority()
            } else if (isAiTurn) {
                traceEvent(GameStateCollector.EventType.AI_TURN_WAIT, game, "waiting for AI")
                // Pacing: brief delay on AI turns so MTGA can animate phase transitions
                val delay = if (phase.name.startsWith("COMBAT")) AI_COMBAT_DELAY_MS else AI_PHASE_DELAY_MS
                Thread.sleep(delay)

                // AI turn, no pending action for human — wait for engine to cycle back
                val reachedPriority = bridge.awaitPriorityWithTimeout(GameBridge.AI_TURN_WAIT_MS)
                if (!reachedPriority) {
                    val g = bridge.getGame()
                    if (g != null && g.isGameOver) {
                        traceEvent(GameStateCollector.EventType.GAME_OVER, game, "game over during AI wait")
                        sendGameOver(ctx)
                        return
                    }
                    traceEvent(GameStateCollector.EventType.AI_TURN_TIMEOUT, game, "AI turn timed out")
                    log.warn("autoPass: AI turn timed out, sending current state")
                    sendRealGameState(ctx, bridge)
                    return
                }
            } else {
                // Human turn but no pending action yet — wait for engine
                traceEvent(GameStateCollector.EventType.PRIORITY_GRANT, game, "waiting for engine")
                log.warn("autoPass: no pending action, waiting for priority")
                bridge.awaitPriority()
            }
        }

        // Guardrail: hit max iterations, send whatever we have
        log.warn("autoPassAndAdvance: hit max iterations ({})", AUTO_PASS_MAX_ITERATIONS)
        sendRealGameState(ctx, bridge)
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
        GameStateCollector.recordEvent(gameStateId, type, phase, turn, detail, priority, stackDepth)
    }

    /**
     * Handle a client action (land play, spell cast, pass) and advance the engine.
     */
    private fun handlePerformAction(ctx: ChannelHandlerContext, greMsg: ClientToGREMessage) {
        val bridge = gameBridge ?: return
        val pending = bridge.actionBridge.getPending() ?: run {
            log.warn("Match Door: PerformActionResp but no pending action — recovering")
            autoPassAndAdvance(ctx, bridge)
            return
        }

        val action = greMsg.performActionResp.actionsList.firstOrNull()
        if (action == null) {
            log.warn("Match Door: PerformActionResp with no actions")
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
                log.info("Match Door: unhandled action type {}, passing", action.actionType)
                bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            }
        }

        // Wait for engine to reach next priority stop
        bridge.awaitPriority()

        // After a cast, the spell is on the stack. Send state with Pass
        // so the client shows the spell on stack. Client responds with Pass,
        // which triggers the resolution path below.
        if (isCast) {
            val g = bridge.getGame()
            if (g != null && !g.stack.isEmpty) {
                sendRealGameState(ctx, bridge)
                return
            }
        }

        // After stack resolution (client passed with spell on stack, engine
        // resolved it), send intermediate state so the client sees the creature
        // move from stack to battlefield with ResolutionStart/Complete annotations
        // BEFORE we auto-pass through remaining phases.
        if (stackWasNonEmpty) {
            val g = bridge.getGame()
            if (g != null && g.stack.isEmpty) {
                log.info("Match Door: stack resolved, sending intermediate resolution state")
                sendRealGameState(ctx, bridge)
                return
            }
        }

        // Auto-pass through phases where only Pass is available
        autoPassAndAdvance(ctx, bridge)
    }

    private fun sendGameOver(ctx: ChannelHandlerContext) {
        gameStateId++
        sendGRE(ctx, GREMessageType.GameStateMessage_695e) {
            it.gameStateMessage = GameStateMessage.newBuilder()
                .setType(GameStateType.Diff).setGameStateId(gameStateId)
                .setGameInfo(
                    GameInfo.newBuilder()
                        .setStage(GameStage.GameOver)
                        .setMatchState(MatchState.MatchComplete)
                        .setMulliganType(MulliganType.London),
                )
                .addPlayers(
                    PlayerInfo.newBuilder()
                        .setSystemSeatNumber(1).setStatus(PlayerStatus.Removed_a1c6).setTeamId(1),
                )
                .setUpdate(GameStateUpdate.Send)
                .build()
        }
    }

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

    /** Apply a [BundleBuilder.BundleResult]: update counters, tap-log, and send. */
    private fun sendBundle(ctx: ChannelHandlerContext, result: BundleBuilder.BundleResult) {
        msgIdCounter = result.nextMsgId
        gameStateId = result.nextGsId
        for (gre in result.messages) {
            if (gre.hasGameStateMessage()) NexusTap.outboundState(gre.gameStateMessage)
            if (gre.hasActionsAvailableReq()) NexusTap.outboundActions(gre.actionsAvailableReq)
        }
        sendBundledGRE(ctx, result.messages)
    }

    /** Send multiple GRE messages bundled in one MatchServiceToClientMessage. */
    private fun sendBundledGRE(ctx: ChannelHandlerContext, messages: List<GREToClientMessage>) {
        ArenaDebugCollector.recordOutbound(messages, seatId)
        GameStateCollector.collectOutbound(messages)
        val event = GreToClientEvent.newBuilder()
        messages.forEach { event.addGreToClientMessages(it) }
        val msg = MatchServiceToClientMessage.newBuilder()
            .setGreToClientEvent(event.build())
            .build()
        ProtoDump.dump(msg)
        ctx.writeAndFlush(msg)
    }

    /** Send a single GRE message (legacy helper). */
    private fun sendGRE(
        ctx: ChannelHandlerContext,
        type: GREMessageType,
        configure: (GREToClientMessage.Builder) -> Unit,
    ) {
        sendBundledGRE(ctx, listOf(makeGRE(type, gameStateId, configure)))
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Match Door error: {}", cause.message, cause)
        gameBridge?.shutdown()
        ctx.close()
    }
}
