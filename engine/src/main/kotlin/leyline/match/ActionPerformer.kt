package leyline.match

import leyline.bridge.handoff.ActionResponseKey
import leyline.bridge.handoff.ActionToken
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.WubrgColorMapping
import leyline.game.data.KeywordAbilityIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles the `PerformActionResp` dispatch cycle: validate the inbound action,
 * submit the selected opaque action token to the engine, and drive the post-action
 * flow (awaitPriority → post-cast prompt → modal ETB check → auto-pass advance).
 *
 * **Threading:** Callers invoke on the serial match owner. This class adds no
 * synchronization of its own.
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
    private val lastPromptGsId: () -> Int,
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
        val promptGsId = lastPromptGsId()
        if (clientGsId != 0 && clientGsId < promptGsId) {
            log.warn(
                "ActionPerformer: stale PerformActionResp gsId={} (lastPrompt={}), ignoring",
                clientGsId,
                promptGsId,
            )
            return
        }

        if (targetingHandler.tryHandlePayCostsPerformAction(greMsg) { autoPassEngine.autoPassAndAdvance() }) {
            return
        }

        val pending =
            seatBridge.action.getPending() ?: run {
                val runtime = bridge.runtimeFacts(counters.seatId)
                if (runtime.isGameOver && !runtime.hasPlayer) {
                    log.warn("ActionPerformer: PerformActionResp after game retirement — ignoring")
                    return
                }
                log.warn("ActionPerformer: PerformActionResp but no pending action — resyncing current state")
                sink.sendBundle(bundles.bundleBuilder.stateOnlyDiff(counters.counter))
                return
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
                    pending.publishedCatalog?.catalog?.keys,
                )
                return
            }

        val acceptedActionEffects =
            AcceptedActionEffects(
                autoPassPriority = greMsg.performActionResp.autoPassPriority,
                selectedSpellCardId = offer.cardId.takeIf { action.isCastAction() },
                selectedSpellGrpId = offer.spellGrpId,
            )
        val onAccepted = {
            acceptedActionEffects.apply(autoPassState, bridge)
            if (acceptedActionEffects.autoPassPriority != AutoPassPriority.None_a099) {
                log.debug("ActionPerformer: autoPassPriority={}", acceptedActionEffects.autoPassPriority)
            }
        }

        fun submitActionToken(
            token: ActionToken,
            selectedManaColor: Byte? = null,
        ): Boolean =
            seatBridge.action.submitActionToken(
                pending.actionId,
                token,
                selectedManaColor,
                onAccepted,
            )

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
        val stackWasNonEmpty = !ctx.bridge.runtimeFacts(counters.seatId).stackEmpty
        val submitted =
            when (action.actionType) {
                ActionType.Pass, ActionType.FloatMana -> {
                    submitActionToken(offer.token)
                }
                ActionType.Play_add3, ActionType.PlayMdfc -> {
                    val cardId = offer.cardId
                    val submitted = submitActionToken(offer.token)
                    Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
                    submitted
                }
                ActionType.Cast -> {
                    val cardId = offer.cardId ?: return
                    val submitted =
                        submitCastOrDefer(
                            action,
                            pending.actionId,
                            offer.token,
                            cardId,
                            offer.abilityId,
                            acceptedActionEffects,
                        ) ?: return
                    Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
                    submitted
                }
                ActionType.Activate_add3 -> {
                    val submitted = submitActionToken(offer.token)
                    Tap.actionResult(action.actionType, action.instanceId, offer.cardId, submitted)
                    submitted
                }
                ActionType.ActivateMana -> {
                    val submitted = submitActionToken(offer.token, selectedManaColor(action))
                    Tap.actionResult(action.actionType, action.instanceId, offer.cardId, submitted)
                    submitted
                }
                ActionType.CastMdfc, ActionType.CastAdventure, ActionType.CastOmen, ActionType.CastLeftRoom, ActionType.CastRightRoom -> {
                    val cardId = offer.cardId ?: return
                    val submitted =
                        submitCastOrDefer(
                            action,
                            pending.actionId,
                            offer.token,
                            cardId,
                            offer.abilityId,
                            acceptedActionEffects,
                        ) ?: return
                    Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
                    submitted
                }
                ActionType.SpecialTurnFaceUp_add3 -> {
                    val submitted = submitActionToken(offer.token)
                    Tap.actionResult(action.actionType, action.instanceId, offer.cardId, submitted)
                    submitted
                }
                else -> {
                    log.warn("ActionPerformer: rejecting unhandled action type {}", action.actionType)
                    return
                }
            }
        if (!submitted) {
            log.warn("ActionPerformer: pending priority window rejected {}", action.actionType)
            return
        }

        // Wait for engine to reach next priority stop
        ctx.engine.awaitPriority()
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
            // Check for pending modal prompt from ETB trigger
            when (targetingHandler.checkPendingPrompt()) {
                TargetingHandler.PromptResult.SENT_TO_CLIENT -> return
                TargetingHandler.PromptResult.AUTO_RESOLVED -> {
                    // Fall through to autoPass
                }
                TargetingHandler.PromptResult.NONE -> {
                    val runtime = ctx.bridge.runtimeFacts(counters.seatId)
                    if (runtime.stackEmpty) {
                        val nextPending = seatBridge.action.getPending()
                        if (nextPending?.state?.kind == PendingActionKind.DECLARE_ATTACKERS ||
                            nextPending?.state?.kind == PendingActionKind.DECLARE_BLOCKERS
                        ) {
                            autoPassEngine.autoPassAndAdvance()
                            return
                        }
                        log.info("ActionPerformer: stack resolved, sending intermediate resolution state")
                        sink.sendRealGameState(bridge)
                        if (runtime.isGameOver) {
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
        actionToken: ActionToken,
        cardId: ForgeCardId,
        abilityId: Int?,
        acceptedActionEffects: AcceptedActionEffects,
    ): Boolean? {
        if (targetingHandler.checkAlternateAdditionalCostChoice(action, pendingActionId, cardId, acceptedActionEffects)) {
            Tap.outboundTemplate("Cast deferred — alternate additional cost prompt sent")
            return null
        }
        if (
            targetingHandler.checkHybridManaTypeOptions(
                action,
                pendingActionId,
                actionToken,
                cardId,
                abilityId,
                acceptedActionEffects,
            )
        ) {
            Tap.outboundTemplate("Cast deferred — hybrid mana type prompt sent")
            return null
        }
        val skipOptionalCostPrompt =
            action.alternativeGrpId == KeywordAbilityIds.JUMP_START || action.alternativeGrpId == KeywordAbilityIds.RETRACE
        if (!skipOptionalCostPrompt &&
            targetingHandler.checkOptionalCosts(
                action,
                pendingActionId,
                actionToken,
                cardId,
                abilityId,
                acceptedActionEffects,
            )
        ) {
            Tap.outboundTemplate("Cast deferred — optional cost prompt sent")
            return null
        }
        val seatBridge = ctx.bridge.seat(counters.seatId)
        return seatBridge.action.submitActionToken(
            pendingActionId,
            actionToken,
            onAccepted = { acceptedActionEffects.apply(autoPassState, ctx.bridge) },
        )
    }

    private fun Action.isCastAction(): Boolean =
        actionType == ActionType.Cast ||
            actionType == ActionType.CastMdfc ||
            actionType == ActionType.CastAdventure ||
            actionType == ActionType.CastOmen ||
            actionType == ActionType.CastLeftRoom ||
            actionType == ActionType.CastRightRoom
}
