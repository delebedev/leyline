package forge.nexus.server

import forge.game.Game
import forge.game.phase.PhaseType
import forge.nexus.debug.GameStateCollector
import forge.nexus.debug.NexusTap
import forge.nexus.game.BundleBuilder
import forge.nexus.game.GameBridge
import forge.web.game.PlayerAction
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles combat-related client messages and auto-pass combat phase detection.
 *
 * Extracted from [MatchSession] for independent testability.
 * Uses [SessionOps] for counter management, message sending, and tracing.
 */
class CombatHandler(private val ops: SessionOps) {
    private val log = LoggerFactory.getLogger(CombatHandler::class.java)

    /** Legal attacker instanceIds from the last DeclareAttackersReq we sent.
     *  Guarded by MatchSession.sessionLock — all reads/writes occur within synchronized entry points. */
    var pendingLegalAttackers: List<Int> = emptyList()
        private set

    /** Last declared attacker instanceIds — updated by iterative DeclareAttackersResp
     *  (creature toggles / "Attack All"), defaults to [pendingLegalAttackers] when we
     *  send DeclareAttackersReq with pre-selected attackers. Used by SubmitAttackersReq
     *  (the "Done" button, which carries no payload). */
    private var lastDeclaredAttackerIds: List<Int> = emptyList()

    /** Last declared blocker assignments: blockerInstanceId → attackerInstanceId.
     *  Updated by iterative DeclareBlockersResp, consumed by SubmitBlockersReq. */
    private val lastDeclaredBlockAssignments = mutableMapOf<Int, Int>()

    /** True while a DeclareBlockersReq is outstanding (sent but not yet responded to).
     *  Prevents [checkCombatPhase] from re-sending during the priority window after
     *  blockers are submitted. Cleared in [onDeclareBlockers]. */
    var pendingBlockersSent: Boolean = false
        private set

    /** Loop signal from combat phase checks. */
    enum class Signal { STOP, SEND_STATE, CONTINUE }

    /**
     * Handle DeclareAttackersResp or SubmitAttackersReq from the client.
     *
     * Arena uses a two-phase combat protocol:
     * - **DeclareAttackersResp** (type=30): iterative update — client sends current
     *   attacker selection on each creature toggle or "Attack All" click.
     *   If `auto_declare=true`, selects all [pendingLegalAttackers].
     * - **SubmitAttackersReq** (type=31): finalize — client sends empty "Done" signal.
     *   Server uses [lastDeclaredAttackerIds] (the last known selection).
     */
    fun onDeclareAttackers(
        greMsg: ClientToGREMessage,
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val isSubmit = greMsg.type == ClientMessageType.SubmitAttackersReq

        if (!isSubmit) {
            // Iterative update: DeclareAttackersResp — update tracked selection
            val resp = greMsg.declareAttackersResp
            if (resp.autoDeclare) {
                // "Attack All" — select all legal attackers
                lastDeclaredAttackerIds = pendingLegalAttackers.toList()
                log.info("CombatHandler: Attack All — selected all {} pending attackers", lastDeclaredAttackerIds.size)
            } else {
                lastDeclaredAttackerIds = resp.selectedAttackersList.map { it.attackerInstanceId }
                log.info("CombatHandler: iterative update — selected {}", lastDeclaredAttackerIds)
            }
            // TODO: send updated DeclareAttackersReq back (refreshed warnings/canSubmit).
            // For now, the initial req is sufficient — client doesn't require re-sends.
            return
        }

        // SubmitAttackersReq: finalize — use last known selection
        val pending = bridge.actionBridge.getPending() ?: run {
            log.warn("CombatHandler: SubmitAttackersReq but no pending action — recovering")
            ops.sendRealGameState(bridge)
            return
        }

        val selectedInstanceIds = lastDeclaredAttackerIds
        log.info(
            "CombatHandler: SubmitAttackers instanceIds={} (pending={})",
            selectedInstanceIds,
            pendingLegalAttackers.size,
        )

        val attackerCardIds = selectedInstanceIds.mapNotNull { instanceId ->
            val forgeId = bridge.getForgeCardId(instanceId)
            if (forgeId == null) {
                log.warn("CombatHandler: instanceId {} not in map (map size={})", instanceId, bridge.getInstanceIdMap().size)
            }
            forgeId
        }
        pendingLegalAttackers = emptyList()
        lastDeclaredAttackerIds = emptyList()

        log.info("CombatHandler: SubmitAttackers forgeCardIds={}", attackerCardIds)

        ops.sendBundledGRE(
            listOf(
                ops.makeGRE(GREMessageType.SubmitAttackersResp_695e, ops.gameStateId, ops.msgIdCounter++) {
                    it.submitAttackersResp = SubmitAttackersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        // Resolve the defending player: the opponent of the active (attacking) player.
        val game = bridge.getGame()
        val humanPlayer = bridge.getPlayer(ops.seatId)
        val defenderPlayerId = game?.players
            ?.firstOrNull { it != humanPlayer }?.id

        // Seed BEFORE submit — submitAction unblocks the game thread immediately
        bridge.playback?.seedCounters(ops.msgIdCounter, ops.gameStateId)
        bridge.actionBridge.submitAction(
            pending.actionId,
            PlayerAction.DeclareAttackers(attackerCardIds, defenderPlayerId = defenderPlayerId),
        )
        bridge.awaitPriority()
        autoPass(bridge)
    }

    /**
     * Handle DeclareBlockersResp or SubmitBlockersReq from the client.
     *
     * Same two-phase protocol as attackers:
     * - **DeclareBlockersResp** (type=32): iterative update with blocker assignments
     * - **SubmitBlockersReq** (type=33): finalize — uses [lastDeclaredBlockAssignments]
     */
    fun onDeclareBlockers(
        greMsg: ClientToGREMessage,
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val isSubmit = greMsg.type == ClientMessageType.SubmitBlockersReq

        if (!isSubmit) {
            // Iterative update: save blocker assignments
            val resp = greMsg.declareBlockersResp
            lastDeclaredBlockAssignments.clear()
            for (blocker in resp.selectedBlockersList) {
                lastDeclaredBlockAssignments[blocker.blockerInstanceId] =
                    blocker.selectedAttackerInstanceIdsList.firstOrNull() ?: continue
            }
            log.info("CombatHandler: blocker update — assignments={}", lastDeclaredBlockAssignments)
            return
        }

        // SubmitBlockersReq: finalize
        val pending = bridge.actionBridge.getPending() ?: run {
            log.warn("CombatHandler: SubmitBlockersReq but no pending action — recovering")
            ops.sendRealGameState(bridge)
            return
        }

        val blockAssignments = mutableMapOf<Int, Int>()
        for ((blockerIid, attackerIid) in lastDeclaredBlockAssignments) {
            val blockerCardId = bridge.getForgeCardId(blockerIid) ?: continue
            val attackerCardId = bridge.getForgeCardId(attackerIid) ?: continue
            blockAssignments[blockerCardId] = attackerCardId
        }
        lastDeclaredBlockAssignments.clear()

        log.info("CombatHandler: SubmitBlockers blocks={}", blockAssignments)
        // Don't clear pendingBlockersSent here — a priority window may follow
        // in DECLARE_BLOCKERS step before moving to damage. Cleared when a new
        // combat starts (COMBAT_DECLARE_ATTACKERS).

        ops.sendBundledGRE(
            listOf(
                ops.makeGRE(GREMessageType.SubmitBlockersResp_695e, ops.gameStateId, ops.msgIdCounter++) {
                    it.submitBlockersResp = SubmitBlockersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        // Seed BEFORE submit — submitAction unblocks the game thread immediately
        bridge.playback?.seedCounters(ops.msgIdCounter, ops.gameStateId)
        bridge.actionBridge.submitAction(
            pending.actionId,
            PlayerAction.DeclareBlockers(blockAssignments),
        )
        bridge.awaitPriority()
        autoPass(bridge)
    }

    /**
     * Check combat phases and send appropriate prompts or state.
     * Called from the auto-pass loop.
     */
    fun checkCombatPhase(
        bridge: GameBridge,
        game: Game,
        phase: PhaseType?,
        isHumanTurn: Boolean,
        isAiTurn: Boolean,
    ): Signal {
        val combat = game.phaseHandler.combat

        when (phase) {
            PhaseType.COMBAT_DECLARE_ATTACKERS -> {
                // New combat round — reset blocker-sent flag from previous combat.
                pendingBlockersSent = false
                if (isHumanTurn) {
                    val req = BundleBuilder.buildDeclareAttackersReq(game, ops.seatId, bridge)
                    if (req.attackersCount > 0) {
                        ops.traceEvent(GameStateCollector.EventType.COMBAT_PROMPT, game, "DeclareAttackers attackers=${req.attackersCount}")
                        sendDeclareAttackersReq(bridge, req)
                        return Signal.STOP
                    }
                } else if (isAiTurn && combat != null && combat.attackers.isNotEmpty()) {
                    ops.traceEvent(GameStateCollector.EventType.SEND_STATE, game, "AI attacking, ${combat.attackers.size} attackers")
                    ops.paceDelay(2)
                    return Signal.SEND_STATE
                }
            }
            PhaseType.COMBAT_DECLARE_BLOCKERS -> {
                if (isAiTurn && combat != null && combat.attackers.isNotEmpty() && !pendingBlockersSent) {
                    // Wait for engine to reach declareBlockers() on the human player's
                    // WebPlayerController — it creates a pending action via awaitAction().
                    // Without this, we'd send DeclareBlockersReq before the engine is
                    // ready to accept the response, causing "no pending action" errors.
                    bridge.awaitPriority()
                    // Sync counters — the engine thread may have captured AI actions
                    // (via NexusGamePlayback) between the last drainPlayback and now,
                    // advancing the snapshot's gsId past ops.gameStateId. Without this,
                    // declareBlockersBundle reads a snapshot with gsId > ops.gameStateId
                    // and produces a self-referential prevGameStateId.
                    drainAndSyncPlayback(bridge)
                    ops.traceEvent(GameStateCollector.EventType.COMBAT_PROMPT, game, "DeclareBlockers attackers=${combat.attackers.size}")
                    sendDeclareBlockersReq(bridge)
                    return Signal.STOP
                } else if (isHumanTurn && combat != null && combat.attackers.isNotEmpty()) {
                    ops.traceEvent(GameStateCollector.EventType.SEND_STATE, game, "AI blocking result")
                    ops.paceDelay(2)
                    return Signal.SEND_STATE
                }
            }
            PhaseType.COMBAT_DAMAGE -> {
                ops.traceEvent(GameStateCollector.EventType.SEND_STATE, game, "combat damage")
                ops.paceDelay(2)
                return Signal.SEND_STATE
            }
            PhaseType.COMBAT_END -> {
                if (combat != null && combat.attackers.isNotEmpty()) {
                    ops.traceEvent(GameStateCollector.EventType.SEND_STATE, game, "combat end")
                    return Signal.SEND_STATE
                }
            }
            else -> {}
        }
        return Signal.CONTINUE
    }

    // --- Sending helpers ---

    private fun sendDeclareAttackersReq(
        bridge: GameBridge,
        req: DeclareAttackersReq? = null,
    ) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.declareAttackersBundle(game, bridge, ops.matchId, ops.seatId, ops.msgIdCounter, ops.gameStateId, req)
        ops.msgIdCounter = result.nextMsgId
        ops.gameStateId = result.nextGsId

        val builtReq = result.messages.firstOrNull { it.hasDeclareAttackersReq() }?.declareAttackersReq
        pendingLegalAttackers = builtReq?.attackersList?.map { it.attackerInstanceId } ?: emptyList()
        // Pre-select all legal attackers as the default declaration — our DeclareAttackersReq
        // sets selectedDamageRecipient on each attacker, so the client shows them as declared.
        // If the user clicks "Done" (SubmitAttackersReq) without toggling, this is the selection.
        lastDeclaredAttackerIds = pendingLegalAttackers.toList()
        log.debug("DeclareAttackersReq: pendingLegalAttackers={} lastDeclared={}", pendingLegalAttackers, lastDeclaredAttackerIds)

        NexusTap.outboundTemplate("DeclareAttackersReq seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
        bridge.playback?.seedCounters(ops.msgIdCounter, ops.gameStateId)
    }

    private fun sendDeclareBlockersReq(bridge: GameBridge) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.declareBlockersBundle(game, bridge, ops.matchId, ops.seatId, ops.msgIdCounter, ops.gameStateId)
        ops.msgIdCounter = result.nextMsgId
        ops.gameStateId = result.nextGsId

        pendingBlockersSent = true
        NexusTap.outboundTemplate("DeclareBlockersReq seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
        bridge.playback?.seedCounters(ops.msgIdCounter, ops.gameStateId)
    }

    /**
     * Drain any pending playback messages and sync ops counters.
     *
     * The engine thread may have captured AI actions (via [NexusGamePlayback])
     * between the last [AutoPassEngine.drainPlayback] and now, queuing messages
     * with new gsIds. If we only sync counters but don't send these messages,
     * the next bundle references a prevGsId the client never received.
     */
    /**
     * Drain any pending playback messages and sync ops counters.
     *
     * The engine thread may have captured AI actions (via [NexusGamePlayback])
     * between the last [AutoPassEngine] drain and now, queuing messages with
     * new gsIds. If we only sync counters but don't send these messages,
     * the next bundle references a prevGsId the client never received.
     */
    private fun drainAndSyncPlayback(bridge: GameBridge) {
        val playback = bridge.playback ?: return
        if (playback.hasPendingMessages()) {
            val batches = playback.drainQueue()
            for (batch in batches) {
                ops.sendBundledGRE(batch) // sendBundledGRE updates lastSentTurnInfo
            }
        }
        val (nextMsg, nextGs) = playback.getCounters()
        if (nextMsg > ops.msgIdCounter) {
            log.debug("drainAndSyncPlayback: msgId {} → {}", ops.msgIdCounter, nextMsg)
            ops.msgIdCounter = nextMsg
        }
        if (nextGs > ops.gameStateId) {
            log.debug("drainAndSyncPlayback: gsId {} → {}", ops.gameStateId, nextGs)
            ops.gameStateId = nextGs
        }
    }
}
