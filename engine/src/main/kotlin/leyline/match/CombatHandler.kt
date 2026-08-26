package leyline.match

import forge.game.phase.PhaseType
import leyline.DevCheck
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.DamageAssignmentCommand
import leyline.bridge.handoff.DamageAssignmentRow
import leyline.bridge.handoff.DeclarationAnswer
import leyline.bridge.handoff.PendingActionKind
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
    private val pacing: Pacing,
    protected val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(CombatHandler::class.java)

    /** True while a DeclareBlockersReq is outstanding (sent but not yet responded to).
     *  Prevents [checkCombatPhase] from re-sending during the priority window after
     *  blockers are submitted. Cleared in [onDeclareBlockers]. */
    var pendingBlockersSent: Boolean = false
        private set

    /** Clear all combat state for puzzle hot-swap. */
    fun reset() {
        pendingBlockersSent = false
    }

    fun hasPendingAttackers(): Boolean =
        ctx.bridge
            .seat(counters.seatId)
            .action
            .getPending()
            ?.state
            ?.kind == PendingActionKind.DECLARE_ATTACKERS

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

    private fun DamageRecipient.toDeclarationTarget(): DeclarationAnswer.Target? =
        when (type) {
            DamageRecType.Player_a0e5 -> DeclarationAnswer.Target.Player(playerSystemSeatId)
            DamageRecType.PlanesWalker -> DeclarationAnswer.Target.Planeswalker(planeswalkerInstanceId)
            DamageRecType.None_a0e5,
            DamageRecType.Team_a0e5,
            DamageRecType.UNRECOGNIZED,
            -> null
        }

    private fun rejectDeclaration(
        greMsg: ClientToGREMessage,
        actionId: String,
        promptGameStateId: Int?,
    ) {
        if (greMsg.gameStateId != promptGameStateId) return
        ResponseEnvelopeGuard.reject(greMsg, FailureReason.UnexpectedMessage, counters.counter, sink)
        if (ctx.bridge.cutCoordinator.republishDeclaration(actionId)) drainPendingPlayback()
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
     *   If `auto_declare=true`, selects all attackers retained by the published runtime window.
     * - **SubmitAttackersReq** (type=31): finalize — client sends empty "Done" signal.
     *   The runtime resolves the retained client-domain selection to the engine action.
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
            val resp = greMsg.declareAttackersResp
            log.debug(
                "CombatHandler: DeclareAttackersResp autoDeclare={} selectedCount={} selectedIds={}",
                resp.autoDeclare,
                resp.selectedAttackersCount,
                resp.selectedAttackersList.map { it.attackerInstanceId },
            )
            val pending = bridge.seat(counters.seatId).action.getPending() ?: return
            if (resp.selectedAttackersList.any {
                    it.hasSelectedDamageRecipient() && it.selectedDamageRecipient.toDeclarationTarget() == null
                }
            ) {
                log.warn("CombatHandler: attacker declaration contains an unsupported damage recipient")
                rejectDeclaration(greMsg, pending.actionId, pending.promptGameStateId)
                return
            }
            val recipients =
                resp.selectedAttackersList
                    .mapNotNull { attacker ->
                        if (!attacker.hasSelectedDamageRecipient()) return@mapNotNull null
                        val target = attacker.selectedDamageRecipient.toDeclarationTarget() ?: return@mapNotNull null
                        attacker.attackerInstanceId to target
                    }.toMap()
            val answer =
                DeclarationAnswer.Attackers.of(
                    attackerInstanceIds = resp.selectedAttackersList.map { it.attackerInstanceId },
                    attackAlternativeByAttacker = resp.selectedAttackersList.associate { it.attackerInstanceId to it.alternativeGrpId },
                    defenderByAttacker = recipients,
                    autoDeclare = resp.autoDeclare,
                )
            if (!bridge.cutCoordinator.updateDeclaration(pending.actionId, greMsg.gameStateId, answer)) {
                log.warn("CombatHandler: attacker declaration did not match exact pending window")
                rejectDeclaration(greMsg, pending.actionId, pending.promptGameStateId)
                return
            }
            drainPendingPlayback()
            return
        }

        submitAttackers(greMsg.gameStateId, autoPass)
    }

    private fun submitAttackers(
        responseGameStateId: Int,
        autoPass: () -> Unit,
    ) {
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

        val confirmation = {
            sink.makeGRE(GREMessageType.SubmitAttackersResp_695e, counters.counter.currentGsId(), counters.counter.nextMsgId()) {
                it.submitAttackersResp = SubmitAttackersResp.newBuilder().setResult(ResultCode.Success_a500).build()
            }
        }
        bridge.cutCoordinator.submitDeclaredAction(
            pending.actionId,
            responseGameStateId,
            confirmation,
        )
        // Release the committed confirmation before auto-pass starts the next
        // phases. The coordinator queue preserves ordering; this is its flush
        // boundary for the client's submit acknowledgment.
        sink.sendPriorityState(bridge)
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

        val confirmation = {
            sink.makeGRE(GREMessageType.SubmitAttackersResp_695e, counters.counter.currentGsId(), counters.counter.nextMsgId()) {
                it.submitAttackersResp = SubmitAttackersResp.newBuilder().setResult(ResultCode.Success_a500).build()
            }
        }

        bridge.cutCoordinator.submitDeclaredAction(
            pending.actionId,
            responseGameStateId = checkNotNull(pending.promptGameStateId),
            confirmation,
        )
        bridge.awaitPriority()
        autoPass()
    }

    /**
     * Handle DeclareBlockersResp or SubmitBlockersReq from the client.
     *
     * Same two-phase protocol as attackers:
     * - **DeclareBlockersResp** (type=32): iterative update with blocker assignments
     * - **SubmitBlockersReq** (type=33): finalize — the runtime resolves the retained selection.
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
            val pending = bridge.seat(counters.seatId).action.getPending() ?: return
            val touched = resp.selectedBlockersList.map { it.blockerInstanceId }
            val assignments =
                resp.selectedBlockersList
                    .mapNotNull { blocker ->
                        blocker.selectedAttackerInstanceIdsList.firstOrNull()?.let { blocker.blockerInstanceId to it }
                    }.toMap()
            if (!bridge.cutCoordinator.updateDeclaration(
                    pending.actionId,
                    greMsg.gameStateId,
                    DeclarationAnswer.Blockers.of(assignments, touched),
                )
            ) {
                log.warn("CombatHandler: blocker declaration did not match exact pending window")
                rejectDeclaration(greMsg, pending.actionId, pending.promptGameStateId)
                return
            }
            drainPendingPlayback()
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

        // Don't clear pendingBlockersSent here — a priority window may follow
        // in DECLARE_BLOCKERS step before moving to damage. Cleared when a new
        // combat starts (COMBAT_DECLARE_ATTACKERS).

        val confirmation = {
            sink.makeGRE(GREMessageType.SubmitBlockersResp_695e, counters.counter.currentGsId(), counters.counter.nextMsgId()) {
                it.submitBlockersResp = SubmitBlockersResp.newBuilder().setResult(ResultCode.Success_a500).build()
            }
        }

        bridge.cutCoordinator.submitDeclaredAction(
            pending.actionId,
            greMsg.gameStateId,
            confirmation,
        )
        bridge.awaitPriority()
        autoPass()
    }

    /**
     * Check combat phases and send appropriate prompts or state.
     * Called from the auto-pass loop.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    open fun checkCombatPhase(
        phase: PhaseType?,
        isHumanTurn: Boolean,
        isAiTurn: Boolean,
    ): Signal {
        val game = ctx.game
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
                    val pending =
                        ctx.bridge
                            .seat(counters.seatId)
                            .action
                            .getPending()
                    if (pending?.state?.kind != PendingActionKind.DECLARE_ATTACKERS) {
                        return Signal.CONTINUE
                    }
                    if (ctx.bridge.cutCoordinator.hasLegalAttackers(pending.actionId)) {
                        return Signal.STOP
                    }
                    ctx.bridge.cutCoordinator.submitDeclaredAction(
                        pending.actionId,
                        checkNotNull(pending.promptGameStateId),
                    )
                    ctx.bridge.awaitPriority()
                    return Signal.CONTINUE
                } else if (isAiTurn && combat != null && combat.attackers.isNotEmpty()) {
                    pacing.paceDelay(2)
                    return Signal.SEND_STATE
                }
            }
            PhaseType.COMBAT_DECLARE_BLOCKERS -> {
                if (isAiTurn && combat != null && combat.attackers.isNotEmpty() && !pendingBlockersSent) {
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
                    pendingBlockersSent = true
                    val skipBlockers = ctx.bridge.cutCoordinator.legalBlockerCount(pending.actionId) == 0
                    if (skipBlockers) {
                        // Zero legal blockers — submit empty declaration and advance
                        val seatBridge = ctx.bridge.seat(counters.seatId)
                        val blockerPending = seatBridge.action.getPending()
                        if (blockerPending != null) {
                            ctx.bridge.cutCoordinator.submitDeclaredAction(
                                blockerPending.actionId,
                                checkNotNull(blockerPending.promptGameStateId),
                            )
                            ctx.bridge.awaitPriority()
                        }
                        return Signal.SEND_STATE
                    }
                    return Signal.STOP
                } else if (isHumanTurn && combat != null && combat.attackers.isNotEmpty()) {
                    pacing.paceDelay(2)
                    return Signal.SEND_STATE
                }
            }
            PhaseType.COMBAT_DAMAGE -> {
                pacing.paceDelay(2)
                return Signal.SEND_STATE
            }
            PhaseType.COMBAT_END -> {
                // Same: combat may be cleared by the time we check
                return Signal.SEND_STATE
            }
            PhaseType.UNTAP,
            PhaseType.UPKEEP,
            PhaseType.DRAW,
            PhaseType.MAIN1,
            PhaseType.COMBAT_BEGIN,
            PhaseType.COMBAT_FIRST_STRIKE_DAMAGE,
            PhaseType.MAIN2,
            PhaseType.END_OF_TURN,
            PhaseType.CLEANUP,
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
     * coordinator-owned interaction window, so the auto-pass loop cannot interfere.
     *
     * @return true if AssignDamageReq was sent (caller should exit the loop)
     */
    fun checkPendingDamageAssignment(): Boolean {
        val pending = ctx.bridge.cutCoordinator.currentBlockingInteraction() ?: return false
        val prompt = pending.interaction as? BlockingInteraction.Damage ?: return false

        log.info("CombatHandler: damage assignment pending for {} (damage={})", prompt.attackerId, prompt.damageDealt)
        drainPendingPlayback()
        return true
    }

    /**
     * Handle AssignDamageResp from client.
     *
     * Parses value assignments and submits them to the coordinator. Live card
     * handles stay on the engine side of the interaction window.
     */
    fun onAssignDamage(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val resp = greMsg.assignDamageResp
        val pending =
            bridge.cutCoordinator.currentBlockingInteraction()?.takeIf { it.interaction is BlockingInteraction.Damage } ?: run {
                log.warn("CombatHandler: AssignDamageResp but no pending damage assignment")
                DevCheck.fail { "AssignDamageResp but no pending damage assignment" }
                sink.sendRealGameState(bridge)
                return
            }
        // Parse all assigners. First assigner completes the blocking future;
        // subsequent assigners are cached for Forge's per-attacker loop.
        val assignmentValues = mutableListOf<DamageAssignmentCommand>()

        for (assigner in resp.assignersList) {
            log.info(
                "CombatHandler: damage rows={} total={}",
                assigner.assignmentsList.map { "${it.instanceId} → ${it.assignedDamage}" },
                assigner.totalDamage,
            )
            assignmentValues +=
                DamageAssignmentCommand(
                    attackerInstanceId = assigner.instanceId,
                    assignments = assigner.assignmentsList.map { DamageAssignmentRow(it.instanceId, it.assignedDamage) },
                    totalDamage = assigner.totalDamage,
                )
        }

        log.info(
            "CombatHandler: AssignDamageResp assigners={} cached={}",
            resp.assignersCount,
            maxOf(0, assignmentValues.size - 1),
        )

        // Complete the future — engine thread unblocks in WPC.assignCombatDamage
        if (assignmentValues.isEmpty()) log.warn("CombatHandler: no assigners in response, completing with empty map")
        if (!bridge.cutCoordinator.submitDamageCommand(pending.interactionId, greMsg.gameStateId, assignmentValues)) return
        bridge.awaitPriority()
        autoPass()
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
