package leyline.match

import leyline.bridge.coord.PriorityPolicyRuntime
import leyline.game.data.KeywordAbilityIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles the `PerformActionResp` dispatch cycle: validate the inbound action,
 * submit a correlated value response to the coordinator, and drive the
 * post-action continuation. The engine thread resolves the retained executable
 * action and owns all progression after that point.
 *
 * **Threading:** Callers invoke inside the session lock. This class adds no
 * locking of its own.
 *
 * **State:** Stateless between calls. Priority settings are submitted to the
 * match runtime, which remains the sole owner of mutable policy state.
 */
internal class ActionPerformer(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val matchRecorder: MatchRecorder? = null,
    private val targetingHandler: TargetingHandler,
    private val priorityPolicy: PriorityPolicyRuntime,
    private val ctx: SessionContext,
    private val continuation: MatchRuntimeContinuation,
) {
    private val log = LoggerFactory.getLogger(ActionPerformer::class.java)

    /**
     * Handle a client action (land play, spell cast, activate, pass, …) and
     * advance the engine to the next priority stop.
     */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    fun perform(greMsg: ClientToGREMessage) {
        var acceptedClaim: leyline.bridge.coord.MatchActionWindowRuntime.ActionClaim? = null
        try {
            val bridge = ctx.bridge
            val seatBridge = bridge.seat(counters.seatId)
            log.info("ActionPerformer: perform enter gsId={} (current={})", greMsg.gameStateId, counters.counter.currentGsId())

            // Reject stale actions — client may resend with outdated gameStateId.
            // Compare against the last prompt's gsId, not currentGsId. Trailing
            // post-content echoes (and any future bundle that emits a non-prompt
            // GRE between the AAR and the client's response) advance currentGsId
            // past the AAR's gsId; a legitimate response targets the AAR's gsId,
            // not the latest counter value. Anything strictly less than the last
            // prompt is genuinely stale (a newer prompt has been emitted since).
            val clientGsId = greMsg.gameStateId
            if (clientGsId != 0 && clientGsId < counters.counter.lastPromptGsId()) {
                log.warn(
                    "ActionPerformer: stale PerformActionResp gsId={} (lastPrompt={}), ignoring",
                    clientGsId,
                    counters.counter.lastPromptGsId(),
                )
                return
            }

            val paymentResult = targetingHandler.tryHandlePayCostsPerformAction(greMsg)
            when (paymentResult) {
                HandlerResult.Resume -> continuation.awaitHorizon()
                is HandlerResult.ResumeAfterEngineResume -> continuation.awaitHorizon(paymentResult)
                HandlerResult.Waiting -> Unit
                HandlerResult.NotHandled -> Unit
            }
            if (paymentResult != HandlerResult.NotHandled) {
                return
            }

            val pending =
                seatBridge.action.getPending() ?: run {
                    log.warn("ActionPerformer: PerformActionResp but no pending action — resyncing current state")
                    bridge.cutCoordinator.drain(counters.seatId).forEach { sink.sendBundledGRE(it) }
                    return
                }
            val action = greMsg.performActionResp.actionsList.firstOrNull()
            if (action == null) {
                log.warn("ActionPerformer: PerformActionResp with no actions")
                return
            }
            val mayDefer =
                action.actionType == ActionType.Cast ||
                    action.actionType == ActionType.CastAdventure ||
                    action.actionType == ActionType.CastLeftRoom ||
                    action.actionType == ActionType.CastRightRoom ||
                    action.actionType == ActionType.CastOmen ||
                    action.actionType == ActionType.CastMdfc
            val claim = bridge.cutCoordinator.claimPriorityResponse(pending.actionId, clientGsId, action, defer = mayDefer)
            if (claim == null) {
                log.warn(
                    "ActionPerformer: action does not match pending window gsId={} promptGsId={} phase={}, ignoring",
                    clientGsId,
                    pending.promptGameStateId,
                    pending.state.phase,
                )
                return
            }
            acceptedClaim = claim.actionClaim

            // Track autoPassPriority from PerformActionResp (full control / auto-pass OK)
            val autoPassPriority = greMsg.performActionResp.autoPassPriority
            if (autoPassPriority != AutoPassPriority.None_a099) {
                priorityPolicy.submitAutoPassPriority(autoPassPriority)
                log.debug("ActionPerformer: autoPassPriority={}", autoPassPriority)
            }

            Tap.inboundAction(action)
            matchRecorder?.recordClientAction(greMsg)

            if (!mayDefer) {
                check(bridge.cutCoordinator.completeActionClaim(claim.actionClaim)) { "Accepted action claim did not complete" }
                acceptedClaim = null
            }
            when (action.actionType) {
                ActionType.Pass, ActionType.FloatMana -> Unit
                ActionType.Play_add3, ActionType.PlayMdfc -> {
                    Tap.actionResult(action.actionType, action.instanceId, claim.cardId, true)
                }
                ActionType.Cast -> {
                    val submitted = submitCastOrDefer(action, claim.actionClaim) ?: return
                    acceptedClaim = null
                    Tap.actionResult(action.actionType, action.instanceId, claim.cardId, submitted)
                }
                ActionType.Activate_add3 -> {
                    Tap.actionResult(action.actionType, action.instanceId, claim.cardId, true)
                }
                ActionType.ActivateMana -> {
                    Tap.actionResult(action.actionType, action.instanceId, claim.cardId, true)
                }
                ActionType.CastMdfc, ActionType.CastAdventure, ActionType.CastOmen, ActionType.CastLeftRoom, ActionType.CastRightRoom -> {
                    val submitted = submitCastOrDefer(action, claim.actionClaim) ?: return
                    acceptedClaim = null
                    Tap.actionResult(action.actionType, action.instanceId, claim.cardId, submitted)
                }
                ActionType.SpecialTurnFaceUp_add3 -> {
                    Tap.actionResult(action.actionType, action.instanceId, claim.cardId, true)
                }
                else -> {
                    log.info("ActionPerformer: unhandled action type {}, passing", action.actionType)
                    return
                }
            }

            continuation.awaitClientVisibleHorizon(ignoredActionId = pending.actionId)
        } catch (ex: Exception) {
            acceptedClaim?.let { ctx.bridge.cutCoordinator.failActionClaim(it, ex) }
            ctx.bridge.cutCoordinator.fail(ex)
        }
    }

    private fun submitCastOrDefer(
        action: Action,
        actionClaim: leyline.bridge.coord.MatchActionWindowRuntime.ActionClaim,
    ): Boolean? {
        if (targetingHandler.checkAlternateAdditionalCostChoice(actionClaim)) {
            Tap.outboundTemplate("Cast deferred — alternate additional cost prompt sent")
            return null
        }
        if (targetingHandler.checkHybridManaTypeOptions(actionClaim)) {
            Tap.outboundTemplate("Cast deferred — hybrid mana type prompt sent")
            return null
        }
        val skipOptionalCostPrompt =
            action.alternativeGrpId == KeywordAbilityIds.JUMP_START || action.alternativeGrpId == KeywordAbilityIds.RETRACE
        if (!skipOptionalCostPrompt && targetingHandler.checkOptionalCosts(actionClaim)) {
            Tap.outboundTemplate("Cast deferred — optional cost prompt sent")
            return null
        }
        check(ctx.bridge.cutCoordinator.completeActionClaim(actionClaim)) { "Deferred action claim did not complete" }
        return true
    }
}
