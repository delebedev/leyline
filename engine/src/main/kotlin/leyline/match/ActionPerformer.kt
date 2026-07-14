package leyline.match

import leyline.bridge.handoff.ActionResponseKey
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.WubrgColorMapping
import leyline.game.data.KeywordAbilityIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles the `PerformActionResp` dispatch cycle: validate the inbound action,
 * submit the appropriate [PlayerAction] to the engine, and drive the post-action
 * flow (awaitPriority → post-cast prompt → modal ETB check → auto-pass advance).
 *
 * **Threading:** Callers invoke inside the session lock. This class adds no
 * locking of its own.
 *
 * **State:** Stateless between calls. [autoPassState] is a shared reference —
 * reads and writes flow through it to stay visible to other handlers.
 */
class ActionPerformer(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val matchRecorder: MatchRecorder? = null,
    private val bundles: BundleBuilderHolder,
    private val targetingHandler: TargetingHandler,
    private val autoPassEngine: AutoPassEngine,
    private val autoPassState: ClientAutoPassState,
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(ActionPerformer::class.java)

    /**
     * Handle a client action (land play, spell cast, activate, pass, …) and
     * advance the engine to the next priority stop.
     */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    fun perform(greMsg: ClientToGREMessage) {
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

        if (targetingHandler.tryHandlePayCostsPerformAction(greMsg) { autoPassEngine.autoPassAndAdvance() }) {
            return
        }

        val pending =
            seatBridge.action.getPending() ?: run {
                log.warn("ActionPerformer: PerformActionResp but no pending action — resyncing current state")
                sink.sendBundle(bundles.bundleBuilder.stateOnlyDiff(ctx.game, counters.counter))
                return
            }
        if (!seatBridge.action.acceptsResponse(pending, clientGsId)) {
            log.warn(
                "ActionPerformer: PerformActionResp gsId={} does not match pending prompt gsId={} phase={}, ignoring",
                clientGsId,
                pending.promptGameStateId,
                pending.state.phase,
            )
            return
        }

        // Track autoPassPriority from PerformActionResp (full control / auto-pass OK)
        val autoPassPriority = greMsg.performActionResp.autoPassPriority
        if (autoPassPriority != AutoPassPriority.None_a099) {
            autoPassState.updateAutoPassPriority(autoPassPriority)
            log.debug("ActionPerformer: autoPassPriority={}", autoPassPriority)
        }

        val action = greMsg.performActionResp.actionsList.firstOrNull()
        if (action == null) {
            log.warn("ActionPerformer: PerformActionResp with no actions")
            return
        }
        val offer =
            seatBridge.action.resolveOfferedAction(pending, clientGsId, action) ?: run {
                log.warn(
                    "ActionPerformer: action is absent from pending offer catalog: response={}, offers={}",
                    ActionResponseKey.from(action),
                    pending.actionCatalog?.keys,
                )
                return
            }
        val command = offer.command

        // Stop decision timer — client responded
        if (bridge.matchConfig.game.timer) {
            val timerStop = bundles.bundleBuilder.timerStop(counters.counter)
            sink.sendBundledGRE(timerStop.messages)
        }

        Tap.inboundAction(action)
        matchRecorder?.recordClientAction(greMsg)

        // ActivateMana excluded: mana abilities don't use the stack (MTG 605.3),
        // so they don't reach handlePostCastPrompt or the post-stack-resolution check.
        val isCastOrActivate =
            action.actionType == ActionType.Cast ||
                action.actionType == ActionType.Activate_add3 ||
                action.actionType == ActionType.CastAdventure ||
                action.actionType == ActionType.CastLeftRoom ||
                action.actionType == ActionType.CastRightRoom ||
                action.actionType == ActionType.CastOmen ||
                action.actionType == ActionType.CastMdfc ||
                action.actionType == ActionType.SpecialTurnFaceUp_add3
        val game = ctx.game
        val stackWasNonEmpty = !game.stack.isEmpty
        when (action.actionType) {
            ActionType.Pass -> {
                seatBridge.action.submitAction(pending.actionId, command)
            }
            ActionType.Play_add3, ActionType.PlayMdfc -> {
                val cardId = (command as? PlayerAction.PlayLand)?.cardId
                val submitted = seatBridge.action.submitAction(pending.actionId, command)
                Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
            }
            ActionType.Cast -> {
                val cast = command as? PlayerAction.CastSpell ?: return
                val submitted = submitCastOrDefer(action, pending.actionId, cast) ?: return
                Tap.actionResult(action.actionType, action.instanceId, cast.cardId, submitted)
            }
            ActionType.Activate_add3 -> {
                offer.stackAbilityGrpId?.let { grpId -> offer.forgeAbilityId?.let { bridge.recordStackAbilityGrpId(it, grpId) } }
                val activate = command as? PlayerAction.ActivateAbility ?: return
                val submitted = seatBridge.action.submitAction(pending.actionId, activate)
                Tap.actionResult(action.actionType, action.instanceId, activate.cardId, submitted)
            }
            ActionType.ActivateMana -> {
                val mana = command as? PlayerAction.ActivateMana ?: return
                val submitted = seatBridge.action.submitAction(pending.actionId, mana.copy(selectedColor = selectedManaColor(action)))
                Tap.actionResult(action.actionType, action.instanceId, mana.cardId, submitted)
            }
            ActionType.CastMdfc, ActionType.CastAdventure, ActionType.CastOmen, ActionType.CastLeftRoom, ActionType.CastRightRoom -> {
                val cast = command as? PlayerAction.CastSpell ?: return
                val submitted = submitCastOrDefer(action, pending.actionId, cast) ?: return
                Tap.actionResult(action.actionType, action.instanceId, cast.cardId, submitted)
            }
            ActionType.SpecialTurnFaceUp_add3 -> {
                val activate = command as? PlayerAction.ActivateAbility ?: return
                val submitted = seatBridge.action.submitAction(pending.actionId, activate)
                Tap.actionResult(action.actionType, action.instanceId, activate.cardId, submitted)
            }
            else -> {
                log.info("ActionPerformer: unhandled action type {}, passing", action.actionType)
                seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
            }
        }

        // Wait for engine to reach next priority stop
        bridge.awaitPriority()
        autoPassEngine.drainPlayback()

        if (action.actionType == ActionType.ActivateMana) {
            sink.sendRealGameState(bridge)
            return
        }

        // After a cast or activate, check for targeting prompt or intermediate stack state.
        // Pass clientAutoResolve when the client opts in to auto-resolving stack effects (#92).
        if (isCastOrActivate && targetingHandler.handlePostCastPrompt(autoPassState.shouldAutoPass())) return

        // After stack resolution: check for modal ETB prompt before sending state.
        // The engine may have fired a modal trigger (e.g. Charming Prince ETB)
        // during resolution, blocking in chooseModeForAbility.
        if (stackWasNonEmpty) {
            val g = ctx.game
            // Check for pending modal prompt from ETB trigger
            when (targetingHandler.checkPendingPrompt()) {
                TargetingHandler.PromptResult.SENT_TO_CLIENT -> return
                TargetingHandler.PromptResult.AUTO_RESOLVED -> {
                    // Fall through to autoPass
                }
                TargetingHandler.PromptResult.NONE -> {
                    if (g.stack.isEmpty) {
                        val nextPending = seatBridge.action.getPending()
                        if (nextPending?.state?.kind == PendingActionKind.DECLARE_ATTACKERS ||
                            nextPending?.state?.kind == PendingActionKind.DECLARE_BLOCKERS
                        ) {
                            autoPassEngine.autoPassAndAdvance()
                            return
                        }
                        log.info("ActionPerformer: stack resolved, sending intermediate resolution state")
                        sink.sendRealGameState(bridge)
                        if (g.isGameOver) {
                            log.info("ActionPerformer: game over after stack resolution")
                            sink.sendGameOver()
                            return
                        }
                        return
                    }
                }
            }
        }

        autoPassEngine.autoPassAndAdvance()
    }

    private fun selectedManaColor(action: Action): Byte? {
        val paymentColors =
            action.manaPaymentOptionsList
                .asSequence()
                .flatMap { it.manaList.asSequence() }
                .filter { it.srcInstanceId == 0 || it.srcInstanceId == action.instanceId }
                .map { it.color }
                .filter { it != ManaColor.None_afc9 }
                .distinct()
                .toList()
        paymentColors.singleOrNull()?.toMagicColorMask()?.let { return it }

        val explicitSelections =
            action.manaSelectionsList
                .asSequence()
                .filter { it.instanceId == 0 || it.instanceId == action.instanceId }
                .flatMap { it.optionsList.asSequence() }
                .map { it.selectedColor }
                .filter { it != ManaColor.None_afc9 }
                .distinct()
                .toList()
        return explicitSelections.singleOrNull()?.toMagicColorMask()
    }

    private fun ManaColor.toMagicColorMask(): Byte? = WubrgColorMapping.magicMaskForManaColor(this)

    private fun submitCastOrDefer(
        action: Action,
        pendingActionId: String,
        cast: PlayerAction.CastSpell,
    ): Boolean? {
        if (targetingHandler.checkAlternateAdditionalCostChoice(action, pendingActionId)) {
            Tap.outboundTemplate("Cast deferred — alternate additional cost prompt sent")
            return null
        }
        if (targetingHandler.checkHybridManaTypeOptions(action, pendingActionId, cast.abilityId)) {
            Tap.outboundTemplate("Cast deferred — hybrid mana type prompt sent")
            return null
        }
        val skipOptionalCostPrompt =
            action.alternativeGrpId == KeywordAbilityIds.JUMP_START || action.alternativeGrpId == KeywordAbilityIds.RETRACE
        if (!skipOptionalCostPrompt && targetingHandler.checkOptionalCosts(action, pendingActionId, cast.abilityId)) {
            Tap.outboundTemplate("Cast deferred — optional cost prompt sent")
            return null
        }
        val seatBridge = ctx.bridge.seat(counters.seatId)
        return seatBridge.action.submitAction(pendingActionId, cast)
    }
}
