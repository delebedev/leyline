package leyline.game.bundle

import forge.game.Game
import forge.game.phase.PhaseType
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.handoff.GameActionBridge.ActionOffer
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.SelectNPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationLossReason
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.SnapDeltaSynthesizer
import leyline.game.event.Zone
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.PlayerMapper
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ShouldStopEvaluator
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.BridgeMutations
import leyline.game.state.GameBridge
import leyline.game.state.StaleInstanceIdTransitionException
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds GRE message bundles for each flow milestone.
 *
 * Frame computation reads one snapshot, then ordering-sensitive bridge mutations and
 * the shared projection baseline commit through one seam. There is no Netty or mutable
 * handler state here. The shared [MessageCounter] advances atomically on each call.
 *
 * Captures a [GsmSnapshot] at entry; every stage reads from the snapshot.
 *
 * **Update types** (what the client does with each GSM):
 * - [GameStateUpdate.SendAndRecord] — checkpoint; client persists state.
 *   Always precedes [ActionsAvailableReq] at human decision points.
 * - [GameStateUpdate.SendHiFi] — animation-quality intermediate. AI actions,
 *   phase echoes, combat toggles. Client animates but doesn't save.
 * - [GameStateUpdate.Send] — speculative/transient. Targeting, selection
 *   prompts. Client may discard on undo/cancel.
 *
 * **pendingMessageCount:** when 1, tells the client another message follows
 * in the same logical batch (GSM + request pair). Client defers processing
 * until both arrive. Omit for standalone GSMs (AI actions, echoes).
 *
 * Naming: `xxxBundle` → [BundleResult] (multi-message). Standalone helpers
 * ([queuedGameState], [edictalPass]) return single [GREToClientMessage].
 */
@Suppress("LargeClass") // coherent unit; split assessed 2026-04-05, marginal leverage
class BundleBuilder(
    private val bridge: GameBridge,
    private val matchId: String,
    val seatId: Int,
    /**
     * Cursor this builder updates after each bundle. Defaults to
     * [GameBridge.bundleCursor] so the session builder and the
     * [leyline.game.GamePlayback] builder share one baseline; tests can inject an
     * isolated cursor if they don't want the bridge state affected.
     */
    val cursor: BundleCursor = bridge.bundleCursor,
) {
    private val log = LoggerFactory.getLogger(BundleBuilder::class.java)

    data class BundleResult(
        val messages: List<GREToClientMessage>,
        val actionOffers: List<ActionOffer> = emptyList(),
        val actionGameStateId: Int? = null,
    )

    private data class FrameInput(
        val state: StateFrameInput,
    )

    private data class FrameDiff(
        val gameStateId: Int,
        val snap: GsmSnapshot,
        val result: StateMapper.BuildResult,
        val events: FrameEventLog,
        val previousSnap: GsmSnapshot?,
        val stagedOrder: Boolean = false,
    )

    private fun frameInput(
        game: Game,
        counter: MessageCounter,
        revealForSeat: Int?,
        eventsOverride: FrameEventLog?,
        updateType: (GsmSnapshot, FrameEventLog) -> GameStateUpdate,
    ): FrameInput {
        val nextGs = counter.nextGsId()
        val snap = GsmSnapshot.capture(game, bridge, matchId, nextGs)
        val frameEvents = eventsOverride ?: bridge.closeBundleFrame(seatId)
        val previousSnap = cursor.lastSent
        val events =
            FrameEventLog(
                events = frameEvents.events + previousSnap?.let { SnapDeltaSynthesizer.synthesize(it, snap) }.orEmpty(),
                zoneMoves = frameEvents.zoneMoves,
            )
        bridge.invalidateAbilityRegistries(events.events)
        val effectFacts = bridge.materializeEffectProjectionFacts()
        return FrameInput(
            StateFrameInput(
                gameStateId = nextGs,
                snapshot = snap,
                previousSnapshot = previousSnap,
                events = events,
                promptFacts = bridge.materializePromptProjectionFacts(),
                updateType = updateType(snap, events),
                viewingSeatId = seatId,
                revealForSeat = revealForSeat,
                effectFacts = effectFacts,
            ),
        )
    }

    private fun compileFrame(input: FrameInput): StateMapper.BuildResult =
        StateMapper.buildDiff(
            input = input.state,
            matchId = matchId,
            bridge = bridge,
        )

    private fun buildFrameDiff(
        game: Game,
        counter: MessageCounter,
        revealForSeat: Int? = null,
        eventsOverride: FrameEventLog? = null,
        includePendingPlayerSubmittedTargets: Boolean = false,
        annotationRiders: (GsmSnapshot, FrameIdResolver) -> List<AnnotationInfo> = { _, _ -> emptyList() },
        updateType: (GsmSnapshot, FrameEventLog) -> GameStateUpdate,
    ): FrameDiff {
        val input = frameInput(game, counter, revealForSeat, eventsOverride, updateType)
        val pendingSubmittedTargets =
            if (includePendingPlayerSubmittedTargets) {
                cursor.pendingPSuT()
            } else {
                null
            }
        return finalizeFrameInput(input, emptyList(), pendingSubmittedTargets, annotationRiders = annotationRiders)
    }

    private fun finalizeFrameInput(
        input: FrameInput,
        riders: List<AnnotationInfo>,
        pendingSubmittedTargets: BundleCursor.PSuTPending? = null,
        annotationRiders: ((GsmSnapshot, FrameIdResolver) -> List<AnnotationInfo>)? = null,
    ): FrameDiff =
        synchronized(bridge.projectionLock) {
            finalizeFrameInputLocked(input, riders, pendingSubmittedTargets, annotationRiders)
        }

    private fun finalizeFrameInputLocked(
        input: FrameInput,
        riders: List<AnnotationInfo>,
        pendingSubmittedTargets: BundleCursor.PSuTPending?,
        annotationRiders: ((GsmSnapshot, FrameIdResolver) -> List<AnnotationInfo>)?,
    ): FrameDiff {
        repeat(MAX_ID_TRANSITION_RETRIES) { attempt ->
            try {
                val result =
                    bridge.ids.withTentativeState {
                        val draft = compileFrame(input)
                        val attemptRiders =
                            annotationRiders
                                ?.invoke(
                                    input.state.snapshot,
                                    checkNotNull(draft.annotationFrameDraft).idResolver,
                                )?.toMutableList()
                                ?: riders.toMutableList()
                        pendingSubmittedTargets?.let { pending ->
                            attemptRiders +=
                                AnnotationBuilder.playerSubmittedTargets(pending.spellInstanceId, pending.casterSeatId)
                        }
                        val finalized = finalizeStateFrame(draft, attemptRiders, pendingSubmittedTargets)
                        bridge.diffListener?.invoke(
                            input.state.previousSnapshot,
                            input.state.snapshot,
                            input.state.events,
                            input.state.gameStateId,
                            finalized.gsm,
                        )
                        commitProjection(finalized.projectionSnapshot, finalized.mutations, pendingSubmittedTargets)
                        finalized
                    }
                return FrameDiff(
                    input.state.gameStateId,
                    result.projectionSnapshot,
                    result,
                    input.state.events,
                    input.state.previousSnapshot,
                )
            } catch (stale: StaleInstanceIdTransitionException) {
                if (attempt == MAX_ID_TRANSITION_RETRIES - 1) throw stale
            }
        }
        error("unreachable")
    }

    internal fun finalizeStateFrame(
        draft: StateMapper.BuildResult,
        riders: List<AnnotationInfo>,
        pendingSubmittedTargets: BundleCursor.PSuTPending? = null,
    ): StateMapper.BuildResult {
        if (pendingSubmittedTargets == null) return draft.finalizeAnnotations(riders)

        return synchronized(cursor) {
            check(cursor.pendingPSuT() == pendingSubmittedTargets) {
                "Pending PlayerSubmittedTargets changed during frame assembly"
            }
            draft.finalizeAnnotations(riders)
        }
    }

    /**
     * Commits one successfully prepared projection at the path's existing commit point.
     *
     * Mutation application and baseline advancement stay adjacent under the caller's
     * existing ownership boundary. Diff paths commit before action or request assembly
     * so planned instance-id changes are visible to those builders. Engine playback
     * calls this while holding its queue lock, before enqueueing the completed batch.
     *
     * Lifecycle boundaries remain named exceptions: [leyline.match.MatchSession] seeds
     * mulligan and puzzle baselines, [leyline.match.MatchConnection] seeds the spectator
     * handshake, and `DebugServer` seeds replacement-game baselines.
     */
    private fun commitProjection(
        snap: GsmSnapshot,
        mutations: BridgeMutations?,
        pendingSubmittedTargets: BundleCursor.PSuTPending? = null,
    ) {
        synchronized(cursor) {
            pendingSubmittedTargets?.let { pending ->
                check(cursor.pendingPSuT() == pending) {
                    "Pending PlayerSubmittedTargets changed before projection commit"
                }
            }
            mutations?.let(bridge::applyMutations)
            cursor.lastSent = snap
            pendingSubmittedTargets?.let(cursor::consumePSuT)
        }
    }

    /**
     * Post-action state bundle:
     *   GRE 1: Diff GameStateMessage with embedded actions (only changed zones/objects)
     *   GRE 2: ActionsAvailableReq
     */
    fun postAction(
        game: Game,
        counter: MessageCounter,
        revealForSeat: Int? = null,
        priorityCandidates: PriorityActionCandidates? = null,
    ): BundleResult {
        val diff =
            buildFrameDiff(
                game,
                counter,
                revealForSeat = revealForSeat,
                includePendingPlayerSubmittedTargets = true,
            ) { snap, events ->
                if (isTurnOrTriggerDraw(events.events, snap, snap.phase.activePlayer)) {
                    GameStateUpdate.SendHiFi
                } else {
                    StateMapper.resolveUpdateType(snap, seatId)
                }
            }
        val nextGs = diff.gameStateId
        val snap = diff.snap
        val frame = GsmFrame.from(snap)
        // Build state first (without actions) — triggers instanceId realloc on zone transfers.
        // Then build actions so they reference the new (post-move) instanceIds.
        val result = diff.result
        val projection = ActionMapper.buildProjectionFromSnapshot(seatId, snap, bridge, priorityCandidates)
        val actions = projection.actions

        // PhaseOrStepModified is now emitted event-driven from GameEvent.PhaseChanged
        // in StateMapper Stage 2b — no injection needed here.

        val gs = GsmBuilder.embedActions(result.gsm, actions, frame, recipientSeatId = seatId)

        // Stop at ActionsAvailableReq for human-priority prompts. A trailing
        // empty GSM advances the visual state after the prompt and can clear
        // zone-cast affordances while the action is still available.
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                    it.gameStateMessage = gs
                },
            ) + coinFlipPromptMessages(diff.events.events, nextGs, counter) +
                listOf(
                    makeGRE(GREMessageType.ActionsAvailableReq_695e, nextGs, counter.nextMsgId()) {
                        it.actionsAvailableReq = actions
                        it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                    },
                )

        return BundleResult(messages, projection.offers, nextGs)
    }

    /**
     * State-only diff: Diff GameStateMessage without ActionsAvailableReq.
     * Used to show intermediate state (e.g. spell on stack) without
     * prompting the client for a response.
     */
    fun stateOnlyDiff(
        game: Game,
        counter: MessageCounter,
    ): BundleResult =
        synchronized(counter) {
            val diff =
                buildFrameDiff(game, counter, includePendingPlayerSubmittedTargets = true) { snap, _ ->
                    StateMapper.resolveUpdateType(snap, seatId)
                }
            val nextGs = diff.gameStateId
            val result = diff.result
            val gs = result.gsm

            // State-only updates still use the content GSM + echo envelope. Human-priority
            // postAction bundles stop at ActionsAvailableReq instead.
            val messages =
                listOf(
                    makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                        it.gameStateMessage = gs
                    },
                ) + coinFlipPromptMessages(diff.events.events, nextGs, counter) +
                    listOf(
                        buildEchoDiffGsm(counter, gs.update, previousGsId = gs.gameStateId),
                    )

            BundleResult(messages)
        }

    /**
     * Remote action diff: content GS Diff with SendHiFi, then a bare SendHiFi echo.
     *
     * Client expects a commit-frame echo after remote-seat content GSMs.
     * Both messages are standalone (no pendingMessageCount). The first carries
     * the state delta + naive actions; the second is a bare diff (empty
     * anns/pAnns/objects/zones with prevGsId chained to the content frame)
     * used for animation pacing.
     */
    fun remoteActionDiff(
        game: Game,
        counter: MessageCounter,
        turnStarted: Boolean = false,
        eventsOverride: FrameEventLog? = null,
    ): BundleResult =
        synchronized(counter) {
            val diff =
                buildFrameDiff(
                    game,
                    counter,
                    eventsOverride = eventsOverride,
                    includePendingPlayerSubmittedTargets = true,
                    annotationRiders = { snap, _ ->
                        if (turnStarted) {
                            listOf(AnnotationBuilder.newTurnStarted(snap.phase.activePlayer))
                        } else {
                            emptyList()
                        }
                    },
                ) { _, _ -> GameStateUpdate.SendHiFi }
            val nextGs = diff.gameStateId
            // Build state first (triggers instanceId realloc), then actions with new IDs
            val gsBase = diff.result.gsm
            // Naive actions: always show human's full hand (Cast/Play) regardless of phase.
            // Client expects human's potential actions embedded during AI turn.
            val actions = ActionMapper.buildNaiveActions(seatId, bridge)

            // Embed actions WITHOUT pendingMessageCount (no follow-up message expected)
            val gsBuilder = gsBase.toBuilder()
            for (action in actions.actionsList) {
                gsBuilder.addActions(
                    ActionInfo
                        .newBuilder()
                        .setSeatId(seatId)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
            val gs = gsBuilder.build()

            val content =
                makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                    it.gameStateMessage = gs
                }
            val echo = buildEchoDiffGsm(counter, GameStateUpdate.SendHiFi, previousGsId = nextGs)

            BundleResult(listOf(content) + coinFlipPromptMessages(diff.events.events, nextGs, counter) + listOf(echo))
        }

    /**
     * True when the only action available is Pass (no Cast, Play, Activate).
     * Used by [AutoPassEngine] on the session thread to skip empty priority
     * points — mainly on the opponent's turn.
     *
     * This is the **session-side** layer of a two-layer auto-pass system:
     *
     * 1. **Engine-side** — [leyline.bridge.PriorityActionCandidates.hasLegalNonManaAction] runs
     *    inside [PlayerController.chooseSpellAbilityToPlay] on the engine
     *    thread, own-turn only. When false, the engine auto-passes before the
     *    bridge round-trip even happens. The session thread never sees it.
     *
     * 2. **Session-side** (this) — checks the proto action list we already
     *    built. Covers opponent-turn priority and any case the engine-side
     *    skip didn't fire. No redundant Game queries needed.
     *
     * Stateless — lives in [Companion] so callers don't need an instance.
     */

    // --- Request builders (delegate to RequestBuilder) ---
    // MatchSession uses these instead of calling RequestBuilder directly,
    // keeping RequestBuilder as an internal dependency of the bundle layer.

    /** Build playable actions for a seat (with legality checks). */
    fun buildActions(priorityCandidates: PriorityActionCandidates? = null): ActionsAvailableReq {
        val game = bridge.getGame() ?: return ActionMapper.passOnlyActions()
        val snap = GsmSnapshot.capture(game, bridge, matchId, 0)
        return ActionMapper.buildProjectionFromSnapshot(seatId, snap, bridge, priorityCandidates).actions
    }

    /** Build a [SelectNReq] from a pending "choose cards" prompt. */
    fun buildSelectNReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        route: SelectNPromptRoute,
    ): SelectNReq = RequestBuilder.buildSelectNReq(prompt, bridge, route)

    /** Build an [OrderReq] from a pending ordering prompt. */
    fun buildOrderReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        kind: OrderRouteKind,
    ): Pair<OrderReq, Prompt> = RequestBuilder.buildOrderReq(prompt, bridge, kind)

    /** Build a [DeclareAttackersReq] listing legal attackers. */
    fun buildDeclareAttackersReq(): DeclareAttackersReq = RequestBuilder.buildDeclareAttackersReq(SeatId(seatId), bridge)

    /**
     * Phase transition bundle matching expected client-facing message pattern (5 messages):
     *   1. GS Diff SendHiFi (2x PhaseOrStepModified, gameInfo, players, actions)
     *   2. GS Diff SendHiFi echo (turnInfo + actions only)
     *   3. GS Diff SendAndRecord (1x PhaseOrStepModified, actions)
     *   4. PromptReq (promptId=37)
     *   5. ActionsAvailableReq (promptId=2)
     */
    fun phaseTransitionDiff(
        game: Game,
        counter: MessageCounter,
    ): BundleResult {
        val prevGs = counter.currentGsId()
        val nextGs = counter.nextGsId()
        val snap = GsmSnapshot.capture(game, bridge, matchId, nextGs)

        val frame = GsmFrame.from(snap)
        // Naive actions: always show human's full hand (Cast/Play) regardless of phase.
        // Client expects Cast/Play actions embedded regardless of current phase (cosmetic only;
        // actual priority gating uses ActionsAvailableReq sent when human gets priority).
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)
        val priorityProjection = ActionMapper.buildProjectionFromSnapshot(seatId, snap, bridge)

        // Message 1: SendHiFi with 2x PhaseOrStepModified + gameInfo
        val gs1 =
            GsmBuilder.buildTransitionState(
                nextGs,
                prevGameStateId = prevGs,
                matchId,
                bridge,
                frame,
                snap = snap,
                isStageTransition = true,
                actions = actions,
                actionSeatId = seatId,
            )
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gs1
            }

        // Message 2: SendHiFi echo (turnInfo + actions, no annotations)
        val msg1GsId = nextGs
        val echoGs = counter.nextGsId()
        val echoBuilder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(echoGs)
                .setPrevGameStateId(msg1GsId)
                .setTurnInfo(frame.turnInfo())
                .setUpdate(GameStateUpdate.SendHiFi)
        embedActions(echoBuilder, actions, seatId, pending = false)
        val msg2 =
            makeGRE(GREMessageType.GameStateMessage_695e, echoGs, counter.nextMsgId()) {
                it.gameStateMessage = echoBuilder.build()
            }

        // Message 3: SendAndRecord with 1x PhaseOrStepModified
        val commitGs = counter.nextGsId()
        val commitBuilder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(commitGs)
                .setPrevGameStateId(echoGs)
                .setTurnInfo(frame.turnInfo())
                .addAnnotations(frame.phaseAnnotation { bridge.nextAnnotationId() })
                .addAllTimers(PlayerMapper.buildTimers())
                .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(commitBuilder, actions, seatId)
        val msg3 =
            makeGRE(GREMessageType.GameStateMessage_695e, commitGs, counter.nextMsgId()) {
                it.gameStateMessage = commitBuilder.build()
            }

        // Message 4: PromptReq (promptId=37)
        val msg4 =
            makeGRE(GREMessageType.PromptReq, commitGs, counter.nextMsgId()) {
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.STARTING_PLAYER).build())
            }

        // Message 5: ActionsAvailableReq (promptId=2)
        val msg5 =
            makeGRE(GREMessageType.ActionsAvailableReq_695e, commitGs, counter.nextMsgId()) {
                it.actionsAvailableReq = priorityProjection.actions
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
            }

        commitProjection(snap, mutations = null)
        return BundleResult(listOf(msg1, msg2, msg3, msg4, msg5), priorityProjection.offers, commitGs)
    }

    /** Embed stripped-down actions from ActionsAvailableReq into a GSM builder. */
    private fun embedActions(
        builder: GameStateMessage.Builder,
        actions: ActionsAvailableReq,
        seatId: Int,
        pending: Boolean = true,
    ) {
        if (pending) builder.setPendingMessageCount(1)
        for (action in actions.actionsList) {
            builder.addActions(
                ActionInfo
                    .newBuilder()
                    .setSeatId(seatId)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }
    }

    /**
     * Echo-back bundle for iterative attacker toggle: thin Diff with base creature
     * objects + fresh DeclareAttackersReq.
     *
     * Echo objects carry no combat state; the refreshed DeclareAttackersReq carries
     * selectedDamageRecipient on currently selected attacker options.
     *
     * @param selectedAttackerIds instanceIds currently selected as attackers
     * @param allLegalAttackerIds all instanceIds eligible to attack (for deselect detection)
     */
    @Suppress("UnusedParameter")
    fun echoAttackersBundle(
        game: Game,
        counter: MessageCounter,
        selectedAttackerIds: List<Int>,
        allLegalAttackerIds: List<Int>,
        selectedAttackAlternatives: Map<Int, Int> = emptyMap(),
        selectedDamageRecipients: Map<Int, DamageRecipient> = emptyMap(),
    ): BundleResult =
        combatEchoBundle(game, counter, allLegalAttackerIds, GREMessageType.DeclareAttackersReq_695e) {
            val req =
                RequestBuilder.buildDeclareAttackersReq(
                    SeatId(seatId),
                    bridge,
                    committedAttackerIds = selectedAttackerIds.toSet(),
                    committedAttackAlternatives = selectedAttackAlternatives,
                    committedDamageRecipients = selectedDamageRecipients,
                )
            val configureRequest: (GREToClientMessage.Builder) -> Unit = {
                it.declareAttackersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
            }
            configureRequest
        }

    /**
     * Declare-attackers bundle: Diff (DeclareAttack step) + DeclareAttackersReq (prompt id=6).
     */
    fun declareAttackersBundle(
        game: Game,
        counter: MessageCounter,
        prebuiltReq: DeclareAttackersReq? = null,
    ): BundleResult {
        val diff = buildFrameDiff(game, counter) { snap, _ -> StateMapper.resolveUpdateType(snap, seatId) }
        val gs = diff.result.gsm
        val req = prebuiltReq ?: RequestBuilder.buildDeclareAttackersReq(SeatId(seatId), bridge)
        return promptRequestBundle(diff, counter, gs, GREMessageType.DeclareAttackersReq_695e) {
            it.declareAttackersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
        }
    }

    /**
     * Echo-back for iterative blocker toggle: thin Diff GSM with provisional
     * blocker state on toggled creatures + fresh DeclareBlockersReq.
     *
     * Same pattern as [echoAttackersBundle] — engine's combat object doesn't
     * track provisional blocker selections during iterative declaration.
     */
    fun echoBlockersBundle(
        game: Game,
        counter: MessageCounter,
        blockAssignments: Map<Int, Int>, // blockerInstanceId → attackerInstanceId
    ): BundleResult =
        combatEchoBundle(game, counter, blockAssignments.keys, GREMessageType.DeclareBlockersReq_695e) {
            // Re-prompt with assigned blockers' attackerInstanceIds cleared
            val req =
                RequestBuilder.buildDeclareBlockersReq(
                    game,
                    SeatId(seatId),
                    bridge,
                    blockerAssignments = blockAssignments,
                )
            val configureRequest: (GREToClientMessage.Builder) -> Unit = {
                it.declareBlockersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
            }
            configureRequest
        }

    private fun combatEchoBundle(
        game: Game,
        counter: MessageCounter,
        includedInstanceIds: Collection<Int>,
        requestType: GREMessageType,
        buildRequestConfig: () -> (GREToClientMessage.Builder) -> Unit,
    ): BundleResult {
        val nextGs = counter.nextGsId()
        val player = bridge.getPlayer(SeatId(seatId)) ?: return BundleResult(emptyList())
        val snap = GsmSnapshot.capture(game, bridge, matchId, nextGs)

        // Echo objects carry no combat state; selection lives in the re-prompt.
        val objects = mutableListOf<GameObjectInfo>()
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            val fid = ForgeCardId(card.id)
            val iid = bridge.getOrAllocInstanceId(fid).value
            if (iid !in includedInstanceIds) continue
            val cardSnap = snap.objects[fid] ?: continue

            objects.add(
                ObjectMapper.buildProvisionalCombatObject(
                    cardSnap,
                    iid,
                    ZoneIds.BATTLEFIELD,
                    ownerSeatId = seatId,
                    cardProto = bridge.cardProto,
                    parentLinkage = snap.boundCards[fid]?.parentLinkage,
                ),
            )
        }

        // Cumulative turn-level actions (Cast, Play, ActivateMana, Activate).
        // Client expects echo GSMs to include this running log.
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)

        val gsmBuilder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(nextGs)
                .addAllGameObjects(objects)
                .setPrevGameStateId(nextGs - 1)
                .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(gsmBuilder, actions, seatId, pending = false)

        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gsmBuilder.build()
            }

        val configureRequest = buildRequestConfig()
        val msg2 = makeGRE(requestType, nextGs, counter.nextMsgId(), configureRequest)

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * Declare-blockers bundle: Diff (DeclareBlock step) + DeclareBlockersReq (prompt id=7).
     */
    fun declareBlockersBundle(
        game: Game,
        counter: MessageCounter,
    ): BundleResult {
        val diff = buildFrameDiff(game, counter) { snap, _ -> StateMapper.resolveUpdateType(snap, seatId) }
        val gs = diff.result.gsm
        val req = RequestBuilder.buildDeclareBlockersReq(game, SeatId(seatId), bridge)
        return promptRequestBundle(diff, counter, gs, GREMessageType.DeclareBlockersReq_695e) {
            it.declareBlockersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
        }
    }

    /**
     * Select-targets bundle: GameState + SelectTargetsReq.
     *
     * Builds the diff **first** (which triggers instanceId reallocs for zone
     * transfers like Hand→Stack), then builds the SelectTargetsReq so that
     * `sourceId` and target instanceIds reflect the post-realloc state.
     * Without this ordering, `sourceId` would reference a retired instanceId
     * and the client wouldn't draw the targeting arrow.
     *
     * Sets `allowCancel=Abort` and `allowUndo=true` on the GRE wrapper
     * (client shows Cancel button and allows undo during targeting).
     */
    fun selectTargetsBundle(
        game: Game,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
    ): BundleResult {
        val diff =
            buildFrameDiff(
                game,
                counter,
                annotationRiders = { _, frameIds ->
                    prompt.request.sourceEntityId?.let { sourceEntityId ->
                        listOf(
                            AnnotationBuilder.playerSelectingTargets(
                                frameIds.cardIid(ForgeCardId(sourceEntityId)),
                                SeatId(seatId),
                            ),
                        )
                    } ?: emptyList()
                },
            ) { _, _ -> GameStateUpdate.Send }
        val gs = diff.result.gsm
        // Build SelectTargetsReq AFTER diff so sourceId uses post-realloc instanceIds
        val req = RequestBuilder.buildSelectTargetsReq(prompt, bridge, seatId)
        return promptRequestBundle(diff, counter, gs, GREMessageType.SelectTargetsReq_695e) {
            it.selectTargetsReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_TARGETS).build())
            it.allowCancel = AllowCancel.Abort
            it.allowUndo = true
        }
    }

    /**
     * SelectN bundle: GameState + SelectNReq.
     * Used for "choose N cards" prompts (discard, sacrifice, etc.).
     */
    fun selectNBundle(
        game: Game,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
        route: SelectNPromptRoute,
        envelopeForReq: (SelectNReq) -> SelectNEnvelope,
    ): BundleResult {
        val diff = buildFrameDiff(game, counter) { _, _ -> GameStateUpdate.Send }
        return selectNBundleFromDiff(diff, counter, envelopeForReq(buildSelectNReq(prompt, route)))
    }

    fun selectNBundle(
        game: Game,
        counter: MessageCounter,
        envelope: SelectNEnvelope,
    ): BundleResult {
        val diff = buildFrameDiff(game, counter) { _, _ -> GameStateUpdate.Send }
        return selectNBundleFromDiff(diff, counter, envelope)
    }

    private fun selectNBundleFromDiff(
        diff: FrameDiff,
        counter: MessageCounter,
        envelope: SelectNEnvelope,
    ): BundleResult {
        val snap = diff.snap
        val baseGs =
            when (envelope.gameStateAugmentation) {
                SelectNEnvelope.GameStateAugmentation.LookAndPick ->
                    attachLookAndPickGameObjects(diff.result.gsm, envelope.req, snap)
                SelectNEnvelope.GameStateAugmentation.LearnLesson ->
                    attachLearnLessonGameObjects(diff.result.gsm, envelope.req, snap)
                SelectNEnvelope.GameStateAugmentation.None -> diff.result.gsm
            }
        val gs =
            baseGs
                .toBuilder()
                .setPendingMessageCount(1)
                .build()
        return promptRequestBundle(diff, counter, gs, GREMessageType.SelectNreq) {
            it.selectNReq = envelope.req
            it.setPrompt(envelope.prompt)
            if (envelope.allowCancel != AllowCancel.None_a526) {
                it.allowCancel = envelope.allowCancel
            }
        }
    }

    /** Order bundle: GameState + OrderReq. */
    fun orderBundle(
        game: Game,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
        kind: OrderRouteKind,
    ): BundleResult {
        val diff = buildOrderFrame(game, counter, prompt)
        val snap = diff.snap
        val (req, promptProto) = buildOrderReq(prompt, kind)
        val baseOrderGsm =
            if (diff.stagedOrder) {
                diff.result.gsm
            } else {
                attachOrderGameObjects(diff.result.gsm, req, snap)
            }
        val gs =
            baseOrderGsm
                .toBuilder()
                .setPendingMessageCount(1)
                .build()
        return promptRequestBundle(
            diff,
            counter,
            gs,
            GREMessageType.OrderReq_695e,
        ) {
            it.orderReq = req
            it.setPrompt(promptProto)
            it.allowCancel = AllowCancel.No_a526
            if (kind == OrderRouteKind.Top) {
                it.allowUndo = true
            }
        }
    }

    private fun buildOrderFrame(
        game: Game,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
    ): FrameDiff =
        synchronized(bridge.projectionLock) {
            buildOrderFrameLocked(game, counter, prompt)
        }

    private fun buildOrderFrameLocked(
        game: Game,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
    ): FrameDiff {
        val input = frameInput(game, counter, revealForSeat = null, eventsOverride = null) { _, _ -> GameStateUpdate.Send }
        val pendingMove = pendingOrderZoneMove(prompt)
        repeat(MAX_ID_TRANSITION_RETRIES) { attempt ->
            try {
                return bridge.ids.withTentativeState {
                    bridge.revealProxies.withTentativeState {
                        val draft = compileFrame(input)
                        val stagedMove = stagePendingOrderZoneMove(draft.gsm, input.state.snapshot, prompt, pendingMove)
                        val stagedGsm = stagedMove?.gsm ?: draft.gsm
                        val mutations =
                            draft.mutations.copy(
                                instanceIdTransition = bridge.ids.tentativeTransition(),
                                revealTransition = bridge.revealProxies.tentativeTransition(),
                                retiredIds = draft.mutations.retiredIds + (stagedMove?.retiredIds ?: emptyList()),
                                zoneRecordings = draft.mutations.zoneRecordings + (stagedMove?.zoneRecordings ?: emptyList()),
                            )
                        val finalized = finalizeStateFrame(draft.copy(gsm = stagedGsm, mutations = mutations), emptyList())
                        bridge.diffListener?.invoke(
                            input.state.previousSnapshot,
                            input.state.snapshot,
                            input.state.events,
                            input.state.gameStateId,
                            finalized.gsm,
                        )
                        commitProjection(stagedMove?.snap ?: finalized.projectionSnapshot, finalized.mutations)
                        pendingMove?.let {
                            bridge.promptBridge(SeatId(seatId)).acknowledgePendingOrderZoneMove(it)
                        }
                        FrameDiff(
                            input.state.gameStateId,
                            finalized.projectionSnapshot,
                            finalized,
                            input.state.events,
                            input.state.previousSnapshot,
                            stagedMove != null,
                        )
                    }
                }
            } catch (stale: StaleInstanceIdTransitionException) {
                if (attempt == MAX_ID_TRANSITION_RETRIES - 1) throw stale
            }
        }
        error("unreachable")
    }

    private fun promptRequestBundle(
        diff: FrameDiff,
        counter: MessageCounter,
        gameStateMessage: GameStateMessage,
        requestType: GREMessageType,
        configureRequest: (GREToClientMessage.Builder) -> Unit,
    ): BundleResult {
        val nextGs = diff.gameStateId
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gameStateMessage
            }
        val msg2 = makeGRE(requestType, nextGs, counter.nextMsgId(), configureRequest)

        return BundleResult(listOf(msg1, msg2))
    }

    private data class StagedOrderMove(
        val gsm: GameStateMessage,
        val snap: GsmSnapshot,
        val retiredIds: List<InstanceId>,
        val zoneRecordings: List<Pair<InstanceId, Int>>,
    )

    private data class StagedMovedCard(
        val forgeCardId: ForgeCardId,
        val oldId: InstanceId,
        val newId: InstanceId,
    )

    private fun pendingOrderZoneMove(prompt: InteractivePromptBridge.PendingPrompt): InteractivePromptBridge.PendingOrderZoneMove? {
        val candidateFids =
            prompt.request.candidateRefs
                .filter { it.isCard() }
                .map { ForgeCardId(it.entityId) }
        if (candidateFids.isEmpty()) return null
        return bridge.promptBridge(SeatId(seatId)).findPendingOrderZoneMove(SeatId(seatId), candidateFids)
    }

    private fun stagePendingOrderZoneMove(
        gsm: GameStateMessage,
        snap: GsmSnapshot,
        prompt: InteractivePromptBridge.PendingPrompt,
        move: InteractivePromptBridge.PendingOrderZoneMove?,
    ): StagedOrderMove? {
        move ?: return null
        val sourceZoneId = ZoneIds.handOf(move.seatId)
        val destZoneId = ZoneIds.libraryOf(move.seatId)

        val moved =
            move.forgeCardIds.map { fid ->
                val realloc = bridge.ids.realloc(fid)
                StagedMovedCard(fid, realloc.old, realloc.new)
            }
        val stagedSnap = stagedOrderSnapshot(snap, move, sourceZoneId, destZoneId)
        val sourceIid = prompt.request.sourceEntityId?.let { bridge.getOrAllocInstanceId(ForgeCardId(it)) } ?: InstanceId(0)
        return StagedOrderMove(
            gsm = stagedOrderGsm(gsm, snap, stagedSnap, move, moved, sourceIid, sourceZoneId, destZoneId),
            snap = stagedSnap,
            retiredIds = moved.map { it.oldId },
            zoneRecordings = moved.map { it.newId to destZoneId },
        )
    }

    private fun stagedOrderSnapshot(
        snap: GsmSnapshot,
        move: InteractivePromptBridge.PendingOrderZoneMove,
        sourceZoneId: Int,
        destZoneId: Int,
    ): GsmSnapshot {
        val movedSet = move.forgeCardIds.toSet()
        val zones = snap.zones.toMutableMap()
        val source = zones[sourceZoneId]
        val dest = zones[destZoneId]
        if (source != null) {
            zones[sourceZoneId] = source.copy(contents = source.contents.filterNot { it in movedSet })
        }
        if (dest != null) {
            val remaining = dest.contents.filterNot { it in movedSet }
            zones[destZoneId] =
                dest.copy(contents = if (move.putOnTop) move.forgeCardIds + remaining else remaining + move.forgeCardIds)
        }
        return GsmSnapshot(
            matchId = snap.matchId,
            gameStateId = snap.gameStateId,
            seats = snap.seats,
            zones = zones,
            boundCards = snap.boundCards,
            stack = snap.stack,
            phase = snap.phase,
            combat = snap.combat,
            abilityWordEntries = snap.abilityWordEntries,
            pendingTriggers = snap.pendingTriggers,
            persistentAnnotationState = snap.persistentAnnotationState,
            capturedAt = snap.capturedAt,
            dayTime = snap.dayTime,
            activePlayerSpellsCastThisTurn = snap.activePlayerSpellsCastThisTurn,
        )
    }

    private fun stagedOrderGsm(
        gsm: GameStateMessage,
        snap: GsmSnapshot,
        stagedSnap: GsmSnapshot,
        move: InteractivePromptBridge.PendingOrderZoneMove,
        moved: List<StagedMovedCard>,
        sourceIid: InstanceId,
        sourceZoneId: Int,
        destZoneId: Int,
    ): GameStateMessage {
        val movedOldIds = moved.map { it.oldId.value }.toSet()
        val movedNewIds = moved.map { it.newId.value }.toSet()
        val builder = gsm.toBuilder()
        val replacementZones =
            listOfNotNull(
                stagedSnap.zones[sourceZoneId]?.let { zoneInfoFor(it) },
                stagedSnap.zones[destZoneId]?.let { zoneInfoFor(it) },
                limboZoneInfo(moved.map { it.oldId }),
            )
        builder.clearZones()
        builder.addAllZones(
            (gsm.zonesList.filterNot { it.zoneId in setOf(sourceZoneId, destZoneId, ZoneIds.LIMBO) } + replacementZones)
                .sortedBy { it.zoneId },
        )
        builder.clearGameObjects()
        builder.addAllGameObjects(gsm.gameObjectsList.filterNot { it.instanceId in movedOldIds || it.instanceId in movedNewIds })
        moved.forEach { staged ->
            val cardSnap = snap.objects[staged.forgeCardId] ?: return@forEach
            builder.addGameObjects(orderMoveObject(cardSnap, staged.oldId, ZoneIds.LIMBO, move.seatId.value))
            builder.addGameObjects(orderMoveObject(cardSnap, staged.newId, destZoneId, move.seatId.value))
            builder.addAnnotations(
                AnnotationBuilder
                    .objectIdChanged(staged.oldId, staged.newId, sourceIid),
            )
            builder.addAnnotations(
                AnnotationBuilder
                    .zoneTransfer(staged.newId, sourceZoneId, destZoneId, "Put", affectorId = sourceIid),
            )
        }
        return builder.build()
    }

    private fun zoneInfoFor(zone: leyline.game.snapshot.ZoneSnapshot): ZoneInfo {
        val builder =
            ZoneInfo
                .newBuilder()
                .setZoneId(zone.id)
                .setType(zone.type)
                .setVisibility(if (zone.type == ZoneType.Library) Visibility.Hidden else zone.visibility)
        zone.owner?.let { owner ->
            builder.setOwnerSeatId(owner.value)
            if (zone.type == ZoneType.Hand || zone.type == ZoneType.Sideboard) {
                builder.addViewers(owner.value)
            }
        }
        zone.contents.forEach { fid -> builder.addObjectInstanceIds(bridge.getOrAllocInstanceId(fid).value) }
        return builder.build()
    }

    private fun limboZoneInfo(extraIds: List<InstanceId> = emptyList()): ZoneInfo =
        ZoneInfo
            .newBuilder()
            .setZoneId(ZoneIds.LIMBO)
            .setType(ZoneType.Limbo)
            .setVisibility(Visibility.Public)
            .addAllObjectInstanceIds((bridge.getLimboInstanceIds() + extraIds).distinct().map { it.value })
            .build()

    private fun orderMoveObject(
        cardSnap: CardSnapshot,
        instanceId: InstanceId,
        zoneId: Int,
        ownerSeatId: Int,
    ): GameObjectInfo =
        ObjectMapper
            .buildFromSnapshot(
                cardSnap = cardSnap,
                instanceId = instanceId.value,
                zoneId = zoneId,
                ownerSeatId = ownerSeatId,
                cardProto = bridge.cardProto,
                visibility = Visibility.Private,
            ).toBuilder()
            .addViewers(ownerSeatId)
            .build()

    private fun attachOrderGameObjects(
        gsm: GameStateMessage,
        req: OrderReq,
        snap: GsmSnapshot,
    ): GameStateMessage {
        if (req.idsList.isEmpty()) return gsm

        val gsBuilder = gsm.toBuilder()
        val existingByIid = gsBuilder.gameObjectsList.withIndex().associate { (idx, obj) -> obj.instanceId to idx }
        for (iid in req.idsList) {
            val forgeCardId = bridge.getForgeCardId(InstanceId(iid)) ?: continue
            val cardSnap = snap.objects[forgeCardId] ?: continue
            val zone = snap.zones.values.firstOrNull { forgeCardId in it.contents } ?: continue
            val obj =
                ObjectMapper
                    .buildFromSnapshot(
                        cardSnap = cardSnap,
                        instanceId = iid,
                        zoneId = zone.id,
                        ownerSeatId = zone.owner?.value ?: seatId,
                        cardProto = bridge.cardProto,
                        visibility = Visibility.Private,
                    ).toBuilder()
                    .addViewers(seatId)
                    .build()
            val existingIdx = existingByIid[iid]
            if (existingIdx != null) {
                gsBuilder.setGameObjects(existingIdx, obj)
            } else {
                gsBuilder.addGameObjects(obj)
            }
        }
        return gsBuilder.build()
    }

    /**
     * Look-and-pick GSM augmentation. Adds full [GameObjectInfo] entries for the
     * SelectN candidate iids with `visibility = Private, viewers = [seatId]`,
     * keeping them in the chooser's library zone.
     *
     * Required because the client renders the SelectN panel from
     * [GameObjectInfo] entries, not from the [SelectNReq.ids] list alone. With
     * the candidates' iids in the library but no per-iid object data sent, the
     * panel comes through blank. Adding the entries with the chooser as the sole
     * `viewer` reveals the cards to the picking player without leaking them to
     * the opponent.
     *
     * Routes through [ObjectMapper.buildFromSnapshot] so the canonical card →
     * GameObjectInfo pipeline stays the single source of truth (P/T,
     * extrinsic keywords, attachment state, etc.). The only override on top
     * is `addViewers(seatId)`.
     */
    private fun attachLookAndPickGameObjects(
        gsm: GameStateMessage,
        req: SelectNReq,
        snap: GsmSnapshot,
    ): GameStateMessage {
        if (req.idsList.isEmpty()) return gsm
        val gsBuilder = gsm.toBuilder()
        val libraryZoneId = ZoneIds.libraryOf(seatId)
        val existingByIid = gsBuilder.gameObjectsList.withIndex().associate { (idx, obj) -> obj.instanceId to idx }
        for (iid in req.idsList) {
            val forgeCardId =
                bridge.getForgeCardId(InstanceId(iid)) ?: run {
                    log.warn("attachLookAndPickGameObjects: no ForgeCardId for iid={}", iid)
                    continue
                }
            val cardSnap =
                snap.objects[forgeCardId] ?: run {
                    log.warn(
                        "attachLookAndPickGameObjects: no CardSnapshot for forgeCardId={} iid={}",
                        forgeCardId.value,
                        iid,
                    )
                    continue
                }
            val obj =
                ObjectMapper
                    .buildFromSnapshot(
                        cardSnap = cardSnap,
                        instanceId = iid,
                        zoneId = libraryZoneId,
                        ownerSeatId = seatId,
                        cardProto = bridge.cardProto,
                        visibility = Visibility.Private,
                    ).toBuilder()
                    .addViewers(seatId)
                    .build()
            val existingIdx = existingByIid[iid]
            if (existingIdx != null) {
                gsBuilder.setGameObjects(existingIdx, obj)
            } else {
                gsBuilder.addGameObjects(obj)
            }
        }
        return gsBuilder.build()
    }

    /**
     * Learn choices can include sideboard Lessons whose object data was not part
     * of the latest diff. Attach the selectable cards in their current zones so
     * the SelectN panel has renderable card objects.
     */
    private fun attachLearnLessonGameObjects(
        gsm: GameStateMessage,
        req: SelectNReq,
        snap: GsmSnapshot,
    ): GameStateMessage {
        if (req.idsList.isEmpty()) return gsm
        val gsBuilder = gsm.toBuilder()
        val existingByIid = gsBuilder.gameObjectsList.withIndex().associate { (idx, obj) -> obj.instanceId to idx }
        for (iid in req.idsList) {
            val forgeCardId =
                bridge.getForgeCardId(InstanceId(iid)) ?: run {
                    log.warn("attachLearnLessonGameObjects: no ForgeCardId for iid={}", iid)
                    continue
                }
            val cardSnap =
                snap.objects[forgeCardId] ?: run {
                    log.warn(
                        "attachLearnLessonGameObjects: no CardSnapshot for forgeCardId={} iid={}",
                        forgeCardId.value,
                        iid,
                    )
                    continue
                }
            val zone = snap.zones.values.firstOrNull { forgeCardId in it.contents }
            val obj =
                ObjectMapper
                    .buildFromSnapshot(
                        cardSnap = cardSnap,
                        instanceId = iid,
                        zoneId = zone?.id ?: ZoneIds.sideboardOf(seatId),
                        ownerSeatId = zone?.owner?.value ?: seatId,
                        cardProto = bridge.cardProto,
                        visibility = Visibility.Private,
                    ).toBuilder()
                    .addViewers(seatId)
                    .build()
            val existingIdx = existingByIid[iid]
            if (existingIdx != null) {
                gsBuilder.setGameObjects(existingIdx, obj)
            } else {
                gsBuilder.addGameObjects(obj)
            }
        }
        return gsBuilder.build()
    }

    /**
     * CastingTimeOptions bundle: GameState + CastingTimeOptionsReq.
     * Used for modal ETB/cast prompts (Charming Prince, Goblin Surprise, etc.).
     *
     * Sends a GSM diff first (state may have changed during trigger/resolution),
     * followed by CastingTimeOptionsReq with the ModalReq payload. Sets
     * allowCancel=Abort and allowUndo=true (client shows Cancel button).
     */

    /**
     * @param sourceCardInstanceId instanceId of the source card (for ability parentId).
     *   Null for spell-time modals where the card itself is on the stack.
     * @param sourceCardGrpId grpId of the source card (for ability objectSourceGrpId).
     *   Null for spell-time modals.
     */
    fun castingTimeOptionsBundle(
        game: Game,
        counter: MessageCounter,
        req: CastingTimeOptionsReq,
        sourceCardInstanceId: Int? = null,
        sourceCardGrpId: Int? = null,
    ): BundleResult {
        val diff = buildFrameDiff(game, counter) { _, _ -> GameStateUpdate.Send }
        val gsResult = diff.result
        val gsBuilder =
            gsResult.gsm
                .toBuilder()
                .setPendingMessageCount(1)

        // Synthesize the ability game object on the stack for ETB modals.
        // Forge adds the trigger to its stack AFTER mode choice (PlaySpellAbility line 733),
        // but the client needs the ability visible in the GSM to render the modal dialog.
        // Only inject when sourceCardInstanceId is set (triggered ability path).
        // Spell-time modals (kicker, spell modals) don't need this.
        if (sourceCardInstanceId != null && req.castingTimeOptionReqCount > 0) {
            val cto = req.getCastingTimeOptionReq(0)
            val abilityIid = cto.affectedId
            val abilityGrpId = cto.grpId
            if (abilityIid > 0 && abilityGrpId > 0) {
                // Only inject if not already present (e.g. spell-time modals where card is on stack)
                val alreadyPresent = gsBuilder.gameObjectsList.any { it.instanceId == abilityIid }
                if (!alreadyPresent) {
                    val abilityBuilder =
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(abilityIid)
                            .setGrpId(abilityGrpId)
                            .setType(GameObjectType.Ability)
                            .setZoneId(ZoneIds.STACK)
                            .setVisibility(Visibility.Public)
                            .setOwnerSeatId(seatId)
                            .setControllerSeatId(seatId)
                    if (sourceCardGrpId != null) {
                        abilityBuilder.setObjectSourceGrpId(sourceCardGrpId)
                    } else {
                        abilityBuilder.setObjectSourceGrpId(abilityGrpId)
                    }
                    // sourceCardInstanceId is non-null here — outer `if` on line 694 guarded it.
                    abilityBuilder.setParentId(sourceCardInstanceId)
                    val abilityObj = abilityBuilder.build()
                    gsBuilder.addGameObjects(abilityObj)

                    // Add to stack zone (create if absent in the diff)
                    val stackIdx = gsBuilder.zonesList.indexOfFirst { it.type == ZoneType.Stack }
                    if (stackIdx >= 0) {
                        val updated =
                            gsBuilder
                                .getZones(stackIdx)
                                .toBuilder()
                                .addObjectInstanceIds(abilityIid)
                                .build()
                        gsBuilder.setZones(stackIdx, updated)
                    } else {
                        gsBuilder.addZones(
                            ZoneInfo
                                .newBuilder()
                                .setZoneId(ZoneIds.STACK)
                                .setType(ZoneType.Stack)
                                .setVisibility(Visibility.Public)
                                .addObjectInstanceIds(abilityIid)
                                .build(),
                        )
                    }
                }
            }
        }

        val gs = gsBuilder.build()
        return promptRequestBundle(diff, counter, gs, GREMessageType.CastingTimeOptionsReq_695e) {
            it.castingTimeOptionsReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.CASTING_TIME_OPTIONS).build())
            it.allowCancel = AllowCancel.Abort
            it.allowUndo = true
        }
    }

    /**
     * PayCosts bundle: GameState + PayCostsReq.
     * Tells the client to show its native cost-selection UI (mana source
     * payment, sacrifice, exile-from-graveyard additional costs, convoke).
     *
     * Merges any [promptPersistentAnnotations] not already present in the
     * frame diff's GSM, so the prompt carries pAnns the client needs to
     * render the candidates (e.g. convoke tap counts) even when the diff
     * itself wouldn't have emitted them this tick.
     *
     * The client responds with PerformActionResp (already handled).
     */
    fun payCostsBundle(
        game: Game,
        counter: MessageCounter,
        req: PayCostsReq,
        prompt: Prompt? = null,
        promptPersistentAnnotations: List<AnnotationInfo> = emptyList(),
    ): BundleResult {
        val diff = buildFrameDiff(game, counter) { _, _ -> GameStateUpdate.Send }
        val promptOnlyPersistentAnnotations =
            promptPersistentAnnotations.filterNot { extra ->
                diff.result.gsm.persistentAnnotationsList
                    .any { it == extra }
            }
        val gs =
            if (promptOnlyPersistentAnnotations.isEmpty()) {
                diff.result.gsm
            } else {
                diff.result.gsm
                    .toBuilder()
                    .addAllPersistentAnnotations(promptOnlyPersistentAnnotations)
                    .build()
            }
        return promptRequestBundle(diff, counter, gs, GREMessageType.PayCostsReq_695e) {
            it.payCostsReq = req
            it.setPrompt(prompt ?: Prompt.newBuilder().setPromptId(PromptIds.PAY_COSTS).build())
            // Without these two flags the client renders the cost-selection
            // picker but treats every card as non-clickable (greyed out).
            // Matches the canonical envelope for non-mana-payment costs
            // (sacrifice, exile-from-grave additional cost).
            it.allowCancel = AllowCancel.Abort
            it.allowUndo = true
        }
    }

    /**
     * Wrap a GameStateMessage as QueuedGameStateMessage (type 51) for opponent during prompts.
     */
    fun queuedGameState(
        gameState: GameStateMessage,
        counter: MessageCounter,
    ): GREToClientMessage =
        makeGRE(GREMessageType.QueuedGameStateMessage, counter.currentGsId(), counter.nextMsgId()) {
            it.gameStateMessage = gameState
        }

    /**
     * Server-forced pass (EdictalMessage). Tells the client "I'm passing priority for seat X".
     * Breaks the client out of autoPassPriority mode so it re-renders action buttons.
     */
    fun edictalPass(counter: MessageCounter): BundleResult {
        val edictal =
            EdictalMessage
                .newBuilder()
                .setEdictMessage(
                    ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.PerformActionResp_097b)
                        .setSystemSeatId(seatId)
                        .setPerformActionResp(
                            PerformActionResp
                                .newBuilder()
                                .addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                        ),
                ).build()
        val msg =
            makeGRE(GREMessageType.EdictalMessage_695e, counter.currentGsId(), counter.nextMsgId()) {
                it.edictalMessage = edictal
            }
        return BundleResult(listOf(msg))
    }

    /**
     * Game-over sequence: 3x GS Diff + IntermissionReq.
     * Pure proto construction — no bridge or game engine access.
     *
     * Protocol pattern:
     * - gs1: GameInfo(stage=GameOver, matchState=GameComplete, 1 result scope=Game),
     *        players with PendingLoss, teams, LossOfGame annotation (if lethal)
     * - gs2: GameInfo(stage=GameOver, matchState=MatchComplete, 2 results Game+Match)
     * - gs3: bare diff with pendingMessageCount=1
     * - IntermissionReq: options, intermissionPrompt(27) with WinningTeamId param
     *
     * @param reason Game_ae0a for natural game end, Concede for concession
     * @param losingPlayerSeatId seat of the losing player (for LossOfGame annotation)
     * @param lossReason wire-level loss reason for the LossOfGame annotation
     */
    fun gameOverBundle(
        winningTeam: Int,
        counter: MessageCounter,
        reason: ResultReason = ResultReason.Game_ae0a,
        losingPlayerSeatId: Int = 0,
        lossReason: AnnotationLossReason = AnnotationLossReason.LifeTotal,
    ): BundleResult {
        val prevGsId = counter.currentGsId()
        val losingTeam = if (winningTeam == 1) 2 else 1

        // Shared GameInfo fields matching initial bundle (StateMapper.buildFromSnapshot)
        fun baseGameInfo() =
            GameInfo
                .newBuilder()
                .setMatchID(matchId)
                .setGameNumber(1)
                .setStage(GameStage.GameOver)
                .setType(GameType.Duel)
                .setVariant(GameVariant.Normal)
                .setMatchWinCondition(MatchWinCondition.SingleElimination)
                .setSuperFormat(SuperFormat.Constructed)
                .setMulliganType(MulliganType.London)
                .setDeckConstraintInfo(
                    DeckConstraintInfo
                        .newBuilder()
                        .setMinDeckSize(60)
                        .setMaxDeckSize(250)
                        .setMaxSideboardSize(15),
                )

        val gameResult =
            ResultSpec
                .newBuilder()
                .setScope(MatchScope.Game_a146)
                .setResult(ResultType.WinLoss)
                .setWinningTeamId(winningTeam)
                .setReason(reason)

        val matchResult =
            ResultSpec
                .newBuilder()
                .setScope(MatchScope.Match)
                .setResult(ResultType.WinLoss)
                .setWinningTeamId(winningTeam)
                .setReason(reason)

        // gs1: GameComplete with Game result only, PendingLoss players
        val gs1Info =
            baseGameInfo()
                .setMatchState(MatchState.GameComplete)
                .addResults(gameResult)
        val gs1Id = counter.nextGsId()
        val gs1 =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gs1Id)
                .setPrevGameStateId(prevGsId)
                .setGameInfo(gs1Info)
                .setUpdate(GameStateUpdate.SendAndRecord)
        // Teams with PendingLoss for losing team
        gs1.addTeams(
            TeamInfo
                .newBuilder()
                .setId(losingTeam)
                .addPlayerIds(losingPlayerSeatId)
                .setStatus(TeamStatus.PendingLoss_a458),
        )
        // Players: loser with full state (lifeTotal, maxHandSize, etc.) + PendingLoss status
        val game = bridge.getGame()
        if (game != null) {
            val gameOverSnap = GsmSnapshot.capture(game, bridge, matchId, 0)
            val loserInfo =
                PlayerMapper
                    .buildFromSnapshot(gameOverSnap, losingPlayerSeatId)
                    .toBuilder()
                    .setStatus(PlayerStatus.PendingLoss_a1c6)
            gs1.addPlayers(loserInfo)
        }
        // Timers — inactivity timer on gs1
        gs1.addAllTimers(PlayerMapper.buildTimers())
        // LossOfGame annotation
        if (losingPlayerSeatId != 0) {
            gs1.addAnnotations(AnnotationBuilder.lossOfGame(SeatId(losingPlayerSeatId), lossReason))
        }

        // gs2: MatchComplete with both Game + Match results
        val gs2Info =
            baseGameInfo()
                .setMatchState(MatchState.MatchComplete)
                .addResults(gameResult)
                .addResults(matchResult)
        val gs2Id = counter.nextGsId()
        val gs2 =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gs2Id)
                .setPrevGameStateId(gs1Id)
                .setGameInfo(gs2Info)
                .setUpdate(GameStateUpdate.SendAndRecord)

        // gs3: bare diff with pendingMessageCount=1 (IntermissionReq follows)
        val gs3Id = counter.nextGsId()
        val gs3 =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gs3Id)
                .setPrevGameStateId(gs2Id)
                .setPendingMessageCount(1)
                .setUpdate(GameStateUpdate.SendAndRecord)

        val messages =
            mutableListOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gs1Id, counter.nextMsgId()) { it.gameStateMessage = gs1.build() },
                makeGRE(GREMessageType.GameStateMessage_695e, gs2Id, counter.nextMsgId()) { it.gameStateMessage = gs2.build() },
                makeGRE(GREMessageType.GameStateMessage_695e, gs3Id, counter.nextMsgId()) { it.gameStateMessage = gs3.build() },
            )

        messages.add(
            makeGRE(GREMessageType.IntermissionReq_695e, gs3Id, counter.nextMsgId()) {
                it.intermissionReq =
                    IntermissionReq
                        .newBuilder()
                        .setResult(
                            ResultSpec
                                .newBuilder()
                                .setScope(MatchScope.Match)
                                .setResult(ResultType.WinLoss)
                                .setWinningTeamId(winningTeam)
                                .setReason(reason),
                        ).addOptions(
                            UserOption
                                .newBuilder()
                                .setOptionPrompt(Prompt.newBuilder().setPromptId(PromptIds.DRAW_CARD))
                                .setResponseType(ClientMessageType.DrawCardResp),
                        ).addOptions(
                            UserOption
                                .newBuilder()
                                .setOptionPrompt(Prompt.newBuilder().setPromptId(PromptIds.REVEAL_HAND))
                                .setResponseType(ClientMessageType.RevealHandResp),
                        ).setIntermissionPrompt(
                            Prompt
                                .newBuilder()
                                .setPromptId(PromptIds.MATCH_RESULT_WIN_LOSS)
                                .addParameters(
                                    PromptParameter
                                        .newBuilder()
                                        .setParameterName("WinningTeamId")
                                        .setType(ParameterType.Number)
                                        .setNumberValue(winningTeam),
                                ),
                        ).build()
            },
        )

        return BundleResult(messages)
    }

    /**
     * Timer start: sends [TimerStateMessage] (GRE type 56) with Decision timer running.
     * Sent on priority grant — the client shows a rope countdown.
     */
    fun timerStart(
        counter: MessageCounter,
        durationSec: Int = 30,
    ): BundleResult = buildTimerBundle(counter, running = true, durationSec = durationSec)

    /**
     * Timer stop: sends [TimerStateMessage] with running=false.
     * Sent when client responds to an action (pass/cast/play).
     */
    fun timerStop(
        counter: MessageCounter,
        durationSec: Int = 30,
    ): BundleResult = buildTimerBundle(counter, running = false, durationSec = durationSec)

    private fun buildTimerBundle(
        counter: MessageCounter,
        running: Boolean,
        durationSec: Int,
    ): BundleResult {
        val timer =
            TimerStateMessage
                .newBuilder()
                .setSeatId(seatId)
                .addTimers(
                    TimerInfo
                        .newBuilder()
                        .setTimerId(1)
                        .setType(TimerType.Decision)
                        .setDurationSec(durationSec)
                        .setElapsedSec(0)
                        .setRunning(running)
                        .setBehavior(TimerBehavior.Timeout_a3cd),
                ).build()
        val msg =
            makeGRE(GREMessageType.TimerStateMessage_695e, counter.currentGsId(), counter.nextMsgId()) {
                it.timerStateMessage = timer
            }
        return BundleResult(listOf(msg))
    }

    /**
     * Build a bare echo diff GSM (empty Diff with just gsId chain + update type).
     *
     * **Where echoes fire.** State-only and remote-seat content-bearing
     * emissions append one of these. Same applies to the `selectTargets`
     * re-prompt cycle in `TargetingHandler.onSelectTargets`. The empirical
     * pattern is "one empty echo per content GSM, same updateType."
     *
     * **Where echoes do not fire.** Human-priority [postAction] bundles and
     * prompt-bearing bundles — [selectTargetsBundle], [selectNBundle],
     * [castingTimeOptionsBundle], [payCostsBundle], [declareAttackersBundle],
     * [declareBlockersBundle] — ship `[GSM, Request]` without a trailing echo.
     * Prompt re-entry frames carry their echo through `TargetingHandler`
     * instead of as a tag-along on the request bundle.
     */
    fun buildEchoDiffGsm(
        counter: MessageCounter,
        updateType: GameStateUpdate = GameStateUpdate.Send,
        previousGsId: Int? = null,
    ): GREToClientMessage {
        val link = counter.nextGameStateLink()
        val prev = previousGsId ?: link.prevGsId
        return makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) {
            it.gameStateMessage =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(link.gsId)
                    .setPrevGameStateId(prev)
                    .setUpdate(updateType)
                    .build()
        }
    }

    /** Explicitly remove a modal trigger ability synthesized for CastingTimeOptionsReq. */
    fun modalStackCleanup(
        counter: MessageCounter,
        abilityInstanceId: Int,
    ): GREToClientMessage {
        val link = counter.nextGameStateLink()
        return makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) {
            it.gameStateMessage =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(link.gsId)
                    .setPrevGameStateId(link.prevGsId)
                    .setUpdate(GameStateUpdate.Send)
                    .addDiffDeletedInstanceIds(abilityInstanceId)
                    .addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(ZoneIds.STACK)
                            .setType(ZoneType.Stack)
                            .setVisibility(Visibility.Public)
                            .build(),
                    ).build()
        }
    }

    /**
     * Resolve candidateRefs to Forge cards and build a surveil/scry bundle.
     *
     * Encapsulates card resolution (candidateRefs → Forge Card + instanceId) plus
     * bundle building (reveal diff + GroupReq) so callers don't need to do inline
     * card resolution. Returns null if no cards could be resolved from candidateRefs.
     *
     * @param candidateRefs prompt candidate references from [InteractivePromptBridge]
     * @param context whether this is surveil or scry
     * @param counter message counter for sequencing
     */
    fun resolveSurveilScryBundle(
        candidateRefs: List<PromptCandidateRefDto>,
        context: GroupingContext,
        counter: MessageCounter,
    ): BundleResult? {
        val game = bridge.getGame() ?: return null
        val resolved =
            candidateRefs
                .filter { it.isCard() }
                .mapNotNull { ref ->
                    val card = game.findById(ref.entityId)
                    if (card != null) card to bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value else null
                }
        if (resolved.isEmpty()) return null
        val snap = GsmSnapshot.capture(game, bridge, matchId, 0)
        val topCardSnaps = resolved.mapNotNull { (card, _) -> snap.objects[ForgeCardId(card.id)] }
        if (topCardSnaps.size != resolved.size) return null
        val cardInstanceIds = resolved.map { it.second }
        val sourceId = game.stack.firstOrNull()?.let { bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value } ?: 0
        return surveilScryBundle(topCardSnaps, cardInstanceIds, sourceId, context, counter)
    }

    /**
     * Surveil/scry bundle: reveal diff (card objects with Private visibility) + GroupReq.
     *
     * Builds a GSM diff that exposes library top card(s) as `visibility=Private, viewers=[seatId]`
     * so the client shows them face-up in the surveil/scry modal, followed by a GroupReq.
     *
     * @param topCardSnaps snapshots for the cards being surveilled/scryed
     * @param cardInstanceIds instanceIds corresponding to [topCardSnaps]
     * @param sourceId instanceId of the triggering spell
     * @param context whether this is surveil or scry
     * @param counter message counter for sequencing
     */
    fun surveilScryBundle(
        topCardSnaps: List<CardSnapshot>,
        cardInstanceIds: List<Int>,
        sourceId: Int,
        context: GroupingContext,
        counter: MessageCounter,
    ): BundleResult {
        val libZoneId = ZoneIds.libraryOf(seatId)
        val revealedObjects =
            topCardSnaps.zip(cardInstanceIds).map { (cardSnap, iid) ->
                ObjectMapper
                    .buildFromSnapshot(cardSnap, iid, libZoneId, seatId, bridge.cardProto, Visibility.Private)
                    .toBuilder()
                    .addViewers(seatId)
                    .build()
            }
        val gsId = counter.nextGsId()
        val revealDiff =
            makeGRE(GREMessageType.GameStateMessage_695e, gsId, counter.nextMsgId()) {
                it.gameStateMessage =
                    GameStateMessage
                        .newBuilder()
                        .setType(GameStateType.Diff)
                        .setGameStateId(gsId)
                        .setPrevGameStateId(gsId - 1)
                        .addAllGameObjects(revealedObjects)
                        .build()
            }

        val groupReq =
            GsmBuilder.buildSurveilScryGroupReq(
                msgId = counter.nextMsgId(),
                gameStateId = gsId,
                seatId = seatId,
                cardInstanceIds = cardInstanceIds,
                context = context,
                sourceInstanceId = sourceId,
            )
        return BundleResult(listOf(revealDiff, groupReq))
    }

    /** Build a single GRE message. */

    private fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val gre =
            GREToClientMessage
                .newBuilder()
                .setType(type)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .addSystemSeatIds(seatId)
        configure(gre)
        return gre.build()
    }

    internal fun coinFlipPromptMessages(
        events: List<GameEvent>,
        gsId: Int,
        counter: MessageCounter,
    ): List<GREToClientMessage> =
        events.filterIsInstance<GameEvent.CoinFlipped>().map { event ->
            makeGRE(GREMessageType.PromptReq, gsId, counter.nextMsgId()) {
                it.setPrompt(
                    Prompt
                        .newBuilder()
                        .setPromptId(PromptIds.COIN_FLIP)
                        .addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("PlayerId")
                                .setType(ParameterType.Reference_a14a)
                                .setReference(
                                    Reference
                                        .newBuilder()
                                        .setType(ReferenceType.PlayerSeatId)
                                        .setId(event.flipperSeatId.value),
                                ),
                        ).addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("CoinFlipResult")
                                .setType(ParameterType.Reference_a14a)
                                .setReference(
                                    Reference
                                        .newBuilder()
                                        .setType(ReferenceType.LocalizationId)
                                        .setId(if (event.result == 1) COIN_FLIP_WIN_LOCALIZATION_ID else COIN_FLIP_LOSS_LOCALIZATION_ID),
                                ),
                        ).build(),
                )
            }
        }

    companion object {
        private const val MAX_ID_TRANSITION_RETRIES = 3
        private const val COIN_FLIP_WIN_LOCALIZATION_ID = 47
        private const val COIN_FLIP_LOSS_LOCALIZATION_ID = 48

        /**
         * Pure function — no instance state needed. Checks if the only action
         * available is Pass (no Cast, Play, Activate).
         */
        fun shouldAutoPass(actions: ActionsAvailableReq): Boolean =
            actions.actionsList.all { !ShouldStopEvaluator.shouldStop(it.actionType) }

        /**
         * True when the drained [events] describe a turn-boundary or trigger-driven
         * draw — one that should be emitted as [GameStateUpdate.SendHiFi] rather
         * than the default [GameStateUpdate.SendAndRecord].
         *
         * The wire contract (bead leyline-pey) marks spell-driven draws in Main1
         * (Divination, Opt, etc.) as `SendAndRecord`, but turn-boundary auto-draws
         * and upkeep-triggered draws as `SendHiFi`. This helper detects the
         * latter by requiring all of:
         *
         * 1. A Library→Hand [GameEvent.ZoneChanged] whose card owner is the
         *    active seat.
         * 2. No [GameEvent.SpellCast] for that seat in the same bundle
         *    (filters out cast-Divination-draw chains).
         * 3. No [GameEvent.SpellResolved] for that seat in the same bundle
         *    (filters out resolve-Divination-draw chains).
         * 4. The snapshot phase is UPKEEP, DRAW, or MAIN1 — the window leyline
         *    bundles the auto-draw into (MAIN1 covers the common case where the
         *    DRAW step's card move lands in the first MAIN1 priority grant's
         *    GSM).
         * 5. A non-null snapshot phase — fall back to the default updateType
         *    when phase is unknown.
         */
        internal fun isTurnOrTriggerDraw(
            events: List<GameEvent>,
            snap: GsmSnapshot,
            activeSeat: SeatId,
        ): Boolean {
            val phase = snap.phase.phase ?: return false
            if (phase != PhaseType.UPKEEP && phase != PhaseType.DRAW && phase != PhaseType.MAIN1) return false

            val hasActiveSeatDraw =
                events.any { ev ->
                    ev is GameEvent.ZoneChanged &&
                        ev.from == Zone.Library &&
                        ev.to == Zone.Hand &&
                        snap.objects[ev.cardId]?.owner == activeSeat
                }
            if (!hasActiveSeatDraw) return false

            val hasActiveSeatSpellCast =
                events.any { ev -> ev is GameEvent.SpellCast && ev.seatId == activeSeat && !ev.isTrigger }
            if (hasActiveSeatSpellCast) return false

            val hasActiveSeatSpellResolved =
                events.any { ev ->
                    ev is GameEvent.SpellResolved && snap.objects[ev.cardId]?.owner == activeSeat
                }
            if (hasActiveSeatSpellResolved) return false

            return true
        }
    }
}
