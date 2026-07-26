package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.AssignedDamageValue
import leyline.bridge.handoff.DamageAssignmentPrompt
import leyline.bridge.handoff.DamageAssignmentValue
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.handoff.Target
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ForgePlayerId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.opponent
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import kotlin.collections.iterator

/**
 * Handles combat-related client messages and auto-pass combat phase detection.
 *
 * Protocol sequencing uses the shared [MessageCounter][leyline.game.bundle.MessageCounter]
 * via `counters.counter` — no seeding or syncing needed.
 */
open class CombatHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val bundles: BundleBuilderHolder,
    private val pacing: Pacing,
    protected val ctx: SessionContext,
) {
    companion object {
        private const val ASSIGN_DAMAGE_PROMPT_ID = PromptIds.ASSIGN_DAMAGE
    }

    private val log = LoggerFactory.getLogger(CombatHandler::class.java)

    /** Legal attacker instanceIds from the last DeclareAttackersReq we sent.
     *  Owned by MatchOwner — all reads/writes occur within its serial execution domain. */
    var pendingLegalAttackers: List<Int> = emptyList()
        private set

    /** Last declared attacker instanceIds — updated by iterative DeclareAttackersResp
     *  (creature toggles / "Attack All"), defaults to [pendingLegalAttackers] when we
     *  send DeclareAttackersReq with pre-selected attackers. Used by SubmitAttackersReq
     *  (the "Done" button, which carries no payload). */
    private var lastDeclaredAttackerIds: List<Int> = emptyList()
    private var lastDeclaredAttackAlternatives: Map<Int, Int> = emptyMap()
    private var lastDeclaredDamageRecipients: Map<Int, DamageRecipient> = emptyMap()

    /** Last declared blocker assignments: blockerInstanceId → attackerInstanceId.
     *  Updated by iterative DeclareBlockersResp, consumed by SubmitBlockersReq. */
    private val lastDeclaredBlockAssignments = mutableMapOf<Int, Int>()

    /** True while a DeclareBlockersReq is outstanding (sent but not yet responded to).
     *  Prevents [checkCombatPhase] from re-sending during the priority window after
     *  blockers are submitted. Cleared in [onDeclareBlockers]. */
    var pendingBlockersSent: Boolean = false
        private set

    /** Clear all combat state for puzzle hot-swap. */
    fun reset() {
        pendingLegalAttackers = emptyList()
        lastDeclaredAttackerIds = emptyList()
        lastDeclaredAttackAlternatives = emptyMap()
        lastDeclaredDamageRecipients = emptyMap()
        lastDeclaredBlockAssignments.clear()
        pendingBlockersSent = false
    }

    /**
     * True if a Submit (finalize) message carries a stale gsId — the client may
     * send on either channel (race), and the echo-back advances the counter, so
     * a Submit from the slower channel arrives with an outdated gsId. Shared by
     * [onDeclareAttackers] and [onDeclareBlockers]; see ActionPerformer.perform
     * for the rationale.
     */
    private fun isStaleSubmit(
        greMsg: ClientToGREMessage,
        label: String,
    ): Boolean {
        val clientGsId = greMsg.gameStateId
        if (clientGsId != 0 && clientGsId < counters.counter.lastPromptGsId()) {
            log.debug(
                "CombatHandler: stale {} gsId={} (lastPrompt={}), ignoring",
                label,
                clientGsId,
                counters.counter.lastPromptGsId(),
            )
            return true
        }
        return false
    }

    private fun defaultAttackRecipient(): DamageRecipient =
        DamageRecipient
            .newBuilder()
            .setType(DamageRecType.Player_a0e5)
            .setPlayerSystemSeatId(counters.seatId.opponent.value)
            .build()

    private data class AttackSelection(
        val alternativeGrpId: Int,
        val damageRecipient: DamageRecipient,
    )

    private fun DamageRecipient.toTarget(bridge: GameBridge): Target? =
        when (type) {
            DamageRecType.None_a0e5,
            DamageRecType.Team_a0e5,
            DamageRecType.UNRECOGNIZED,
            -> null
            DamageRecType.Player_a0e5 -> Target.Player(ForgePlayerId(playerSystemSeatId))
            DamageRecType.PlanesWalker ->
                bridge.getForgeCardId(InstanceId(planeswalkerInstanceId))?.let { Target.Card(it) }
        }

    /**
     * Loop signal from combat phase checks.
     *
     * - [STOP] — sent interactive prompt (DeclareAttackersReq/DeclareBlockersReq), waiting for client response.
     * - [SEND_STATE] — informational: show the board, client has priority. Bypasses checkHumanActions.
     * - [CONTINUE] — nothing to do, fall through to action check.
     *
     * **AI turn handling:** AutoPassEngine downgrades SEND_STATE to fall-through on AI turns.
     * Client expects no actions during AI combat phases (actionsCount=0 in GSMs).
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
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val isSubmit = greMsg.type == ClientMessageType.SubmitAttackersReq

        // Reject stale Submit messages — the client may send on either channel (race).
        // The echo-back advances the counter, so a Submit from the slower channel
        // arrives with an outdated gsId. Only applies to Submit (type-only signal);
        // iterative DeclareAttackersResp always carries fresh data.
        // Compare against lastPromptGsId — see ActionPerformer.perform for the
        // rationale; this branch shares the predicate.
        if (isSubmit && isStaleSubmit(greMsg, "SubmitAttackersReq")) return

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
                lastDeclaredAttackAlternatives = lastDeclaredAttackerIds.associateWith { 0 }
                lastDeclaredDamageRecipients = lastDeclaredAttackerIds.associateWith { defaultAttackRecipient() }
                log.info("CombatHandler: Attack All — selected all {} pending attackers", lastDeclaredAttackerIds.size)
            } else {
                // A recipient-bearing entry selects or updates an attacker. An entry
                // without a recipient deselects an attacker that was already selected.
                val current =
                    lastDeclaredAttackerIds
                        .associateWith { id ->
                            AttackSelection(
                                alternativeGrpId = lastDeclaredAttackAlternatives[id] ?: 0,
                                damageRecipient = lastDeclaredDamageRecipients[id] ?: defaultAttackRecipient(),
                            )
                        }.toMutableMap()
                for (attacker in resp.selectedAttackersList) {
                    val id = attacker.attackerInstanceId
                    if (attacker.hasSelectedDamageRecipient()) {
                        current[id] =
                            AttackSelection(
                                alternativeGrpId = attacker.alternativeGrpId,
                                damageRecipient = attacker.selectedDamageRecipient,
                            )
                    } else if (id in current) {
                        current -= id
                    } else {
                        log.warn("CombatHandler: attacker {} omitted selectedDamageRecipient", id)
                        ResponseEnvelopeGuard.reject(
                            greMsg,
                            FailureReason.UnexpectedMessage,
                            counters.counter,
                            sink,
                        )
                        sendAttackerEchoBack()
                        return
                    }
                }
                lastDeclaredAttackAlternatives = current.mapValues { it.value.alternativeGrpId }
                lastDeclaredDamageRecipients = current.mapValues { it.value.damageRecipient }
                lastDeclaredAttackerIds = current.keys.toList()
                log.info("CombatHandler: selection {} → committed {}", resp.selectedAttackersList, lastDeclaredAttackAlternatives)
            }
            // Echo back GSM with creature objects (no combat state) + DeclareAttackersReq.
            sendAttackerEchoBack()
            return
        }

        submitAttackers(autoPass)
    }

    private fun submitAttackers(autoPass: () -> Unit) {
        // Finalize — use last known selection
        val bridge = ctx.bridge
        val seatBridge = bridge.seat(counters.seatId)
        val pending =
            seatBridge.action.getPending() ?: run {
                log.warn("CombatHandler: SubmitAttackersReq but no pending action — recovering")
                DevCheck.fail { "SubmitAttackersReq but no pending action" }
                sink.sendRealGameState(bridge)
                return
            }

        val selectedInstanceIds = lastDeclaredAttackerIds
        log.info(
            "CombatHandler: SubmitAttackers instanceIds={} (pending={})",
            selectedInstanceIds,
            pendingLegalAttackers.size,
        )

        val attackerCardIds =
            selectedInstanceIds.mapNotNull { instanceId ->
                DevCheck.requireOrNull(bridge.getForgeCardId(InstanceId(instanceId))) {
                    "CombatHandler: instanceId $instanceId not in map (map size=${bridge.getInstanceIdMap().size})"
                }
            }
        val selectedAlternatives = lastDeclaredAttackAlternatives
        val selectedDamageRecipients = lastDeclaredDamageRecipients
        val attackAlternativeByAttacker =
            selectedInstanceIds
                .mapNotNull { instanceId ->
                    val cardId = bridge.getForgeCardId(InstanceId(instanceId)) ?: return@mapNotNull null
                    val alternativeGrpId = selectedAlternatives[instanceId] ?: 0
                    if (alternativeGrpId == 0) null else cardId to alternativeGrpId
                }.toMap()
        val defenderByAttacker =
            selectedInstanceIds
                .mapNotNull { instanceId ->
                    val cardId = bridge.getForgeCardId(InstanceId(instanceId)) ?: return@mapNotNull null
                    val target = selectedDamageRecipients[instanceId]?.toTarget(bridge) ?: return@mapNotNull null
                    cardId to target
                }.toMap()
        pendingLegalAttackers = emptyList()
        lastDeclaredAttackerIds = emptyList()
        lastDeclaredAttackAlternatives = emptyMap()
        lastDeclaredDamageRecipients = emptyMap()

        log.info("CombatHandler: SubmitAttackers forgeCardIds={}", attackerCardIds)

        sink.sendBundledGRE(
            listOf(
                sink.makeGRE(GREMessageType.SubmitAttackersResp_695e, counters.counter.currentGsId(), counters.counter.nextMsgId()) {
                    it.submitAttackersResp = SubmitAttackersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        // Resolve the defending player: the opponent of the active (attacking) player.
        val defenderPlayerId = bridge.opponentPlayerId(counters.seatId)

        seatBridge.action.submitAction(
            pending.actionId,
            PlayerAction.DeclareAttackers(
                attackerCardIds,
                attackAlternativeByAttacker = attackAlternativeByAttacker,
                defender = defenderPlayerId?.let { Target.Player(it) },
                defenderByAttacker = defenderByAttacker,
            ),
        )
        bridge.awaitPriority()
        autoPass()
    }

    /**
     * Handle CancelActionReq during attack declaration — pass combat with no attackers.
     *
     * The client sends CancelActionReq when the player clicks "Cancel" during the
     * declare attackers phase. This submits an empty attacker list to the engine,
     * which passes combat entirely (no attacks, skip to post-combat main).
     */
    fun onCancelAttackers(autoPass: () -> Unit) {
        val bridge = ctx.bridge
        val seatBridge = bridge.seat(counters.seatId)
        val pending =
            seatBridge.action.getPending() ?: run {
                log.warn("CombatHandler: CancelAttackers but no pending action — recovering")
                DevCheck.fail { "CancelAttackers but no pending action" }
                sink.sendRealGameState(bridge)
                return
            }

        log.info("CombatHandler: CancelAttackers — submitting empty attackers to pass combat")

        pendingLegalAttackers = emptyList()
        lastDeclaredAttackerIds = emptyList()
        lastDeclaredAttackAlternatives = emptyMap()
        lastDeclaredDamageRecipients = emptyMap()

        sink.sendBundledGRE(
            listOf(
                sink.makeGRE(GREMessageType.SubmitAttackersResp_695e, counters.counter.currentGsId(), counters.counter.nextMsgId()) {
                    it.submitAttackersResp = SubmitAttackersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        val defenderPlayerId = bridge.opponentPlayerId(counters.seatId)

        seatBridge.action.submitAction(
            pending.actionId,
            PlayerAction.DeclareAttackers(emptyList(), defender = defenderPlayerId?.let { Target.Player(it) }),
        )
        bridge.awaitPriority()
        autoPass()
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
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val isSubmit = greMsg.type == ClientMessageType.SubmitBlockersReq

        // Reject stale Submit — same pattern as attackers (see onDeclareAttackers).
        if (isSubmit && isStaleSubmit(greMsg, "SubmitBlockersReq")) return

        if (!isSubmit) {
            // DeclareBlockersResp is diff-style: each entry carries only the
            // toggled blocker. Non-empty selectedAttackerInstanceIds → assign;
            // empty → deselect. Server must accumulate across responses.
            val resp = greMsg.declareBlockersResp
            for (blocker in resp.selectedBlockersList) {
                val attackerIid = blocker.selectedAttackerInstanceIdsList.firstOrNull()
                if (attackerIid != null) {
                    lastDeclaredBlockAssignments[blocker.blockerInstanceId] = attackerIid
                } else {
                    lastDeclaredBlockAssignments.remove(blocker.blockerInstanceId)
                }
            }
            log.info("CombatHandler: blocker update — assignments={}, echoing DeclareBlockersReq", lastDeclaredBlockAssignments)
            sendBlockerEchoBack()
            return
        }

        // SubmitBlockersReq: finalize
        val seatBridge = bridge.seat(counters.seatId)
        val pending =
            seatBridge.action.getPending() ?: run {
                log.warn("CombatHandler: SubmitBlockersReq but no pending action — recovering")
                DevCheck.fail { "SubmitBlockersReq but no pending action" }
                sink.sendRealGameState(bridge)
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

        sink.sendBundledGRE(
            listOf(
                sink.makeGRE(GREMessageType.SubmitBlockersResp_695e, counters.counter.currentGsId(), counters.counter.nextMsgId()) {
                    it.submitBlockersResp = SubmitBlockersResp.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

        seatBridge.action.submitAction(
            pending.actionId,
            PlayerAction.DeclareBlockers(blockAssignments),
        )
        bridge.awaitPriority()
        autoPass()
    }

    /**
     * Check combat phases and send appropriate prompts or state.
     * Called from the auto-pass loop.
     */
    @Suppress("ReturnCount")
    open fun checkCombatPhase(
        phase: String?,
        isHumanTurn: Boolean,
        isAiTurn: Boolean,
    ): Signal {
        val combatHasAttackers = ctx.bridge.runtimeFacts(counters.seatId).combatHasAttackers

        when (phase) {
            "COMBAT_DECLARE_ATTACKERS" -> {
                // New combat round — reset blocker-sent flag from previous combat.
                pendingBlockersSent = false
                if (isHumanTurn) {
                    // Don't re-prompt if attackers already declared (post-declaration priority window)
                    if (combatHasAttackers) {
                        return Signal.CONTINUE
                    }
                    val pending =
                        ctx.bridge
                            .seat(counters.seatId)
                            .action
                            .getPending()
                    if (pending?.state?.kind != PendingActionKind.DECLARE_ATTACKERS) {
                        return Signal.CONTINUE
                    }
                    val req = bundles.bundleBuilder.buildDeclareAttackersReq()
                    if (req.attackersCount > 0) {
                        sendDeclareAttackersReq(req)
                        return Signal.STOP
                    }
                } else if (isAiTurn && combatHasAttackers) {
                    pacing.paceDelay(2)
                    return Signal.SEND_STATE
                }
            }
            "COMBAT_DECLARE_BLOCKERS" -> {
                if (isAiTurn && combatHasAttackers && !pendingBlockersSent) {
                    // Wait for engine to reach declareBlockers() on the human player's
                    // PlayerController — it creates a pending action via awaitAction().
                    // Without this, we'd send DeclareBlockersReq before the engine is
                    // ready to accept the response, causing "no pending action" errors.
                    ctx.bridge.awaitPriority()
                    // Drain any pending playback messages — the engine thread may have
                    // queued AI actions between the last drain and now.
                    drainPendingPlayback()
                    val pending =
                        ctx.bridge
                            .seat(counters.seatId)
                            .action
                            .getPending()
                    if (pending?.state?.kind != PendingActionKind.DECLARE_BLOCKERS) {
                        return Signal.CONTINUE
                    }
                    val skipBlockers = sendDeclareBlockersReq()
                    if (skipBlockers) {
                        // Zero legal blockers — submit empty declaration and advance
                        val seatBridge = ctx.bridge.seat(counters.seatId)
                        val blockerPending = seatBridge.action.getPending()
                        if (blockerPending != null) {
                            seatBridge.action.submitAction(blockerPending.actionId, PlayerAction.DeclareBlockers(emptyMap()))
                            ctx.bridge.awaitPriority()
                        }
                        return Signal.SEND_STATE
                    }
                    return Signal.STOP
                } else if (isHumanTurn && combatHasAttackers) {
                    pacing.paceDelay(2)
                    return Signal.SEND_STATE
                }
            }
            "COMBAT_DAMAGE" -> {
                pacing.paceDelay(2)
                return Signal.SEND_STATE
            }
            "COMBAT_END" -> {
                // Same: combat may be cleared by the time we check
                return Signal.SEND_STATE
            }
            "UNTAP",
            "UPKEEP",
            "DRAW",
            "MAIN1",
            "COMBAT_BEGIN",
            "COMBAT_FIRST_STRIKE_DAMAGE",
            "MAIN2",
            "END_OF_TURN",
            "CLEANUP",
            null,
            -> {}
        }
        return Signal.CONTINUE
    }

    // --- Damage assignment ---

    /**
     * Check if the engine is blocked waiting for manual damage assignment.
     *
     * Called from [AutoPassEngine.autoPassAndAdvance] between combat phase
     * handling and interactive prompt checks. Uses the dedicated
     * [DamageAssignmentPrompt] future on the human controller — NOT the
     * [GameActionBridge] — so the auto-pass loop cannot interfere.
     *
     * @return true if AssignDamageReq was sent (caller should exit the loop)
     */
    fun checkPendingDamageAssignment(): Boolean {
        val prompt = ctx.bridge.pendingDamageAssignment() ?: return false

        log.info("CombatHandler: damage assignment pending for {} (damage={})", prompt.attacker.name, prompt.damageDealt)
        sendAssignDamageReq(prompt)
        return true
    }

    /**
     * Handle AssignDamageResp from client.
     *
     * Parses the response, completes the [DamageAssignmentPrompt] future on
     * human controller so the engine thread unblocks with the damage map.
     * For batched responses with multiple assigners, caches subsequent
     * attacker maps on WPC for the engine's per-attacker loop.
     */
    fun onAssignDamage(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val resp = greMsg.assignDamageResp
        if (bridge.pendingDamageAssignment() == null) {
            log.warn("CombatHandler: AssignDamageResp but no pending damage assignment")
            DevCheck.fail { "AssignDamageResp but no pending damage assignment" }
            sink.sendRealGameState(bridge)
            return
        }

        val assignments =
            resp.assignersList.mapNotNull { assigner ->
                val attackerId =
                    DevCheck.requireOrNull(bridge.getForgeCardId(InstanceId(assigner.instanceId))) {
                        "CombatHandler: unknown attacker instanceId=${assigner.instanceId}"
                    } ?: return@mapNotNull null
                DamageAssignmentValue(
                    attackerId = attackerId,
                    totalDamage = assigner.totalDamage,
                    assignments =
                        assigner.assignmentsList.mapNotNull { assignment ->
                            val targetId = bridge.getForgeCardId(InstanceId(assignment.instanceId))
                            if (targetId == null && assignment.assignedDamage <= 0) return@mapNotNull null
                            AssignedDamageValue(targetId, assignment.assignedDamage)
                        },
                )
            }

        log.info(
            "CombatHandler: AssignDamageResp assigners={}",
            resp.assignersCount,
        )

        // Send confirmation
        sink.sendBundledGRE(
            listOf(
                sink.makeGRE(GREMessageType.AssignDamageConfirmation_695e, counters.counter.currentGsId(), counters.counter.nextMsgId()) {
                    it.assignDamageConfirmation =
                        AssignDamageConfirmation
                            .newBuilder()
                            .setResult(ResultCode.Success_a500)
                            .build()
                },
            ),
        )

        bridge.submitDamageAssignment(assignments)
        bridge.awaitPriority()
        autoPass()
    }

    /**
     * Build and send a batched AssignDamageReq from the pending
     * [DamageAssignmentPrompt] context.
     */
    private fun sendAssignDamageReq(prompt: DamageAssignmentPrompt) {
        val bridge = ctx.bridge

        val attackerIid = bridge.getOrAllocInstanceId(prompt.attacker.id)
        val assignments = mutableListOf<DamageAssignment>()

        // Reference conformance: blocker slots have minDamage=lethal, assignedDamage
        // pre-filled. Trample adds a defender (player) slot with instanceId=defendingSeatId,
        // maxDamage=overflow, assignedDamage=overflow, no minDamage.
        // Client requires sum(assignedDamage) == totalDamage to enable Done button.
        var assigned = 0
        for (blocker in prompt.blockers) {
            val blockerIid = bridge.getOrAllocInstanceId(blocker.id)
            val lethal = if (prompt.hasDeathtouch) 1 else maxOf(0, blocker.netToughness - blocker.damage)
            assigned += lethal
            assignments.add(
                DamageAssignment
                    .newBuilder()
                    .setInstanceId(blockerIid.value)
                    .setMinDamage(lethal)
                    .setAssignedDamage(lethal)
                    .build(),
            )
        }

        // Trample overflow → defender (player) slot
        if (prompt.hasTrample && prompt.hasDefender) {
            val overflow = prompt.damageDealt - assigned
            val defendingSeatId = counters.seatId.opponent.value
            assignments.add(
                DamageAssignment
                    .newBuilder()
                    .setInstanceId(defendingSeatId)
                    .setMaxDamage(overflow)
                    .setAssignedDamage(overflow)
                    .build(),
            )
        }
        val assigner =
            DamageAssigner
                .newBuilder()
                .setInstanceId(attackerIid.value)
                .setTotalDamage(prompt.damageDealt)
                .addAllAssignments(assignments)
                .setCanIgnoreBlockers(prompt.hasTrample)
                .setDecisionPrompt(
                    Prompt
                        .newBuilder()
                        .setPromptId(ASSIGN_DAMAGE_PROMPT_ID)
                        .addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("CardId")
                                .setType(ParameterType.Number)
                                .setNumberValue(attackerIid.value),
                        ),
                ).build()

        log.info("CombatHandler: AssignDamageReq attacker={} assignments={}", prompt.attacker.name, assignments.size)

        val req = AssignDamageReq.newBuilder().addDamageAssigners(assigner).build()
        sink.sendBundledGRE(
            listOf(
                sink.makeGRE(GREMessageType.AssignDamageReq_695e, counters.counter.currentGsId(), counters.counter.nextMsgId()) {
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
    private fun sendAttackerEchoBack() {
        val result =
            bundles.bundleBuilder.echoAttackersBundle(
                counters.counter,
                selectedAttackerIds = lastDeclaredAttackerIds,
                selectedAttackAlternatives = lastDeclaredAttackAlternatives,
                selectedDamageRecipients = lastDeclaredDamageRecipients,
                allLegalAttackerIds = pendingLegalAttackers,
            )
        Tap.outboundTemplate("DeclareAttackersReq echo seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    /**
     * @param resetSelection true on initial send (no attackers selected yet),
     *                       false on echo-back (preserve current [lastDeclaredAttackerIds]).
     */
    private fun sendDeclareAttackersReq(
        req: DeclareAttackersReq? = null,
        resetSelection: Boolean = true,
    ) {
        val result = bundles.bundleBuilder.declareAttackersBundle(counters.counter, req)

        val builtReq = result.messages.firstOrNull { it.hasDeclareAttackersReq() }?.declareAttackersReq
        pendingLegalAttackers = builtReq?.attackersList?.map { it.attackerInstanceId } ?: emptyList()
        if (resetSelection) {
            // Initial send — no attackers selected yet. Client clicks populate lastDeclaredAttackerIds.
            lastDeclaredAttackerIds = emptyList()
            lastDeclaredAttackAlternatives = emptyMap()
            lastDeclaredDamageRecipients = emptyMap()
        }
        log.debug("DeclareAttackersReq: pendingLegalAttackers={} lastDeclared={}", pendingLegalAttackers, lastDeclaredAttackerIds)

        Tap.outboundTemplate("DeclareAttackersReq seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    /**
     * Echo-back for iterative blocker toggle: sends GSM with provisional
     * block state on toggled creatures + fresh DeclareBlockersReq.
     */
    private fun sendBlockerEchoBack() {
        val result =
            bundles.bundleBuilder.echoBlockersBundle(
                counters.counter,
                blockAssignments = lastDeclaredBlockAssignments.toMap(),
            )
        Tap.outboundTemplate("DeclareBlockersReq echo seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }

    private fun sendDeclareBlockersReq(): Boolean {
        val req = bundles.bundleBuilder.buildDeclareBlockersReq()

        if (req.blockersCount == 0) {
            log.info("CombatHandler: zero legal blockers — auto-submitting empty declaration")
            pendingBlockersSent = true
            return true // caller should auto-advance
        }

        val result = bundles.bundleBuilder.declareBlockersBundle(counters.counter)
        pendingBlockersSent = true
        Tap.outboundTemplate("DeclareBlockersReq seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
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
    private fun drainPendingPlayback() {
        val playback = ctx.bridge.playbackFor(counters.seatId) ?: return
        if (playback.hasPendingMessages()) {
            val batches = playback.drainQueue()
            for (batch in batches) {
                sink.sendBundledGRE(batch) // sendBundledGRE records client-seen turn info
            }
        }
    }
}
