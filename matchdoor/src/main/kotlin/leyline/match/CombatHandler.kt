package leyline.match

import forge.game.Game
import forge.game.phase.PhaseType
import leyline.bridge.ForgeCardId
import leyline.bridge.ForgePlayerId
import leyline.bridge.InstanceId
import leyline.bridge.PlayerAction
import leyline.bridge.SeatId
import leyline.bridge.Target
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import kotlin.collections.iterator

/**
 * Handles combat-related client messages and auto-pass combat phase detection.
 *
 * Extracted from [MatchSession] for independent testability.
 * Uses [SessionOps] for message sending and tracing. Protocol sequencing
 * uses the shared [MessageCounter][leyline.game.MessageCounter] via
 * `ops.counter` — no seeding or syncing needed.
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

    /**
     * Loop signal from combat phase checks.
     *
     * - [STOP] — sent interactive prompt (DeclareAttackersReq/DeclareBlockersReq), waiting for client response.
     * - [SEND_STATE] — informational: show the board, client has priority. Bypasses checkHumanActions.
     * - [CONTINUE] — nothing to do, fall through to action check.
     *
     * **AI turn handling:** AutoPassEngine downgrades SEND_STATE to fall-through on AI turns.
     * Real server never offers actions during AI combat phases (actionsCount=0 in GSMs).
     * Offering Cast actions during AI combat makes the client stuck (no Pass button → 120s timeout).
     *
     * **Human turn guard:** SEND_STATE bypasses checkHumanActions. If the human has only Pass actions
     * when SEND_STATE fires, AutoPassEngine downgrades to fall-through to avoid a stuck UI.
     */
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

        // Reject stale Submit messages — the client may send on either channel (race).
        // The echo-back advances the counter, so a Submit from the slower channel
        // arrives with an outdated gsId. Only applies to Submit (type-only signal);
        // iterative DeclareAttackersResp always carries fresh data.
        if (isSubmit) {
            val clientGsId = greMsg.gameStateId
            if (clientGsId != 0 && clientGsId < ops.counter.currentGsId()) {
                log.debug("CombatHandler: stale SubmitAttackersReq gsId={} (current={}), ignoring", clientGsId, ops.counter.currentGsId())
                return
            }
        }

        if (!isSubmit) {
            // Iterative update: DeclareAttackersResp — update tracked selection
            val resp = greMsg.declareAttackersResp
            log.debug(
                "CombatHandler: DeclareAttackersResp autoDeclare={} selectedCount={} selectedIds={}",
                resp.autoDeclare,
                resp.selectedAttackersCount,
                resp.selectedAttackersList.map { it.attackerInstanceId },
            )
            if (resp.autoDeclare) {
                // "Attack All" — select all legal attackers
                lastDeclaredAttackerIds = pendingLegalAttackers.toList()
                log.info("CombatHandler: Attack All — selected all {} pending attackers", lastDeclaredAttackerIds.size)
            } else {
                // XOR toggle: selectedAttackers contains the toggled creature(s).
                // If already committed → deselect. If not committed → select.
                // Conformance: recording 2026-03-14_17-28-50, idx 160/172/184.
                val toggled = resp.selectedAttackersList.map { it.attackerInstanceId }
                val current = lastDeclaredAttackerIds.toMutableSet()
                for (id in toggled) {
                    if (id in current) current.remove(id) else current.add(id)
                }
                lastDeclaredAttackerIds = current.toList()
                log.info("CombatHandler: toggle {} → committed {}", toggled, lastDeclaredAttackerIds)
            }
            // Echo back GSM with creature object (no combat state) + DeclareAttackersReq.
            // Real server echo carries NO attackState — confirmed across 4 proxy recordings.
            sendAttackerEchoBack(bridge)
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
            val forgeId = bridge.getForgeCardId(InstanceId(instanceId))
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
                ops.makeGRE(GREMessageType.SubmitAttackersResp_695e, ops.counter.currentGsId(), ops.counter.nextMsgId()) {
                    it.submitAttackersResp = SubmitAttackersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        // Resolve the defending player: the opponent of the active (attacking) player.
        val game = bridge.getGame()
        val humanPlayer = bridge.getPlayer(SeatId(ops.seatId))
        val defenderPlayerId = game?.players
            ?.firstOrNull { it != humanPlayer }?.id

        bridge.actionBridge.submitAction(
            pending.actionId,
            PlayerAction.DeclareAttackers(attackerCardIds, defender = defenderPlayerId?.let { Target.Player(ForgePlayerId(it)) }),
        )
        bridge.awaitPriority()
        autoPass(bridge)
    }

    /**
     * Handle CancelActionReq during attack declaration — pass combat with no attackers.
     *
     * The client sends CancelActionReq when the player clicks "Cancel" during the
     * declare attackers phase. This submits an empty attacker list to the engine,
     * which passes combat entirely (no attacks, skip to post-combat main).
     */
    fun onCancelAttackers(
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val pending = bridge.actionBridge.getPending() ?: run {
            log.warn("CombatHandler: CancelAttackers but no pending action — recovering")
            ops.sendRealGameState(bridge)
            return
        }

        log.info("CombatHandler: CancelAttackers — submitting empty attackers to pass combat")

        pendingLegalAttackers = emptyList()
        lastDeclaredAttackerIds = emptyList()

        ops.sendBundledGRE(
            listOf(
                ops.makeGRE(GREMessageType.SubmitAttackersResp_695e, ops.counter.currentGsId(), ops.counter.nextMsgId()) {
                    it.submitAttackersResp = SubmitAttackersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        val game = bridge.getGame()
        val humanPlayer = bridge.getPlayer(SeatId(ops.seatId))
        val defenderPlayerId = game?.players
            ?.firstOrNull { it != humanPlayer }?.id

        bridge.actionBridge.submitAction(
            pending.actionId,
            PlayerAction.DeclareAttackers(emptyList(), defender = defenderPlayerId?.let { Target.Player(ForgePlayerId(it)) }),
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

        // Reject stale Submit — same pattern as attackers (see onDeclareAttackers).
        if (isSubmit) {
            val clientGsId = greMsg.gameStateId
            if (clientGsId != 0 && clientGsId < ops.counter.currentGsId()) {
                log.debug("CombatHandler: stale SubmitBlockersReq gsId={} (current={}), ignoring", clientGsId, ops.counter.currentGsId())
                return
            }
        }

        if (!isSubmit) {
            // Iterative update: save blocker assignments, then echo back
            // DeclareBlockersReq so the client confirms and enables Submit.
            // Same echo pattern as DeclareAttackersResp → DeclareAttackersReq.
            val resp = greMsg.declareBlockersResp
            lastDeclaredBlockAssignments.clear()
            for (blocker in resp.selectedBlockersList) {
                lastDeclaredBlockAssignments[blocker.blockerInstanceId] =
                    blocker.selectedAttackerInstanceIdsList.firstOrNull() ?: continue
            }
            log.info("CombatHandler: blocker update — assignments={}, echoing DeclareBlockersReq", lastDeclaredBlockAssignments)
            sendBlockerEchoBack(bridge)
            return
        }

        // SubmitBlockersReq: finalize
        val pending = bridge.actionBridge.getPending() ?: run {
            log.warn("CombatHandler: SubmitBlockersReq but no pending action — recovering")
            ops.sendRealGameState(bridge)
            return
        }

        val blockAssignments = mutableMapOf<ForgeCardId, ForgeCardId>()
        for ((blockerIid, attackerIid) in lastDeclaredBlockAssignments) {
            val blockerCardId = bridge.getForgeCardId(InstanceId(blockerIid)) ?: continue
            val attackerCardId = bridge.getForgeCardId(InstanceId(attackerIid)) ?: continue
            blockAssignments[blockerCardId] = attackerCardId
        }
        lastDeclaredBlockAssignments.clear()

        log.info("CombatHandler: SubmitBlockers blocks={}", blockAssignments)
        // Don't clear pendingBlockersSent here — a priority window may follow
        // in DECLARE_BLOCKERS step before moving to damage. Cleared when a new
        // combat starts (COMBAT_DECLARE_ATTACKERS).

        ops.sendBundledGRE(
            listOf(
                ops.makeGRE(GREMessageType.SubmitBlockersResp_695e, ops.counter.currentGsId(), ops.counter.nextMsgId()) {
                    it.submitBlockersResp = SubmitBlockersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

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
                        ops.traceEvent(MatchEventType.COMBAT_PROMPT, game, "DeclareAttackers attackers=${req.attackersCount}")
                        sendDeclareAttackersReq(bridge, req)
                        return Signal.STOP
                    }
                } else if (isAiTurn && combat != null && combat.attackers.isNotEmpty()) {
                    ops.traceEvent(MatchEventType.SEND_STATE, game, "AI attacking, ${combat.attackers.size} attackers")
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
                    // Drain any pending playback messages — the engine thread may have
                    // captured AI actions between the last drain and now.
                    drainPendingPlayback(bridge)
                    ops.traceEvent(MatchEventType.COMBAT_PROMPT, game, "DeclareBlockers attackers=${combat.attackers.size}")
                    sendDeclareBlockersReq(bridge)
                    return Signal.STOP
                } else if (isHumanTurn && combat != null && combat.attackers.isNotEmpty()) {
                    ops.traceEvent(MatchEventType.SEND_STATE, game, "AI blocking result")
                    ops.paceDelay(2)
                    return Signal.SEND_STATE
                }
            }
            PhaseType.COMBAT_DAMAGE -> {
                if (combat != null && combat.attackers.isNotEmpty()) {
                    ops.traceEvent(MatchEventType.SEND_STATE, game, "combat damage")
                    ops.paceDelay(2)
                    return Signal.SEND_STATE
                }
            }
            PhaseType.COMBAT_END -> {
                if (combat != null && combat.attackers.isNotEmpty()) {
                    ops.traceEvent(MatchEventType.SEND_STATE, game, "combat end")
                    return Signal.SEND_STATE
                }
            }
            else -> {}
        }
        return Signal.CONTINUE
    }

    // --- Sending helpers ---

    /**
     * Echo-back for iterative attacker toggle: sends GSM with provisional
     * combat state on toggled creatures + fresh DeclareAttackersReq.
     */
    private fun sendAttackerEchoBack(bridge: GameBridge) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.echoAttackersBundle(
            game,
            bridge,
            ops.seatId,
            ops.counter,
            selectedAttackerIds = lastDeclaredAttackerIds,
            allLegalAttackerIds = pendingLegalAttackers,
        )
        Tap.outboundTemplate("DeclareAttackersReq echo seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
    }

    /**
     * @param resetSelection true on initial send (no attackers selected yet),
     *                       false on echo-back (preserve current [lastDeclaredAttackerIds]).
     */
    private fun sendDeclareAttackersReq(
        bridge: GameBridge,
        req: DeclareAttackersReq? = null,
        resetSelection: Boolean = true,
    ) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.declareAttackersBundle(game, bridge, ops.matchId, ops.seatId, ops.counter, req)

        val builtReq = result.messages.firstOrNull { it.hasDeclareAttackersReq() }?.declareAttackersReq
        pendingLegalAttackers = builtReq?.attackersList?.map { it.attackerInstanceId } ?: emptyList()
        if (resetSelection) {
            // Initial send — no attackers selected yet. Client clicks populate lastDeclaredAttackerIds.
            lastDeclaredAttackerIds = emptyList()
        }
        log.debug("DeclareAttackersReq: pendingLegalAttackers={} lastDeclared={}", pendingLegalAttackers, lastDeclaredAttackerIds)

        Tap.outboundTemplate("DeclareAttackersReq seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
    }

    /**
     * Echo-back for iterative blocker toggle: sends GSM with provisional
     * block state on toggled creatures + fresh DeclareBlockersReq.
     */
    private fun sendBlockerEchoBack(bridge: GameBridge) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.echoBlockersBundle(
            game,
            bridge,
            ops.seatId,
            ops.counter,
            blockAssignments = lastDeclaredBlockAssignments.toMap(),
        )
        Tap.outboundTemplate("DeclareBlockersReq echo seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
    }

    private fun sendDeclareBlockersReq(bridge: GameBridge) {
        val game = bridge.getGame() ?: return
        val result = BundleBuilder.declareBlockersBundle(game, bridge, ops.matchId, ops.seatId, ops.counter)

        pendingBlockersSent = true
        Tap.outboundTemplate("DeclareBlockersReq seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
    }

    /**
     * Drain any pending playback messages.
     *
     * The engine thread may have captured AI actions (via [GamePlayback])
     * between the last drain and now, queuing messages with new gsIds.
     * With the shared MessageCounter, no counter syncing is needed — just
     * drain and send.
     */
    private fun drainPendingPlayback(bridge: GameBridge) {
        val playback = bridge.playbacks[SeatId(ops.seatId)] ?: return
        if (playback.hasPendingMessages()) {
            val batches = playback.drainQueue()
            for (batch in batches) {
                ops.sendBundledGRE(batch) // sendBundledGRE updates lastSentTurnInfo
            }
        }
    }
}
