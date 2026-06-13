package leyline.game.bundle

import forge.game.Game
import forge.game.phase.PhaseType
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationLossReason
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.PlayerMapper
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ShouldStopEvaluator
import leyline.game.mapping.StateMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Pure functions that build GRE message bundles for each flow milestone.
 *
 * No side effects, no Netty, no mutable handler state — takes everything as params,
 * returns messages. The shared [MessageCounter] advances atomically on each call.
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
    )

    private data class FrameDiff(
        val gameStateId: Int,
        val snap: GsmSnapshot,
        val result: StateMapper.BuildResult,
        val events: FrameEventLog,
    )

    private fun buildFrameDiff(
        game: Game,
        counter: MessageCounter,
        revealForSeat: Int? = null,
        eventsOverride: FrameEventLog? = null,
        updateType: (GsmSnapshot, FrameEventLog) -> GameStateUpdate,
    ): FrameDiff {
        val nextGs = counter.nextGsId()
        val snap = GsmSnapshot.capture(game, bridge, matchId, nextGs)
        val events = eventsOverride ?: bridge.closeBundleFrame(seatId)
        val previousSnap = cursor.lastSent
        val result =
            StateMapper.buildDiff(
                previousSnap,
                snap,
                events,
                nextGs,
                matchId,
                bridge,
                updateType = updateType(snap, events),
                viewingSeatId = seatId,
                revealForSeat = revealForSeat,
            )
        bridge.applyMutations(result.mutations)
        bridge.diffListener?.invoke(previousSnap, snap, events.events, nextGs, result.gsm)
        return FrameDiff(nextGs, snap, result, events)
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
    ): BundleResult {
        val diff =
            buildFrameDiff(game, counter, revealForSeat = revealForSeat) { snap, events ->
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
        val actions = ActionMapper.buildFromSnapshot(seatId, snap, bridge)

        // PhaseOrStepModified is now emitted event-driven from GameEvent.PhaseChanged
        // in StateMapper Stage 2b — no injection needed here.

        // Re-embed stripped actions into the GSM, then drain any pending
        // PlayerSubmittedTargets so it lands on the first post-submit frame.
        val gsWithActions = GsmBuilder.embedActions(result.gsm, actions, frame, recipientSeatId = seatId)
        val gs = appendPendingPlayerSubmittedTargets(gsWithActions)

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

        cursor.lastSent = snap
        return BundleResult(messages)
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
            val diff = buildFrameDiff(game, counter) { snap, _ -> StateMapper.resolveUpdateType(snap, seatId) }
            val nextGs = diff.gameStateId
            val snap = diff.snap
            val result = diff.result
            val gs = appendPendingPlayerSubmittedTargets(result.gsm)

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

            cursor.lastSent = snap
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
            val diff = buildFrameDiff(game, counter, eventsOverride = eventsOverride) { _, _ -> GameStateUpdate.SendHiFi }
            val nextGs = diff.gameStateId
            val snap = diff.snap
            val frame = GsmFrame.from(snap)
            // Build state first (triggers instanceId realloc), then actions with new IDs
            val gsBase = diff.result.gsm
            // Naive actions: always show human's full hand (Cast/Play) regardless of phase.
            // Client expects human's potential actions embedded during AI turn.
            val actions = ActionMapper.buildNaiveActions(seatId, bridge)

            // Inject turn-start annotation when applicable. PhaseOrStepModified is now
            // emitted event-driven in Stage 2b (inside buildDiff above).
            val gsWithAnnotations =
                if (turnStarted) {
                    gsBase
                        .toBuilder()
                        .apply {
                            addAnnotations(
                                AnnotationBuilder
                                    .newTurnStarted(SeatId(frame.activeSeat))
                                    .toBuilder()
                                    .setId(bridge.nextAnnotationId())
                                    .build(),
                            )
                        }.build()
                } else {
                    gsBase
                }

            // Embed actions WITHOUT pendingMessageCount (no follow-up message expected)
            val gsBuilder = gsWithAnnotations.toBuilder()
            for (action in actions.actionsList) {
                gsBuilder.addActions(
                    ActionInfo
                        .newBuilder()
                        .setSeatId(seatId)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
            val gs = appendPendingPlayerSubmittedTargets(gsBuilder.build())

            val content =
                makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                    it.gameStateMessage = gs
                }
            val echo = buildEchoDiffGsm(counter, GameStateUpdate.SendHiFi, previousGsId = nextGs)

            cursor.lastSent = snap
            BundleResult(listOf(content) + coinFlipPromptMessages(diff.events.events, nextGs, counter) + listOf(echo))
        }

    /**
     * True when the only action available is Pass (no Cast, Play, Activate).
     * Used by [AutoPassEngine] on the session thread to skip empty priority
     * points — mainly on the opponent's turn.
     *
     * This is the **session-side** layer of a two-layer auto-pass system:
     *
     * 1. **Engine-side** — [PlayableActionQuery.hasPlayableNonManaAction] runs
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
    fun buildActions(): ActionsAvailableReq {
        val game = bridge.getGame() ?: return ActionMapper.passOnlyActions()
        val snap = GsmSnapshot.capture(game, bridge, matchId, 0)
        return ActionMapper.buildFromSnapshot(seatId, snap, bridge)
    }

    /** Build a [SelectNReq] from a pending "choose cards" prompt. */
    fun buildSelectNReq(prompt: InteractivePromptBridge.PendingPrompt): SelectNReq = RequestBuilder.buildSelectNReq(prompt, bridge)

    /** Build an [OrderReq] from a pending ordering prompt. */
    fun buildOrderReq(prompt: InteractivePromptBridge.PendingPrompt): Pair<OrderReq, Prompt> = RequestBuilder.buildOrderReq(prompt, bridge)

    /** Build a [SearchReq] GRE message with populated inner fields for library search.
     *
     *  [sourceInstanceId] — `searchReq.sourceId` (the AB iid for activated-
     *  ability searches; the spell iid for hard-cast tutors).
     *
     *  [hostCardInstanceId] — first `prompt.parameters` CardId. Names the
     *  source card (so the picker header reads "Lórien Revealed" rather
     *  than the bare ability description).
     *
     *  [searchingSeat] — second `prompt.parameters` CardId. The searching
     *  seat — what the client picker pairs with [hostCardInstanceId] to
     *  anchor the panel header. Both parameters are required.
     *
     *  [promptId] — picker layout. [PromptIds.SEARCH_TYPECYCLING] for
     *  cycling/typecycling/basiccycling (highlight-every-valid-card layout
     *  with click-to-pick); [PromptIds.SEARCH] for generic tutors.
     *
     *  [allowCancel] — defaults to `No_a526` (typecycling-shape; non-cancellable
     *  resolution-side picker). Generic tutors with optional resolution may
     *  pass `Abort` instead. */
    @Suppress("LongParameterList")
    fun buildSearchReq(
        msgId: Int,
        gsId: Int,
        sourceInstanceId: Int,
        hostCardInstanceId: Int,
        searchingSeat: Int,
        libraryZoneId: Int,
        allLibraryIds: List<Int>,
        validTargetIds: List<Int>,
        maxFind: Int = 1,
        allowFailToFind: Boolean = true,
        promptId: Int = PromptIds.SEARCH,
        allowCancel: AllowCancel = AllowCancel.No_a526,
    ): GREToClientMessage {
        val searchReq =
            SearchReq
                .newBuilder()
                .setMaxFind(maxFind)
                .addZonesToSearch(libraryZoneId)
                .addAllItemsToSearch(allLibraryIds)
                .addAllItemsSought(validTargetIds)
                .setSourceId(sourceInstanceId)
        if (allowFailToFind) {
            searchReq.setAllowFailToFind(AllowFailToFind.Any)
        }
        return GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.SearchReq_695e)
            .setMsgId(msgId)
            .setGameStateId(gsId)
            .addSystemSeatIds(seatId)
            .setAllowCancel(allowCancel)
            .setPrompt(
                Prompt
                    .newBuilder()
                    .setPromptId(promptId)
                    .addParameters(
                        PromptParameter
                            .newBuilder()
                            .setParameterName("CardId")
                            .setType(ParameterType.Number)
                            .setNumberValue(hostCardInstanceId),
                    ).addParameters(
                        PromptParameter
                            .newBuilder()
                            .setParameterName("CardId")
                            .setType(ParameterType.Number)
                            .setNumberValue(searchingSeat),
                    ),
            ).setSearchReq(searchReq)
            .build()
    }

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
                it.actionsAvailableReq = actions
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
            }

        cursor.lastSent = snap
        return BundleResult(listOf(msg1, msg2, msg3, msg4, msg5))
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
    ): BundleResult {
        val nextGs = counter.nextGsId()
        val player = bridge.getPlayer(SeatId(seatId)) ?: return BundleResult(emptyList())
        val snap = GsmSnapshot.capture(game, bridge, matchId, nextGs)

        // Build provisional creature objects for ALL legal attackers.
        // Echo objects carry no combat state; selection lives in the re-prompt.
        val objects = mutableListOf<GameObjectInfo>()
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            val fid = ForgeCardId(card.id)
            val iid = bridge.getOrAllocInstanceId(fid).value
            if (iid !in allLegalAttackerIds) continue
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

        val req =
            RequestBuilder.buildDeclareAttackersReq(
                SeatId(seatId),
                bridge,
                committedAttackerIds = selectedAttackerIds.toSet(),
                committedAttackAlternatives = selectedAttackAlternatives,
                committedDamageRecipients = selectedDamageRecipients,
            )
        val msg2 =
            makeGRE(GREMessageType.DeclareAttackersReq_695e, nextGs, counter.nextMsgId()) {
                it.declareAttackersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
            }

        return BundleResult(listOf(msg1, msg2))
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
        val nextGs = diff.gameStateId
        val snap = diff.snap
        val gs = diff.result.gsm
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gs
            }

        val req = prebuiltReq ?: RequestBuilder.buildDeclareAttackersReq(SeatId(seatId), bridge)
        val msg2 =
            makeGRE(GREMessageType.DeclareAttackersReq_695e, nextGs, counter.nextMsgId()) {
                it.declareAttackersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
            }

        cursor.lastSent = snap
        return BundleResult(listOf(msg1, msg2))
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
    ): BundleResult {
        val nextGs = counter.nextGsId()
        val player = bridge.getPlayer(SeatId(seatId)) ?: return BundleResult(emptyList())
        val snap = GsmSnapshot.capture(game, bridge, matchId, nextGs)

        // Build provisional creature objects for assigned blockers.
        // Echo objects carry NO combat state — only base card fields.
        val objects = mutableListOf<GameObjectInfo>()
        val blockerSet = blockAssignments.keys
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            val fid = ForgeCardId(card.id)
            val iid = bridge.getOrAllocInstanceId(fid).value
            if (iid !in blockerSet) continue
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

        // Cumulative turn-level actions — same pattern as attacker echo.
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

        // Re-prompt with assigned blockers' attackerInstanceIds cleared
        val req = RequestBuilder.buildDeclareBlockersReq(game, SeatId(seatId), bridge, blockerAssignments = blockAssignments)
        val msg2 =
            makeGRE(GREMessageType.DeclareBlockersReq_695e, nextGs, counter.nextMsgId()) {
                it.declareBlockersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
            }

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
        val nextGs = diff.gameStateId
        val snap = diff.snap
        val gs = diff.result.gsm
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gs
            }

        val req = RequestBuilder.buildDeclareBlockersReq(game, SeatId(seatId), bridge)
        val msg2 =
            makeGRE(GREMessageType.DeclareBlockersReq_695e, nextGs, counter.nextMsgId()) {
                it.declareBlockersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
            }

        cursor.lastSent = snap
        return BundleResult(listOf(msg1, msg2))
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
        // Build diff first — triggers instanceId reallocs for zone transfers
        val diff = buildFrameDiff(game, counter) { _, _ -> GameStateUpdate.Send }
        val nextGs = diff.gameStateId
        val snap = diff.snap
        val gs = appendPlayerSelectingTargets(diff.result.gsm, prompt)
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gs
            }

        // Build SelectTargetsReq AFTER diff so sourceId uses post-realloc instanceIds
        val req = RequestBuilder.buildSelectTargetsReq(prompt, bridge, seatId)
        val msg2 =
            makeGRE(GREMessageType.SelectTargetsReq_695e, nextGs, counter.nextMsgId()) {
                it.selectTargetsReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_TARGETS).build())
                it.allowCancel = AllowCancel.Abort
                it.allowUndo = true
            }

        cursor.lastSent = snap
        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * SelectN bundle: GameState + SelectNReq.
     * Used for "choose N cards" prompts (discard, sacrifice, etc.).
     */
    fun selectNBundle(
        game: Game,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
        envelopeForReq: (SelectNReq) -> SelectNEnvelope,
    ): BundleResult {
        val diff = buildFrameDiff(game, counter) { _, _ -> GameStateUpdate.Send }
        return selectNBundleFromDiff(diff, counter, envelopeForReq(buildSelectNReq(prompt)))
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
        val nextGs = diff.gameStateId
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
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gs
            }

        val msg2 =
            makeGRE(GREMessageType.SelectNreq, nextGs, counter.nextMsgId()) {
                it.selectNReq = envelope.req
                it.setPrompt(envelope.prompt)
                if (envelope.allowCancel != AllowCancel.None_a526) {
                    it.allowCancel = envelope.allowCancel
                }
            }

        cursor.lastSent = snap
        return BundleResult(listOf(msg1, msg2))
    }

    /** Order bundle: GameState + OrderReq. */
    fun orderBundle(
        game: Game,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
    ): BundleResult {
        val diff = buildFrameDiff(game, counter) { _, _ -> GameStateUpdate.Send }
        val nextGs = diff.gameStateId
        val snap = diff.snap
        val stagedMove = stagePendingOrderZoneMove(diff.result.gsm, snap, prompt)
        val (req, promptProto) = buildOrderReq(prompt)
        val baseOrderGsm = stagedMove?.gsm ?: attachOrderGameObjects(diff.result.gsm, req, snap, prompt.request.semantic)
        val gs =
            baseOrderGsm
                .toBuilder()
                .setPendingMessageCount(1)
                .build()
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gs
            }
        val msg2 =
            makeGRE(GREMessageType.OrderReq_695e, nextGs, counter.nextMsgId()) {
                it.orderReq = req
                it.setPrompt(promptProto)
                it.allowCancel = AllowCancel.No_a526
                if (prompt.request.semantic == PromptSemantic.OrderForTop) {
                    it.allowUndo = true
                }
            }

        cursor.lastSent = stagedMove?.snap ?: snap
        return BundleResult(listOf(msg1, msg2))
    }

    private data class StagedOrderMove(
        val gsm: GameStateMessage,
        val snap: GsmSnapshot,
    )

    private data class StagedMovedCard(
        val forgeCardId: ForgeCardId,
        val oldId: InstanceId,
        val newId: InstanceId,
    )

    private fun stagePendingOrderZoneMove(
        gsm: GameStateMessage,
        snap: GsmSnapshot,
        prompt: InteractivePromptBridge.PendingPrompt,
    ): StagedOrderMove? {
        val candidateFids =
            prompt.request.candidateRefs
                .filter { it.kind == "card" }
                .map { ForgeCardId(it.entityId) }
        val move = bridge.promptBridge(SeatId(seatId)).pollPendingOrderZoneMove(SeatId(seatId), candidateFids) ?: return null
        if (candidateFids.isEmpty()) return null
        val sourceZoneId = ZoneIds.handOf(move.seatId)
        val destZoneId = ZoneIds.libraryOf(move.seatId)

        val moved =
            candidateFids.map { fid ->
                val realloc = bridge.ids.realloc(fid)
                bridge.retireToLimbo(realloc.old)
                bridge.recordZone(realloc.new, destZoneId)
                StagedMovedCard(fid, realloc.old, realloc.new)
            }
        val stagedSnap = stagedOrderSnapshot(snap, move, sourceZoneId, destZoneId)
        val sourceIid = prompt.request.sourceEntityId?.let { bridge.getOrAllocInstanceId(ForgeCardId(it)) } ?: InstanceId(0)
        return StagedOrderMove(
            gsm = stagedOrderGsm(gsm, snap, stagedSnap, move, moved, sourceIid, sourceZoneId, destZoneId),
            snap = stagedSnap,
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
                limboZoneInfo(),
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
                    .objectIdChanged(staged.oldId, staged.newId, sourceIid)
                    .toBuilder()
                    .setId(bridge.annotations.nextAnnotationId())
                    .build(),
            )
            builder.addAnnotations(
                AnnotationBuilder
                    .zoneTransfer(staged.newId, sourceZoneId, destZoneId, "Put", affectorId = sourceIid)
                    .toBuilder()
                    .setId(bridge.annotations.nextAnnotationId())
                    .build(),
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

    private fun limboZoneInfo(): ZoneInfo =
        ZoneInfo
            .newBuilder()
            .setZoneId(ZoneIds.LIMBO)
            .setType(ZoneType.Limbo)
            .setVisibility(Visibility.Public)
            .addAllObjectInstanceIds(bridge.getLimboInstanceIds().map { it.value })
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
        semantic: PromptSemantic,
    ): GameStateMessage {
        if (req.idsList.isEmpty()) return gsm
        if (semantic != PromptSemantic.OrderForTop && semantic != PromptSemantic.OrderForBottom) return gsm

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
        val nextGs = diff.gameStateId
        val snap = diff.snap
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

        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gs
            }

        val msg2 =
            makeGRE(GREMessageType.CastingTimeOptionsReq_695e, nextGs, counter.nextMsgId()) {
                it.castingTimeOptionsReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.CASTING_TIME_OPTIONS).build())
                it.allowCancel = AllowCancel.Abort
                it.allowUndo = true
            }

        cursor.lastSent = snap
        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * PayCosts bundle: GameState + PayCostsReq.
     * Tells the client to show its native mana payment UI.
     *
     * Currently unused — mana payment auto-resolves via the engine's AI
     * mana solver + checkPendingPrompt(). Wire this in when implementing
     * interactive mana payment in the compatibility flow.
     *
     * The client responds with PerformActionResp (already handled).
     */
    fun payCostsBundle(
        game: Game,
        counter: MessageCounter,
        req: PayCostsReq,
        prompt: Prompt? = null,
    ): BundleResult {
        val diff = buildFrameDiff(game, counter) { _, _ -> GameStateUpdate.Send }
        val nextGs = diff.gameStateId
        val snap = diff.snap
        val gs = diff.result.gsm
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gs
            }

        val msg2 =
            makeGRE(GREMessageType.PayCostsReq_695e, nextGs, counter.nextMsgId()) {
                it.payCostsReq = req
                it.setPrompt(prompt ?: Prompt.newBuilder().setPromptId(PromptIds.PAY_COSTS).build())
                // Without these two flags the client renders the cost-selection
                // picker but treats every card as non-clickable (greyed out).
                // Matches the canonical envelope for non-mana-payment costs
                // (sacrifice, exile-from-grave additional cost).
                it.allowCancel = AllowCancel.Abort
                it.allowUndo = true
            }

        cursor.lastSent = snap
        return BundleResult(listOf(msg1, msg2))
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
     * Build a [ModalReq] + [CastingTimeOptionsReq] proto for a modal prompt.
     *
     * Pure proto construction — caller handles card lookup, fallback, and pending state.
     *
     * Per-mode `modeCost` and `excludedOptions` are populated when the caller
     * supplies them (Spree path, and Charm-with-filtered-modes). Charm with all
     * modes legal can pass nulls and gets the legacy free-mode shape.
     *
     * @param parentGrpId the abilityGrpId of the modal ability
     * @param childGrpIds the grpIds for each modal option, in render order
     * @param modalCosts optional per-mode `+ {cost}` parallel to childGrpIds; null = all free
     * @param excludedGrpIds modes that exist on the card but aren't pickable now
     *   (e.g. Spree counter mode with no stack target). May be empty.
     * @param excludedCosts costs parallel to excludedGrpIds; same shape as modalCosts
     * @param minSel minimum number of modes to select
     * @param maxSel maximum number of modes to select
     * @param sourceInstanceId the instanceId for affectedId/affectorId
     * @param grpId the grpId for the CTO entry (card grpId for spells, ability grpId for triggers)
     * @param ctoId CTO identifier (1-2 for spell-time, 3 for triggered abilities)
     * @param playerIdToPrompt seat number to prompt (null omits the field)
     */
    @Suppress("LongParameterList") // Each param maps to one explicit proto field; bundling into a struct just renames the bag.
    fun buildModalCastingTimeOptionsReq(
        parentGrpId: Int,
        childGrpIds: List<Int>,
        minSel: Int,
        maxSel: Int,
        sourceInstanceId: Int,
        grpId: Int,
        ctoId: Int = 2,
        playerIdToPrompt: Int? = null,
        modalCosts: List<List<Pair<ManaColor, Int>>>? = null,
        excludedGrpIds: List<Int> = emptyList(),
        excludedCosts: List<List<Pair<ManaColor, Int>>> = emptyList(),
    ): CastingTimeOptionsReq {
        val modalReq =
            ModalReq
                .newBuilder()
                .setAbilityGrpId(parentGrpId)
                .setMinSel(minSel)
                .setMaxSel(maxSel)
        for ((i, childGrpId) in childGrpIds.withIndex()) {
            val opt = ModalOption.newBuilder().setGrpId(childGrpId)
            modalCosts?.getOrNull(i)?.forEach { (color, count) ->
                opt.addModeCost(buildManaCost(color, count))
            }
            modalReq.addModalOptions(opt)
        }
        for ((i, exGrpId) in excludedGrpIds.withIndex()) {
            val opt = ModalOption.newBuilder().setGrpId(exGrpId)
            excludedCosts.getOrNull(i)?.forEach { (color, count) ->
                opt.addModeCost(buildManaCost(color, count))
            }
            modalReq.addExcludedOptions(opt)
        }
        val ctoBuilder =
            CastingTimeOptionReq
                .newBuilder()
                .setCtoId(ctoId)
                .setCastingTimeOptionType(CastingTimeOptionType.Modal_a7b4)
                .setAffectedId(sourceInstanceId)
                .setAffectorId(sourceInstanceId)
                .setGrpId(grpId)
                .setIsRequired(true)
                .setModalReq(modalReq)
        if (playerIdToPrompt != null) {
            ctoBuilder.setPlayerIdToPrompt(playerIdToPrompt)
        }
        return CastingTimeOptionsReq
            .newBuilder()
            .addCastingTimeOptionReq(ctoBuilder)
            .build()
    }

    /** Build a single-color [Cost] message for a `+ {cost}` mode entry. */
    private fun buildManaCost(
        color: ManaColor,
        count: Int,
    ): Cost =
        Cost
            .newBuilder()
            .setType(CostType.Mana)
            .setManaCost(
                ManaCost
                    .newBuilder()
                    .addColor(color)
                    .setCount(count),
            ).build()

    /**
     * Build a [CastingTimeOptionsReq] for optional costs (kicker, buyback, etc.).
     *
     * Pure proto construction — caller handles SpellAbility lookup and pending state.
     * `playerIdToPrompt` and `baseManaCost` (with `objectId = instanceId`) are
     * populated on every entry including Done; the Bargain (proto 17) renderer
     * silently drops without them.
     *
     * @param instanceId the instanceId of the card being cast.
     * @param optionalCosts list of (ctoType, abilityGrpId) for each optional cost.
     * @param playerIdToPrompt the casting seat (1 or 2).
     * @param baseManaCost the spell's base mana cost as (color, count) pairs;
     *   empty list leaves manaCost unset.
     */
    fun buildOptionalCostCastingTimeOptionsReq(
        instanceId: Int,
        optionalCosts: List<Pair<CastingTimeOptionType, Int>>,
        playerIdToPrompt: Int,
        baseManaCost: List<Pair<ManaColor, Int>>,
    ): Pair<CastingTimeOptionsReq, List<Int>> {
        val manaRequirements =
            baseManaCost.map { (color, count) ->
                ManaRequirement
                    .newBuilder()
                    .addColor(color)
                    .setCount(count)
                    .setObjectId(instanceId)
                    .build()
            }
        val ctoReqBuilder = CastingTimeOptionsReq.newBuilder()
        val costCtoIds = mutableListOf<Int>()
        for ((i, cost) in optionalCosts.withIndex()) {
            val ctoId = i + 1
            costCtoIds.add(ctoId)
            ctoReqBuilder.addCastingTimeOptionReq(
                CastingTimeOptionReq
                    .newBuilder()
                    .setCtoId(ctoId)
                    .setCastingTimeOptionType(cost.first)
                    .setAffectedId(instanceId)
                    .setAffectorId(instanceId)
                    .setGrpId(cost.second)
                    .setPlayerIdToPrompt(playerIdToPrompt)
                    .addAllManaCost(manaRequirements),
            )
        }
        ctoReqBuilder.addCastingTimeOptionReq(
            CastingTimeOptionReq
                .newBuilder()
                .setCtoId(0)
                .setCastingTimeOptionType(CastingTimeOptionType.Done)
                .setIsRequired(true)
                .setPlayerIdToPrompt(playerIdToPrompt)
                .addAllManaCost(manaRequirements),
        )
        return Pair(ctoReqBuilder.build(), costCtoIds)
    }

    fun buildChooseOrCostCastingTimeOptionsReq(
        instanceId: Int,
        grpId: Int,
        optionCount: Int,
        optionPromptIds: List<Int> = emptyList(),
    ): Pair<CastingTimeOptionsReq, List<Int>> {
        val ctoId = 2
        val selectPrompt =
            Prompt
                .newBuilder()
                .setPromptId(if (optionPromptIds.isNotEmpty()) PromptIds.CHOOSE_OR_COST else PromptIds.SELECT_N)
                .apply {
                    optionPromptIds.forEach { promptId ->
                        addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("Cost")
                                .setType(ParameterType.PromptId)
                                .setPromptId(promptId),
                        )
                    }
                }.build()
        val selectNReq =
            SelectNReq
                .newBuilder()
                .setMinSel(1)
                .setMaxSel(1)
                .setListType(SelectionListType.Dynamic)
                .setIdType(IdType.PromptParameterIndex)
                .setValidationType(SelectionValidationType.NonRepeatable)
                .setSourceId(instanceId)
                .setPrompt(selectPrompt)
                .apply {
                    repeat(optionCount) { index -> addIds(index + 1) }
                }.build()

        val req =
            CastingTimeOptionsReq
                .newBuilder()
                .addCastingTimeOptionReq(
                    CastingTimeOptionReq
                        .newBuilder()
                        .setCtoId(ctoId)
                        .setCastingTimeOptionType(CastingTimeOptionType.ChooseOrCost)
                        .setAffectedId(instanceId)
                        .setAffectorId(instanceId)
                        .setGrpId(grpId)
                        .setPlayerIdToPrompt(seatId)
                        .setIsRequired(true)
                        .setSelectNReq(selectNReq),
                ).build()
        return req to (1..optionCount).toList()
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
                .filter { it.kind == "card" }
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

    /**
     * Append PlayerSelectingTargets to the GSM that pairs with SelectTargetsReq.
     * No-op if the prompt has no source entity (defensive — should not happen
     * for a real targeting prompt). Source iid resolution mirrors
     * [RequestBuilder.buildSelectTargetsReq].
     */
    private fun appendPlayerSelectingTargets(
        gsm: GameStateMessage,
        prompt: InteractivePromptBridge.PendingPrompt,
    ): GameStateMessage {
        val sourceEntityId = prompt.request.sourceEntityId ?: return gsm
        val sourceIid = bridge.getOrAllocInstanceId(ForgeCardId(sourceEntityId))
        val annotation =
            AnnotationBuilder
                .playerSelectingTargets(sourceIid, SeatId(seatId))
                .toBuilder()
                .setId(bridge.nextAnnotationId())
                .build()
        return gsm.toBuilder().addAnnotations(annotation).build()
    }

    /**
     * Drain a queued PlayerSubmittedTargets and append to the GSM. Bundle methods
     * that build a diff call this after `buildDiff` so PSuT lands as the first
     * annotation on the post-submit frame, matching the canonical slot ordering.
     */
    private fun appendPendingPlayerSubmittedTargets(gsm: GameStateMessage): GameStateMessage {
        val pending = cursor.drainPSuT() ?: return gsm
        val annotation =
            AnnotationBuilder
                .playerSubmittedTargets(pending.spellInstanceId, pending.casterSeatId)
                .toBuilder()
                .setId(bridge.nextAnnotationId())
                .build()
        return gsm.toBuilder().addAnnotations(annotation).build()
    }

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
