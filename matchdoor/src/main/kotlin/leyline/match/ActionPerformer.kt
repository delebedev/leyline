package leyline.match

import leyline.bridge.findCard
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.InstanceId
import leyline.game.state.GameBridge
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
    private val tracer: SessionTracer,
    private val bundles: BundleBuilderHolder,
    private val targetingHandler: TargetingHandler,
    private val autoPassEngine: AutoPassEngine,
    private val autoPassState: ClientAutoPassState,
) {
    private val log = LoggerFactory.getLogger(ActionPerformer::class.java)

    /**
     * Handle a client action (land play, spell cast, activate, pass, …) and
     * advance the engine to the next priority stop.
     *
     * Caller resolves [ctx]; this method does not re-resolve.
     */
    fun perform(ctx: SessionContext, greMsg: ClientToGREMessage) {
        val bridge = ctx.bridge
        val seatBridge = bridge.seat(counters.seatId.value)
        log.info("ActionPerformer: perform enter gsId={} (current={})", greMsg.gameStateId, counters.counter.currentGsId())

        // Reject stale actions — client may resend with outdated gameStateId
        val clientGsId = greMsg.gameStateId
        if (clientGsId != 0 && clientGsId < counters.counter.currentGsId()) {
            log.warn("ActionPerformer: stale PerformActionResp gsId={} (current={}), ignoring", clientGsId, counters.counter.currentGsId())
            return
        }

        val pending = seatBridge.action.getPending() ?: run {
            log.warn("ActionPerformer: PerformActionResp but no pending action — resyncing current state")
            sink.sendRealGameState(bridge)
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

        // Stop decision timer — client responded
        if (bridge.matchConfig.game.timer) {
            val timerStop = bundles.bundleBuilder!!.timerStop(counters.counter)
            sink.sendBundledGRE(timerStop.messages)
        }

        Tap.inboundAction(action)
        tracer.recorder?.recordClientAction(greMsg)

        // ActivateMana excluded: mana abilities don't use the stack (MTG 605.3),
        // so they don't reach handlePostCastPrompt or the post-stack-resolution check.
        val isCastOrActivate = action.actionType == ActionType.Cast ||
            action.actionType == ActionType.Activate_add3 ||
            action.actionType == ActionType.CastAdventure
        val game = ctx.game
        val stackWasNonEmpty = !game.stack.isEmpty
        val actionName = action.actionType.name.removeSuffix("_add3")
        val cardName = if (action.instanceId != 0) {
            bridge.cardRepository.findNameByGrpId(action.grpId)?.let { " ($it)" } ?: ""
        } else {
            ""
        }
        tracer.traceEvent(MatchEventType.CLIENT_ACTION, game, "$actionName iid=${action.instanceId}$cardName")

        when (action.actionType) {
            ActionType.Pass -> {
                seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
            }
            ActionType.Play_add3 -> {
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val submitted = if (cardId != null) {
                    seatBridge.action.submitAction(pending.actionId, PlayerAction.PlayLand(cardId))
                } else {
                    seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
                }
                Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
            }
            ActionType.Cast -> {
                // Check for optional costs (kicker, buyback, etc.) before submitting.
                // If found, sends CastingTimeOptionsReq to client and returns without
                // submitting to engine. onCastingTimeOptions resumes the cast.
                if (targetingHandler.checkOptionalCosts(action, pending.actionId, bridge)) {
                    Tap.outboundTemplate("Cast deferred — optional cost prompt sent")
                    // Don't submit to engine yet — wait for CastingTimeOptionsResp
                } else {
                    val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                    val submitted = if (cardId != null) {
                        seatBridge.action.submitAction(pending.actionId, PlayerAction.CastSpell(cardId))
                    } else {
                        seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
                    }
                    Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
                }
            }
            ActionType.Activate_add3 -> {
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val abilityIndex = resolveAbilityIndex(action, bridge)
                val submitted = if (cardId != null) {
                    seatBridge.action.submitAction(
                        pending.actionId,
                        PlayerAction.ActivateAbility(cardId, abilityIndex),
                    )
                } else {
                    seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
                }
                Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
            }
            ActionType.ActivateMana -> {
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val submitted = if (cardId != null) {
                    seatBridge.action.submitAction(
                        pending.actionId,
                        PlayerAction.ActivateMana(cardId),
                    )
                } else {
                    seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
                }
                Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
            }
            ActionType.CastAdventure -> {
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val submitted = if (cardId != null) {
                    val card = findCard(game, cardId)
                    val player = bridge.getPlayer(counters.seatId)
                    val adventureIndex = if (card != null && player != null) {
                        getAllCastableAbilities(card, player)
                            .indexOfFirst { it.isAdventure }
                            .takeIf { it >= 0 }
                    } else {
                        null
                    }
                    if (adventureIndex == null) {
                        log.warn("CastAdventure: no adventure SA found for card={} iid={}", card?.name, action.instanceId)
                    }
                    seatBridge.action.submitAction(
                        pending.actionId,
                        PlayerAction.CastSpell(cardId, adventureIndex),
                    )
                } else {
                    seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
                }
                Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
            }
            else -> {
                log.info("ActionPerformer: unhandled action type {}, passing", action.actionType)
                seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
            }
        }

        // Wait for engine to reach next priority stop
        bridge.awaitPriority()

        // After a cast or activate, check for targeting prompt or intermediate stack state.
        // Pass clientAutoResolve when the client opts in to auto-resolving stack effects (#92).
        if (isCastOrActivate && targetingHandler.handlePostCastPrompt(bridge, autoPassState.shouldAutoPass())) return

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
        }

        autoPassEngine.autoPassAndAdvance(bridge)
    }

    /**
     * Resolve abilityIndex from `action.abilityGrpId` using the AbilityRegistry's
     * SlotLayout. Falls back to 0 when any lookup step fails.
     */
    private fun resolveAbilityIndex(action: Action, bridge: GameBridge): Int {
        val abilityGrpId = action.abilityGrpId
        if (abilityGrpId == 0) return 0

        // Resolve grpId: prefer action.grpId, fall back to instanceId lookup
        // (hand-zone Activate_add3 actions omit grpId to match client wire)
        val grpId = if (action.grpId != 0) {
            action.grpId
        } else {
            val forgeId = bridge.getForgeCardId(InstanceId(action.instanceId))
            val game = bridge.getGame()
            val card = if (forgeId != null && game != null) findCard(game, forgeId) else null
            if (card != null) {
                bridge.resolveGrpId(card, action.instanceId)
            } else {
                return 0
            }
        }
        val cardData = bridge.cardRepository.findByGrpId(grpId) ?: return 0
        val forgeCardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return 0
        val game = bridge.getGame() ?: return 0
        val forgeCard = game.findById(forgeCardId.value) ?: return 0
        val registry = bridge.abilityRegistryFor(forgeCard, cardData) ?: return 0

        val index = registry.slotLayout.forgeIndexFor(abilityGrpId)
        return if (index != null && index >= 0) index else 0
    }
}
