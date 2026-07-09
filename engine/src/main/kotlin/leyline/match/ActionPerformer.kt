package leyline.match

import forge.game.ability.ApiType
import leyline.bridge.findCard
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.getPlayableManaAbilities
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.pickMdfcBackSpellAbility
import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.WubrgColorMapping
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.AbilityGrpIdMode
import leyline.game.mapping.AltGrpIdSource
import leyline.game.mapping.CastRail
import leyline.game.mapping.CastRails
import leyline.game.mapping.RoomDoorCastDescriptors
import leyline.game.mapping.ZoneCastRail
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
                sink.sendRealGameState(bridge)
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

        // Stop decision timer — client responded
        if (bridge.matchConfig.game.timer) {
            val timerStop = bundles.bundleBuilder.timerStop(counters.counter)
            sink.sendBundledGRE(timerStop.messages)
        }

        Tap.inboundAction(action)
        tracer.recorder?.recordClientAction(greMsg)

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
        val actionName = action.actionType.name.removeSuffix("_add3")
        val cardName =
            if (action.instanceId != 0) {
                bridge.cardRepository.findNameByGrpId(action.grpId)?.let { " ($it)" } ?: ""
            } else {
                ""
            }
        tracer.traceEvent(MatchEventType.CLIENT_ACTION, game, "$actionName iid=${action.instanceId}$cardName")

        when (action.actionType) {
            ActionType.Pass -> {
                seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
            }
            ActionType.Play_add3, ActionType.PlayMdfc -> {
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val submitted =
                    if (cardId != null) {
                        seatBridge.action.submitAction(pending.actionId, PlayerAction.PlayLand(cardId))
                    } else {
                        seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
                    }
                Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
            }
            ActionType.Cast -> {
                val castAbilityIndex =
                    resolveCastAbilityIndex(action, bridge)
                        ?: resolveImplicitDisguiseCastAbilityIndex(action, bridge)
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val abilityIndex =
                    if (action.alternativeGrpId != 0 && cardId != null) {
                        resolveAltCostAbilityIndex(action, cardId, bridge)
                    } else {
                        castAbilityIndex
                    }
                val submitted = submitCastOrDefer(action, pending.actionId, cardId, castAbilityIndex, abilityIndex) ?: return
                Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
            }
            ActionType.Activate_add3 -> {
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val abilityIndex = resolveAbilityIndex(action, bridge)
                val submitted =
                    if (cardId != null) {
                        if (action.abilityGrpId != 0) {
                            val player = bridge.getPlayer(counters.seatId)
                            val card = findCard(game, cardId)
                            val abilityId =
                                if (player != null && card != null) {
                                    getNonManaActivatedAbilities(card, player).getOrNull(abilityIndex)?.id ?: 0
                                } else {
                                    0
                                }
                            bridge.recordStackAbilityGrpId(abilityId, action.abilityGrpId)
                        }
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
                val abilityIndex = resolveManaAbilityIndex(action, bridge)
                val submitted =
                    if (cardId != null) {
                        seatBridge.action.submitAction(
                            pending.actionId,
                            PlayerAction.ActivateMana(cardId, abilityIndex, selectedManaColor(action)),
                        )
                    } else {
                        seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
                    }
                Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
            }
            ActionType.CastMdfc -> {
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val card = cardId?.let { findCard(game, it) }
                val player = bridge.getPlayer(counters.seatId)
                val backSpell = card?.let(::pickMdfcBackSpellAbility)
                val abilityIndex =
                    if (card != null && player != null && backSpell != null) {
                        getAllCastableAbilities(card, player)
                            .indexOfFirst { it === backSpell }
                            .takeIf { it >= 0 }
                    } else {
                        null
                    }
                if (abilityIndex == null) {
                    log.warn("CastMdfc: no backside spell SA found for card={} iid={}", card?.name, action.instanceId)
                }
                val submitted = submitCastOrDefer(action, pending.actionId, cardId, abilityIndex, abilityIndex) ?: return
                Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
            }
            ActionType.CastAdventure -> {
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val submitted =
                    if (cardId != null) {
                        val card = findCard(game, cardId)
                        val player = bridge.getPlayer(counters.seatId)
                        val adventureIndex =
                            if (card != null && player != null) {
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
            ActionType.CastOmen -> {
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val submitted =
                    if (cardId != null) {
                        val card = findCard(game, cardId)
                        val player = bridge.getPlayer(counters.seatId)
                        val omenIndex =
                            if (card != null && player != null) {
                                getAllCastableAbilities(card, player)
                                    .indexOfFirst { it.isOmen }
                                    .takeIf { it >= 0 }
                            } else {
                                null
                            }
                        if (omenIndex == null) {
                            log.warn("CastOmen: no Omen SA found for card={} iid={}", card?.name, action.instanceId)
                        }
                        seatBridge.action.submitAction(pending.actionId, requiredAbilityCastAction(cardId, omenIndex))
                    } else {
                        seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
                    }
                Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
            }
            ActionType.CastLeftRoom, ActionType.CastRightRoom -> {
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val submitted =
                    if (cardId != null) {
                        val card = findCard(game, cardId)
                        val player = bridge.getPlayer(counters.seatId)
                        // Hand: the split-spell SA from card.getSpells() is the
                        // correct cast SA (the activated unlock SA is filtered out
                        // by canPlay's zone gate from hand). Battlefield: the
                        // unlock SA is the only castable. The descriptor handles
                        // both — the SA the offer-side emitted and the SA the
                        // accept-side expects must match by reference.
                        val descriptor = RoomDoorCastDescriptors.forActionType(action.actionType)
                        val abilityIndex =
                            if (card != null && player != null && descriptor != null) {
                                descriptor.resolveAbilityIndex(card, player)
                            } else {
                                null
                            }
                        if (abilityIndex == null) {
                            log.warn(
                                "{}: no door SA matched for card={} iid={}",
                                action.actionType,
                                card?.name,
                                action.instanceId,
                            )
                        }
                        seatBridge.action.submitAction(pending.actionId, requiredAbilityCastAction(cardId, abilityIndex))
                    } else {
                        seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
                    }
                Tap.actionResult(action.actionType, action.instanceId, cardId, submitted)
            }
            ActionType.SpecialTurnFaceUp_add3 -> {
                val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
                val submitted =
                    if (cardId != null) {
                        val card = findCard(game, cardId)
                        val player = bridge.getPlayer(counters.seatId)
                        // The disguise turn-face-up SA lives on the
                        // KeywordInstance (CardFactoryUtil.abilityTurnFaceUp);
                        // CardLookup.getAllCastableAbilities appends it so the
                        // accept side can reference the same SA the offer side
                        // emitted via reference equality.
                        val turnFaceUpSa =
                            card?.spellAbilities?.firstOrNull { it.isDisguiseUp }
                        val abilityIndex =
                            if (card != null && player != null && turnFaceUpSa != null) {
                                getNonManaActivatedAbilities(card, player)
                                    .indexOfFirst { it === turnFaceUpSa }
                                    .takeIf { it >= 0 }
                            } else {
                                null
                            }
                        if (abilityIndex == null) {
                            log.warn(
                                "SpecialTurnFaceUp: no turn-face-up SA matched for card={} iid={}",
                                card?.name,
                                action.instanceId,
                            )
                        }
                        seatBridge.action.submitAction(
                            pending.actionId,
                            PlayerAction.ActivateAbility(cardId, abilityIndex ?: 0),
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

    /**
     * Resolve abilityIndex from `action.abilityGrpId` using the AbilityRegistry's
     * SlotLayout. Falls back to 0 when any lookup step fails.
     */
    @Suppress("ReturnCount")
    private fun resolveAbilityIndex(
        action: Action,
        bridge: GameBridge,
    ): Int {
        val abilityGrpId = action.abilityGrpId
        if (abilityGrpId == 0) return 0

        // Resolve grpId: prefer action.grpId, fall back to instanceId lookup
        // (hand-zone Activate_add3 actions omit grpId to match client wire)
        val grpId =
            if (action.grpId != 0) {
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

        if (abilityGrpId == KeywordAbilityIds.RECONFIGURE_UNATTACH) {
            val player = bridge.getPlayer(counters.seatId) ?: return 0
            return getNonManaActivatedAbilities(forgeCard, player)
                .indexOfFirst { it.api == ApiType.Unattach && it.getParam("PrecostDesc") == "Reconfigure" }
                .takeIf { it >= 0 } ?: 0
        }

        val index = registry.slotLayout.forgeIndexFor(abilityGrpId)
        return if (index != null && index >= 0) index else 0
    }

    private fun resolveManaAbilityIndex(
        action: Action,
        bridge: GameBridge,
    ): Int? {
        val abilityGrpId = action.abilityGrpId
        if (abilityGrpId == 0) return null
        val forgeCardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return null
        val game = bridge.getGame() ?: return null
        val player = bridge.getPlayer(counters.seatId) ?: return null
        val card = findCard(game, forgeCardId) ?: return null
        val grpId = bridge.resolveGrpId(card, action.instanceId)
        val cardData = bridge.cardRepository.findByGrpId(grpId) ?: return null
        val registry = bridge.abilityRegistryFor(card, cardData) ?: return null
        return getPlayableManaAbilities(card, player)
            .indexOfFirst { registry.forSpellAbility(it.id) == abilityGrpId }
            .takeIf { it >= 0 }
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
        cardId: ForgeCardId?,
        castAbilityIndex: Int?,
        submitAbilityIndex: Int?,
    ): Boolean? {
        if (targetingHandler.checkAlternateAdditionalCostChoice(action, pendingActionId)) {
            Tap.outboundTemplate("Cast deferred — alternate additional cost prompt sent")
            return null
        }
        if (targetingHandler.checkHybridManaTypeOptions(action, pendingActionId, castAbilityIndex)) {
            Tap.outboundTemplate("Cast deferred — hybrid mana type prompt sent")
            return null
        }
        val skipOptionalCostPrompt =
            action.alternativeGrpId == KeywordAbilityIds.JUMP_START || action.alternativeGrpId == KeywordAbilityIds.RETRACE
        if (!skipOptionalCostPrompt && targetingHandler.checkOptionalCosts(action, pendingActionId, castAbilityIndex)) {
            Tap.outboundTemplate("Cast deferred — optional cost prompt sent")
            return null
        }
        val seatBridge = ctx.bridge.seat(counters.seatId)
        return if (cardId != null) {
            seatBridge.action.submitAction(pendingActionId, PlayerAction.CastSpell(cardId, submitAbilityIndex))
        } else {
            seatBridge.action.submitAction(pendingActionId, PlayerAction.PassPriority)
        }
    }

    private fun resolveCastAbilityIndex(
        action: Action,
        bridge: GameBridge,
    ): Int? {
        val forgeCardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return null
        val game = bridge.getGame() ?: return null
        val player = bridge.getPlayer(counters.seatId) ?: return null
        val card = findCard(game, forgeCardId) ?: return null
        val grpId = bridge.resolveGrpId(card, action.instanceId)
        val (candidates, _) =
            leyline.game.mapping.ActionMapper.buildIndexedHandCastActionsForCard(
                card = card,
                player = player,
                instanceId = action.instanceId,
                grpId = grpId,
                checkLegality = true,
                idResolver = { forgeId -> bridge.getOrAllocInstanceId(forgeId) },
                grpIdResolver = { candidate ->
                    val iid = bridge.getOrAllocInstanceId(ForgeCardId(candidate.id)).value
                    GrpId(bridge.resolveGrpId(candidate, iid))
                },
                cardDataLookup = { candidateGrpId -> bridge.cardRepository.findByGrpId(candidateGrpId.value) },
                abilityRegistryLookup = { candidate, cardData -> bridge.abilityRegistryFor(candidate, cardData) },
            )
        return candidates.firstOrNull { equivalentCastAction(it.action, action) }?.abilityIndex
    }

    private fun resolveImplicitDisguiseCastAbilityIndex(
        action: Action,
        bridge: GameBridge,
    ): Int? {
        if (action.alternativeGrpId != 0) return null
        if (!isFaceDownCastCost(action)) return null
        val forgeCardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return null
        val game = bridge.getGame() ?: return null
        val player = bridge.getPlayer(counters.seatId) ?: return null
        val card = findCard(game, forgeCardId) ?: return null
        val castable = getAllCastableAbilities(card, player)
        val index = castable.indexOfFirst { it.isCastFaceDown }.takeIf { it >= 0 } ?: return null
        log.info(
            "ActionPerformer: treating ambiguous Cast modal response as Disguise face-down cast card={} iid={}",
            card.name,
            action.instanceId,
        )
        return index
    }

    private fun equivalentCastAction(
        expected: Action,
        actual: Action,
    ): Boolean {
        val manaCostMatches = actual.manaCostCount == 0 || expected.manaCostList == actual.manaCostList
        val autoTapMatches = !actual.hasAutoTapSolution() || expected.autoTapSolution == actual.autoTapSolution

        return expected.actionType == actual.actionType &&
            expected.instanceId == actual.instanceId &&
            expected.grpId == actual.grpId &&
            expected.abilityGrpId == actual.abilityGrpId &&
            expected.alternativeGrpId == actual.alternativeGrpId &&
            manaCostMatches &&
            autoTapMatches
    }

    private fun isFaceDownCastCost(action: Action): Boolean {
        if (action.manaCostCount == 0) return false
        return action.manaCostList.all { it.colorList == listOf(ManaColor.Generic) } &&
            action.manaCostList.sumOf { it.count } == 3
    }

    @Suppress("ReturnCount")
    private fun resolveAltCostAbilityIndex(
        action: Action,
        cardId: ForgeCardId,
        bridge: GameBridge,
    ): Int? {
        val alternativeGrpId = action.alternativeGrpId
        if (alternativeGrpId == 0) return null

        val game = bridge.getGame() ?: return null
        val player = bridge.getPlayer(counters.seatId) ?: return null
        val card = findCard(game, cardId) ?: return null

        // Universal-149 ("Cast without paying mana cost") routes to rails that
        // declare AltGrpIdSource.Universal149 AND whose abilityGrpIdMode
        // declares the same FixedKeyword baseId the action carries. Today
        // only Plot's exile-cast leg matches (149 + abilityGrpId=PLOT); the
        // co-check keeps dispatch unambiguous if a future rail also adopts
        // Universal149 with a different fixed keyword.
        //
        // Per-card alternativeGrpIds resolve via findAbilityInfo and match
        // rails on the keyword BaseId. When more than one rail shares a
        // BaseId (Plot/Foretell hand vs exile-cast), iterate
        // getAllCastableAbilities and pick the SA the rails' saPredicates
        // match — getAllCastableAbilities only surfaces one flavor at a time
        // per zone so the match is unambiguous.
        val candidateRails: List<CastRail> =
            if (alternativeGrpId == 149) {
                CastRails.all.filter { rail ->
                    rail is ZoneCastRail &&
                        rail.altGrpIdSource is AltGrpIdSource.Universal149 &&
                        (rail.abilityGrpIdMode as? AbilityGrpIdMode.FixedKeyword)?.baseId == action.abilityGrpId
                }
            } else {
                CastRails.all.filter { it.kind.keywordBaseId == alternativeGrpId }.ifEmpty {
                    val info = bridge.cardRepository.findAbilityInfo(alternativeGrpId) ?: return null
                    CastRails.all.filter { it.kind.keywordBaseId == info.baseId }
                }
            }
        if (candidateRails.isEmpty()) return null

        val castable = getAllCastableAbilities(card, player)
        for ((idx, sa) in castable.withIndex()) {
            if (candidateRails.any { it.saPredicate(sa) }) return idx
        }
        return null
    }
}

internal fun requiredAbilityCastAction(
    cardId: ForgeCardId,
    abilityIndex: Int?,
): PlayerAction =
    if (abilityIndex == null) {
        PlayerAction.PassPriority
    } else {
        PlayerAction.CastSpell(cardId, abilityIndex)
    }
