package leyline.match

import forge.game.Game
import forge.game.phase.PhaseType
import forge.game.player.Player
import leyline.bridge.ForgeCardId
import leyline.bridge.ForgePlayerId
import leyline.bridge.InstanceId
import leyline.bridge.PlayerAction
import leyline.bridge.Target
import leyline.bridge.WebPlayerController
import leyline.bridge.findCard
import leyline.game.GameBridge
import leyline.game.RequestBuilder
import leyline.game.mapper.PromptIds
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
open class CombatHandler(private val ops: SessionOps) {
    companion object {
        private const val ASSIGN_DAMAGE_PROMPT_ID = PromptIds.ASSIGN_DAMAGE
    }

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
        val seatBridge = bridge.seat(ops.seatId.value)
        val pending = seatBridge.action.getPending() ?: run {
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
        val humanPlayer = bridge.getPlayer(ops.seatId)
        val defenderPlayerId = game?.players
            ?.firstOrNull { it != humanPlayer }?.id

        seatBridge.action.submitAction(
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
        val seatBridge = bridge.seat(ops.seatId.value)
        val pending = seatBridge.action.getPending() ?: run {
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
        val humanPlayer = bridge.getPlayer(ops.seatId)
        val defenderPlayerId = game?.players
            ?.firstOrNull { it != humanPlayer }?.id

        seatBridge.action.submitAction(
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
        val seatBridge = bridge.seat(ops.seatId.value)
        val pending = seatBridge.action.getPending() ?: run {
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

        seatBridge.action.submitAction(
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
    @Suppress("ReturnCount")
    open fun checkCombatPhase(
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
                    // Don't re-prompt if attackers already declared (post-declaration priority window)
                    if (combat != null && combat.attackers.isNotEmpty()) {
                        return Signal.CONTINUE
                    }
                    val req = ops.bundleBuilder!!.buildDeclareAttackersReq(game)
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
                    val skipBlockers = sendDeclareBlockersReq(bridge)
                    if (skipBlockers) {
                        // Zero legal blockers — submit empty declaration and advance
                        val seatBridge = bridge.seat(ops.seatId.value)
                        val pending = seatBridge.action.getPending()
                        if (pending != null) {
                            seatBridge.action.submitAction(pending.actionId, PlayerAction.DeclareBlockers(emptyMap()))
                            bridge.awaitPriority()
                        }
                        return Signal.SEND_STATE
                    }
                    return Signal.STOP
                } else if (isHumanTurn && combat != null && combat.attackers.isNotEmpty()) {
                    ops.traceEvent(MatchEventType.SEND_STATE, game, "AI blocking result")
                    ops.paceDelay(2)
                    return Signal.SEND_STATE
                }
            }
            PhaseType.COMBAT_DAMAGE -> {
                ops.traceEvent(MatchEventType.SEND_STATE, game, "combat damage")
                ops.paceDelay(2)
                return Signal.SEND_STATE
            }
            PhaseType.COMBAT_END -> {
                // Same: combat may be cleared by the time we check
                ops.traceEvent(MatchEventType.SEND_STATE, game, "combat end")
                return Signal.SEND_STATE
            }
            else -> {}
        }
        return Signal.CONTINUE
    }

    // --- Damage assignment ---

    /**
     * Check if the engine is blocked waiting for manual damage assignment.
     *
     * Called from [AutoPassEngine.autoPassAndAdvance] between combat phase
     * handling and interactive prompt checks. Uses the dedicated
     * [DamageAssignmentPrompt] future on [WebPlayerController] — NOT the
     * [GameActionBridge] — so the auto-pass loop cannot interfere.
     *
     * @return true if AssignDamageReq was sent (caller should exit the loop)
     */
    fun checkPendingDamageAssignment(bridge: GameBridge): Boolean {
        val wpc = bridge.humanController ?: return false
        val prompt = wpc.pendingDamageAssignment ?: return false
        val game = bridge.getGame() ?: return false

        log.info("CombatHandler: damage assignment pending for {} (damage={})", prompt.attacker.name, prompt.damageDealt)
        sendAssignDamageReq(bridge, prompt)
        return true
    }

    /**
     * Handle AssignDamageResp from client.
     *
     * Parses the response, completes the [DamageAssignmentPrompt] future on
     * [WebPlayerController] so the engine thread unblocks with the damage map.
     * For batched responses with multiple assigners, caches subsequent
     * attacker maps on WPC for the engine's per-attacker loop.
     */
    fun onAssignDamage(
        greMsg: ClientToGREMessage,
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val resp = greMsg.assignDamageResp
        val wpc = bridge.humanController ?: run {
            log.warn("CombatHandler: no humanController for damage assignment")
            return
        }
        val prompt = wpc.pendingDamageAssignment ?: run {
            log.warn("CombatHandler: AssignDamageResp but no pending damage assignment")
            ops.sendRealGameState(bridge)
            return
        }
        val game = bridge.getGame() ?: return

        // Parse all assigners. First assigner completes the blocking future;
        // subsequent assigners are cached for Forge's per-attacker loop.
        var firstMap: MutableMap<forge.game.card.Card?, Int>? = null

        for (assigner in resp.assignersList) {
            val attackerForgeId = bridge.getForgeCardId(InstanceId(assigner.instanceId))
            if (attackerForgeId == null) {
                log.warn("CombatHandler: unknown attacker instanceId={}", assigner.instanceId)
                continue
            }

            val damageMap = mutableMapOf<forge.game.card.Card?, Int>()
            for (assignment in assigner.assignmentsList) {
                val blockerForgeId = bridge.getForgeCardId(InstanceId(assignment.instanceId))
                if (blockerForgeId != null) {
                    val card = findCard(game, blockerForgeId)
                    if (card != null) damageMap[card] = assignment.assignedDamage
                } else if (assignment.assignedDamage > 0) {
                    // Defender (player) — null key in Forge's damage map
                    damageMap[null] = assignment.assignedDamage
                }
            }

            // Trample overflow: totalDamage minus assigned-to-blockers → defender (null key).
            // The client doesn't send a defender slot — overflow is implicit.
            val assignedToBlockers = damageMap.values.sum()
            val overflow = assigner.totalDamage - assignedToBlockers
            if (overflow > 0 && !damageMap.containsKey(null)) {
                damageMap[null] = overflow
            }
            log.info(
                "CombatHandler: damageMap={} overflow={} total={}",
                damageMap.entries.joinToString { "${it.key?.name ?: "DEFENDER"} → ${it.value}" },
                overflow,
                assigner.totalDamage,
            )

            if (firstMap == null) {
                firstMap = damageMap
            } else {
                // Cache for subsequent per-attacker calls
                wpc.damageAssignCache[attackerForgeId.value] = damageMap
            }
        }

        log.info(
            "CombatHandler: AssignDamageResp assigners={} cached={}",
            resp.assignersCount,
            wpc.damageAssignCache.size,
        )

        // Send confirmation
        ops.sendBundledGRE(
            listOf(
                ops.makeGRE(GREMessageType.AssignDamageConfirmation_695e, ops.counter.currentGsId(), ops.counter.nextMsgId()) {
                    it.assignDamageConfirmation = AssignDamageConfirmation.newBuilder()
                        .setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        // Complete the future — engine thread unblocks in WPC.assignCombatDamage
        if (firstMap != null) {
            prompt.future.complete(firstMap)
        } else {
            log.warn("CombatHandler: no assigners in response, completing with empty map")
            prompt.future.complete(mutableMapOf())
        }
        bridge.awaitPriority()
        autoPass(bridge)
    }

    /**
     * Build and send a batched AssignDamageReq from the pending
     * [DamageAssignmentPrompt] context.
     */
    private fun sendAssignDamageReq(
        bridge: GameBridge,
        prompt: WebPlayerController.DamageAssignmentPrompt,
    ) {
        val humanPlayer = bridge.getPlayer(ops.seatId) ?: return

        val attackerIid = bridge.getOrAllocInstanceId(ForgeCardId(prompt.attacker.id))
        val assignments = mutableListOf<DamageAssignment>()

        // Recording conformance: real server sets totalDamage = attacker power,
        // no maxDamage, no defender slot. assignedDamage pre-filled so
        // sum(assignedDamage) == totalDamage — last blocker gets overflow.
        // Client requires sum == totalDamage to enable Done button.
        var assigned = 0
        for ((idx, blocker) in prompt.blockers.withIndex()) {
            val blockerIid = bridge.getOrAllocInstanceId(ForgeCardId(blocker.id))
            val lethal = if (prompt.hasDeathtouch) 1 else maxOf(0, blocker.netToughness - blocker.damage)
            val isLast = idx == prompt.blockers.size - 1
            val preFill = if (isLast) prompt.damageDealt - assigned else lethal
            assigned += preFill
            assignments.add(
                DamageAssignment.newBuilder()
                    .setInstanceId(blockerIid.value)
                    .setMinDamage(lethal)
                    .setAssignedDamage(preFill)
                    .build(),
            )
        }
        val assigner = DamageAssigner.newBuilder()
            .setInstanceId(attackerIid.value)
            .setTotalDamage(prompt.damageDealt)
            .addAllAssignments(assignments)
            .setCanIgnoreBlockers(prompt.hasTrample)
            .setDecisionPrompt(
                Prompt.newBuilder()
                    .setPromptId(ASSIGN_DAMAGE_PROMPT_ID)
                    .addParameters(
                        PromptParameter.newBuilder()
                            .setParameterName("CardId")
                            .setType(ParameterType.Number)
                            .setNumberValue(attackerIid.value),
                    ),
            )
            .build()

        log.info("CombatHandler: AssignDamageReq attacker={} assignments={}", prompt.attacker.name, assignments.size)

        val req = AssignDamageReq.newBuilder().addDamageAssigners(assigner).build()
        ops.sendBundledGRE(
            listOf(
                ops.makeGRE(GREMessageType.AssignDamageReq_695e, ops.counter.currentGsId(), ops.counter.nextMsgId()) {
                    it.assignDamageReq = req
                },
            ),
        )
    }

    // --- Sending helpers ---

    /**
     * Echo-back for iterative attacker toggle: sends GSM with provisional
     * combat state on toggled creatures + fresh DeclareAttackersReq.
     */
    private fun sendAttackerEchoBack(bridge: GameBridge) {
        val game = bridge.getGame() ?: return
        val result = ops.bundleBuilder!!.echoAttackersBundle(
            game,
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
        val result = ops.bundleBuilder!!.declareAttackersBundle(game, ops.counter, req)

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
        val result = ops.bundleBuilder!!.echoBlockersBundle(
            game,
            ops.counter,
            blockAssignments = lastDeclaredBlockAssignments.toMap(),
        )
        Tap.outboundTemplate("DeclareBlockersReq echo seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
    }

    private fun sendDeclareBlockersReq(bridge: GameBridge): Boolean {
        val game = bridge.getGame() ?: return false
        val req = RequestBuilder.buildDeclareBlockersReq(game, ops.seatId.value, bridge)

        if (req.blockersCount == 0) {
            log.info("CombatHandler: zero legal blockers — auto-submitting empty declaration")
            pendingBlockersSent = true
            return true // caller should auto-advance
        }

        val result = ops.bundleBuilder!!.declareBlockersBundle(game, ops.counter)
        pendingBlockersSent = true
        Tap.outboundTemplate("DeclareBlockersReq seat=${ops.seatId}")
        ops.sendBundledGRE(result.messages)
        return false
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
        val playback = bridge.playbacks[ops.seatId] ?: return
        if (playback.hasPendingMessages()) {
            val batches = playback.drainQueue()
            for (batch in batches) {
                ops.sendBundledGRE(batch) // sendBundledGRE records client-seen turn info
            }
        }
    }
}
