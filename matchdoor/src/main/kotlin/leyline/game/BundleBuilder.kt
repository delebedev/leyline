package leyline.game

import forge.game.Game
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.mapper.ActionMapper
import leyline.game.mapper.ObjectMapper
import leyline.game.mapper.PlayerMapper
import leyline.game.mapper.PromptIds
import leyline.game.mapper.ShouldStopEvaluator
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Pure functions that build GRE message bundles for each flow milestone.
 *
 * No side effects, no Netty, no mutable handler state — takes everything as params,
 * returns messages. The shared [MessageCounter] advances atomically on each call.
 *
 * **Ordering invariant:** every method that includes actions calls
 * [StateMapper.buildDiffFromGame] *first*. Diff-building triggers instanceId
 * reallocation for zone transfers — if actions were built before the diff,
 * they'd reference pre-realloc instanceIds and the client couldn't match them.
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
object BundleBuilder {

    data class BundleResult(
        val messages: List<GREToClientMessage>,
    )

    /**
     * Post-action state bundle:
     *   GRE 1: Diff GameStateMessage with embedded actions (only changed zones/objects)
     *   GRE 2: ActionsAvailableReq
     */
    fun postAction(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        counter: MessageCounter,
    ): BundleResult {
        val nextGs = counter.nextGsId()
        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        // Build state first (without actions) — triggers instanceId realloc on zone transfers.
        // Then build actions so they reference the new (post-move) instanceIds.
        val gsBase = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)
        val actions = ActionMapper.buildActions(game, seatId, bridge)

        // Detect phase/step change vs last state sent to client.
        // Uses lastSentTurnInfo (what client saw) instead of prevSnapshot (diff computation).
        // This handles the case where PhaseStopProfile skips phases on the engine thread —
        // prevSnapshot may already show the new phase, but the client hasn't seen it.
        val gsWithPhaseAnnotation = if (gsBase.hasTurnInfo() &&
            bridge.isPhaseChangedFromLastSent(gsBase.turnInfo)
        ) {
            val handler = game.phaseHandler
            val human = bridge.getPlayer(SeatId(1))
            val activeSeat = if (handler.playerTurn == human) 1 else 2
            gsBase.toBuilder()
                .addAnnotations(
                    AnnotationBuilder.phaseOrStepModified(
                        activeSeat,
                        gsBase.turnInfo.phase.number,
                        gsBase.turnInfo.step.number,
                    ).toBuilder().setId(bridge.nextAnnotationId()).build(),
                )
                .build()
        } else {
            gsBase
        }

        // Re-embed stripped actions into the GSM
        val gs = GsmBuilder.embedActions(gsWithPhaseAnnotation, actions, game, bridge, recipientSeatId = seatId)

        val messages = listOf(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
                it.gameStateMessage = gs
            },
            makeGRE(GREMessageType.ActionsAvailableReq_695e, nextGs, seatId, counter.nextMsgId()) {
                it.actionsAvailableReq = actions
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
            },
        )

        return BundleResult(messages)
    }

    /**
     * State-only diff: Diff GameStateMessage without ActionsAvailableReq.
     * Used to show intermediate state (e.g. spell on stack) without
     * prompting the client for a response.
     */
    fun stateOnlyDiff(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        counter: MessageCounter,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)

        val messages = listOf(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
                it.gameStateMessage = gs
            },
        )

        return BundleResult(messages)
    }

    /**
     * Remote action diff: single GS Diff with SendHiFi.
     *
     * Real server sends exactly one GSM per AI action — no echo, no
     * pendingMessageCount. Uses SendHiFi (transient update the client
     * doesn't need to persist as a save point). Actions are embedded
     * without the pending flag so the client dispatches immediately.
     */
    fun remoteActionDiff(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        counter: MessageCounter,
        phaseChanged: Boolean = false,
        turnStarted: Boolean = false,
    ): BundleResult {
        val handler = game.phaseHandler
        val human = bridge.getPlayer(SeatId(1))
        val activeSeat = if (handler.playerTurn == human) 1 else 2
        val nextGs = counter.nextGsId()
        // Build state first (triggers instanceId realloc), then actions with new IDs
        val gsBase = StateMapper.buildDiffFromGame(
            game,
            nextGs,
            matchId,
            bridge,
            updateType = GameStateUpdate.SendHiFi,
            viewingSeatId = seatId,
        )
        // Naive actions: always show human's full hand (Cast/Play) regardless of phase.
        // Real server embeds human's potential actions during AI turn.
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)

        // Inject phase/turn annotations when applicable — must assign IDs to avoid
        // mixed-id violations when gsBase already contains numbered zone-transfer annotations.
        val gsWithAnnotations = if (phaseChanged || turnStarted) {
            val protoPhase = PlayerMapper.mapPhase(handler.phase).number
            val protoStep = PlayerMapper.mapStep(handler.phase).number
            gsBase.toBuilder().apply {
                if (turnStarted) {
                    addAnnotations(
                        AnnotationBuilder.newTurnStarted(activeSeat)
                            .toBuilder().setId(bridge.nextAnnotationId()).build(),
                    )
                }
                if (phaseChanged) {
                    addAnnotations(
                        AnnotationBuilder.phaseOrStepModified(activeSeat, protoPhase, protoStep)
                            .toBuilder().setId(bridge.nextAnnotationId()).build(),
                    )
                    addAnnotations(
                        AnnotationBuilder.phaseOrStepModified(activeSeat, protoPhase, protoStep)
                            .toBuilder().setId(bridge.nextAnnotationId()).build(),
                    )
                }
            }.build()
        } else {
            gsBase
        }

        // Embed actions WITHOUT pendingMessageCount (no follow-up message expected)
        val gs = gsWithAnnotations.toBuilder()
        for (action in actions.actionsList) {
            gs.addActions(
                ActionInfo.newBuilder()
                    .setSeatId(seatId)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }

        val messages = listOf(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
                it.gameStateMessage = gs.build()
            },
        )

        return BundleResult(messages)
    }

    /**
     * True when the only action available is Pass (no Cast, Play, Activate).
     * Used by [AutoPassEngine] on the session thread to skip empty priority
     * points — mainly on the opponent's turn.
     *
     * This is the **session-side** layer of a two-layer auto-pass system:
     *
     * 1. **Engine-side** — [PlayableActionQuery.hasPlayableNonManaAction] runs
     *    inside [WebPlayerController.chooseSpellAbilityToPlay] on the engine
     *    thread, own-turn only. When false, the engine auto-passes before the
     *    bridge round-trip even happens. The session thread never sees it.
     *
     * 2. **Session-side** (this) — checks the proto action list we already
     *    built. Covers opponent-turn priority and any case the engine-side
     *    skip didn't fire. No redundant Game queries needed.
     */
    fun shouldAutoPass(actions: ActionsAvailableReq): Boolean =
        actions.actionsList.all { !ShouldStopEvaluator.shouldStop(it.actionType) }

    // --- Request builders (delegate to RequestBuilder) ---
    // MatchSession uses these instead of calling RequestBuilder directly,
    // keeping RequestBuilder as an internal dependency of the bundle layer.

    /** Build playable actions for a seat (with legality checks). */
    fun buildActions(game: Game, seatId: Int, bridge: GameBridge): ActionsAvailableReq =
        ActionMapper.buildActions(game, seatId, bridge)

    /** Build a [SelectNReq] from a pending "choose cards" prompt. */
    fun buildSelectNReq(
        prompt: leyline.bridge.InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): SelectNReq = RequestBuilder.buildSelectNReq(prompt, bridge)

    /** Build a [DeclareAttackersReq] listing legal attackers. */
    fun buildDeclareAttackersReq(game: Game, seatId: Int, bridge: GameBridge): DeclareAttackersReq =
        RequestBuilder.buildDeclareAttackersReq(game, seatId, bridge)

    /**
     * Phase transition bundle matching real server pattern (5 messages):
     *   1. GS Diff SendHiFi (2x PhaseOrStepModified, gameInfo, players, actions)
     *   2. GS Diff SendHiFi echo (turnInfo + actions only)
     *   3. GS Diff SendAndRecord (1x PhaseOrStepModified, actions)
     *   4. PromptReq (promptId=37)
     *   5. ActionsAvailableReq (promptId=2)
     */
    fun phaseTransitionDiff(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        counter: MessageCounter,
    ): BundleResult {
        val prevGs = counter.currentGsId()
        val nextGs = counter.nextGsId()

        val phase = PlayerMapper.mapPhase(game.phaseHandler.phase)
        val step = PlayerMapper.mapStep(game.phaseHandler.phase)
        // Naive actions: always show human's full hand (Cast/Play) regardless of phase.
        // Real server embeds Cast/Play actions regardless of current phase (cosmetic only;
        // actual priority gating uses ActionsAvailableReq sent when human gets priority).
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)
        val handler = game.phaseHandler
        val human = bridge.getPlayer(SeatId(1))
        val activeSeat = if (handler.playerTurn == human) 1 else 2
        val prioritySeat = if (handler.priorityPlayer == human) 1 else 2

        // Shared turnInfo for all diffs
        val turnInfo = TurnInfo.newBuilder()
            .setPhase(phase)
            .setStep(step)
            .setTurnNumber(handler.turn.coerceAtLeast(1))
            .setActivePlayer(activeSeat)
            .setPriorityPlayer(prioritySeat)
            .setDecisionPlayer(prioritySeat)

        // Message 1: SendHiFi with 2x PhaseOrStepModified + gameInfo
        val gs1 = GsmBuilder.buildTransitionState(
            game,
            nextGs,
            prevGameStateId = prevGs,
            matchId,
            bridge,
            phase,
            step,
            isStageTransition = true,
            actions = actions,
            actionSeatId = seatId,
        )
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = gs1
        }

        // Message 2: SendHiFi echo (turnInfo + actions, no annotations)
        val msg1GsId = nextGs
        val echoGs = counter.nextGsId()
        val echoBuilder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(echoGs)
            .setPrevGameStateId(msg1GsId)
            .setTurnInfo(turnInfo)
            .setUpdate(GameStateUpdate.SendHiFi)
        embedActions(echoBuilder, actions, seatId, pending = false)
        val msg2 = makeGRE(GREMessageType.GameStateMessage_695e, echoGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = echoBuilder.build()
        }

        // Message 3: SendAndRecord with 1x PhaseOrStepModified
        val commitGs = counter.nextGsId()
        val commitBuilder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(commitGs)
            .setPrevGameStateId(echoGs)
            .setTurnInfo(turnInfo)
            .addAnnotations(
                AnnotationBuilder.phaseOrStepModified(activeSeat, phase.number, step.number)
                    .toBuilder().setId(bridge.nextAnnotationId()).build(),
            )
            .addAllTimers(PlayerMapper.buildTimers())
            .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(commitBuilder, actions, seatId)
        val msg3 = makeGRE(GREMessageType.GameStateMessage_695e, commitGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = commitBuilder.build()
        }

        // Message 4: PromptReq (promptId=37)
        val msg4 = makeGRE(GREMessageType.PromptReq, commitGs, seatId, counter.nextMsgId()) {
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.STARTING_PLAYER).build())
        }

        // Message 5: ActionsAvailableReq (promptId=2)
        val msg5 = makeGRE(GREMessageType.ActionsAvailableReq_695e, commitGs, seatId, counter.nextMsgId()) {
            it.actionsAvailableReq = actions
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
        }

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
                ActionInfo.newBuilder()
                    .setSeatId(seatId)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }
    }

    /**
     * Echo-back bundle for iterative attacker toggle: thin Diff with provisional
     * combat state on toggled creatures + fresh DeclareAttackersReq.
     *
     * Real server sends `objects=1` per toggle with the creature's attack state
     * and tap state reflecting the provisional selection. We synthesize this
     * because the engine's combat object doesn't track provisional toggles.
     *
     * @param selectedAttackerIds instanceIds currently selected as attackers
     * @param allLegalAttackerIds all instanceIds eligible to attack (for deselect detection)
     */
    @Suppress("UnusedParameter")
    fun echoAttackersBundle(
        game: Game,
        bridge: GameBridge,
        seatId: Int,
        counter: MessageCounter,
        selectedAttackerIds: List<Int>,
        allLegalAttackerIds: List<Int>,
    ): BundleResult {
        val nextGs = counter.nextGsId()
        val player = bridge.getPlayer(SeatId(seatId)) ?: return BundleResult(emptyList())

        // Build provisional creature objects for ALL legal attackers.
        // Real server echo objects carry NO combat state — confirmed across 4 recordings.
        val objects = mutableListOf<GameObjectInfo>()
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            val iid = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            if (iid !in allLegalAttackerIds) continue

            objects.add(
                ObjectMapper.buildProvisionalCombatObject(
                    card,
                    iid,
                    ZoneIds.BATTLEFIELD,
                    ownerSeatId = seatId,
                    controllerSeatId = seatId,
                    bridge = bridge,
                    game = game,
                ),
            )
        }

        // Cumulative turn-level actions (Cast, Play, ActivateMana, Activate).
        // Real server echo GSMs always include this running log.
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)

        val gsmBuilder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(nextGs)
            .addAllGameObjects(objects)
            .setPrevGameStateId(nextGs - 1)
            .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(gsmBuilder, actions, seatId, pending = false)

        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = gsmBuilder.build()
        }

        val req = RequestBuilder.buildDeclareAttackersReq(
            game,
            seatId,
            bridge,
            committedAttackerIds = selectedAttackerIds.toSet(),
        )
        val msg2 = makeGRE(GREMessageType.DeclareAttackersReq_695e, nextGs, seatId, counter.nextMsgId()) {
            it.declareAttackersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_TARGETS).build())
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * Declare-attackers bundle: Diff (DeclareAttack step) + DeclareAttackersReq (prompt id=6).
     */
    fun declareAttackersBundle(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        counter: MessageCounter,
        prebuiltReq: DeclareAttackersReq? = null,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        val req = prebuiltReq ?: RequestBuilder.buildDeclareAttackersReq(game, seatId, bridge)
        val msg2 = makeGRE(GREMessageType.DeclareAttackersReq_695e, nextGs, seatId, counter.nextMsgId()) {
            it.declareAttackersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_TARGETS).build())
        }

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
        bridge: GameBridge,
        seatId: Int,
        counter: MessageCounter,
        blockAssignments: Map<Int, Int>, // blockerInstanceId → attackerInstanceId
    ): BundleResult {
        val nextGs = counter.nextGsId()
        val player = bridge.getPlayer(SeatId(seatId)) ?: return BundleResult(emptyList())

        // Build provisional creature objects for assigned blockers.
        // Real server echo objects carry NO combat state — confirmed across 4 recordings.
        val objects = mutableListOf<GameObjectInfo>()
        val blockerSet = blockAssignments.keys
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            val iid = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            if (iid !in blockerSet) continue

            objects.add(
                ObjectMapper.buildProvisionalCombatObject(
                    card,
                    iid,
                    ZoneIds.BATTLEFIELD,
                    ownerSeatId = seatId,
                    controllerSeatId = seatId,
                    bridge = bridge,
                    game = game,
                ),
            )
        }

        // Cumulative turn-level actions — same pattern as attacker echo.
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)

        val gsmBuilder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(nextGs)
            .addAllGameObjects(objects)
            .setPrevGameStateId(nextGs - 1)
            .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(gsmBuilder, actions, seatId, pending = false)

        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = gsmBuilder.build()
        }

        // Re-prompt with assigned blockers' attackerInstanceIds cleared
        val req = RequestBuilder.buildDeclareBlockersReq(game, seatId, bridge, blockerAssignments = blockAssignments)
        val msg2 = makeGRE(GREMessageType.DeclareBlockersReq_695e, nextGs, seatId, counter.nextMsgId()) {
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
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        counter: MessageCounter,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        val req = RequestBuilder.buildDeclareBlockersReq(game, seatId, bridge)
        val msg2 = makeGRE(GREMessageType.DeclareBlockersReq_695e, nextGs, seatId, counter.nextMsgId()) {
            it.declareBlockersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
        }

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
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        counter: MessageCounter,
        prompt: leyline.bridge.InteractivePromptBridge.PendingPrompt,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        // Build diff first — triggers instanceId reallocs for zone transfers
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = GameStateUpdate.Send, viewingSeatId = seatId)
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        // Build SelectTargetsReq AFTER diff so sourceId uses post-realloc instanceIds
        val req = RequestBuilder.buildSelectTargetsReq(prompt, bridge, seatId)
        val msg2 = makeGRE(GREMessageType.SelectTargetsReq_695e, nextGs, seatId, counter.nextMsgId()) {
            it.selectTargetsReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_TARGETS).build())
            it.allowCancel = AllowCancel.Abort
            it.allowUndo = true
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * SelectN bundle: GameState + SelectNReq.
     * Used for "choose N cards" prompts (discard, sacrifice, etc.).
     */
    fun selectNBundle(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        counter: MessageCounter,
        req: SelectNReq,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = GameStateUpdate.Send, viewingSeatId = seatId)
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        val msg2 = makeGRE(GREMessageType.SelectNreq, nextGs, seatId, counter.nextMsgId()) {
            it.selectNReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build())
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * CastingTimeOptions bundle: GameState + CastingTimeOptionsReq.
     * Used for modal ETB/cast prompts (Charming Prince, Goblin Surprise, etc.).
     *
     * Sends a GSM diff first (state may have changed during trigger/resolution),
     * followed by CastingTimeOptionsReq with the ModalReq payload. Sets
     * allowCancel=Abort and allowUndo=true (client shows Cancel button).
     */
    fun castingTimeOptionsBundle(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        counter: MessageCounter,
        req: CastingTimeOptionsReq,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = GameStateUpdate.Send, viewingSeatId = seatId)
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        val msg2 = makeGRE(GREMessageType.CastingTimeOptionsReq_695e, nextGs, seatId, counter.nextMsgId()) {
            it.castingTimeOptionsReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.CASTING_TIME_OPTIONS).build())
            it.allowCancel = AllowCancel.Abort
            it.allowUndo = true
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * PayCosts bundle: GameState + PayCostsReq.
     * Tells the Arena client to show its native mana payment UI.
     *
     * Currently unused — mana payment auto-resolves via the engine's AI
     * mana solver + checkPendingPrompt(). Wire this in when implementing
     * interactive mana payment for the real client.
     *
     * The client responds with PerformActionResp (already handled).
     */
    fun payCostsBundle(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        counter: MessageCounter,
        req: PayCostsReq,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = GameStateUpdate.Send, viewingSeatId = seatId)
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        val msg2 = makeGRE(GREMessageType.PayCostsReq_695e, nextGs, seatId, counter.nextMsgId()) {
            it.payCostsReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PAY_COSTS).build())
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * Wrap a GameStateMessage as QueuedGameStateMessage (type 51) for opponent during prompts.
     */
    fun queuedGameState(
        gameState: GameStateMessage,
        seatId: Int,
        counter: MessageCounter,
    ): GREToClientMessage = makeGRE(GREMessageType.QueuedGameStateMessage, counter.currentGsId(), seatId, counter.nextMsgId()) {
        it.gameStateMessage = gameState
    }

    /**
     * Server-forced pass (EdictalMessage). Tells the client "I'm passing priority for seat X".
     * Breaks the client out of autoPassPriority mode so it re-renders action buttons.
     */
    fun edictalPass(seatId: Int, counter: MessageCounter): BundleResult {
        val edictal = EdictalMessage.newBuilder()
            .setEdictMessage(
                ClientToGREMessage.newBuilder()
                    .setType(ClientMessageType.PerformActionResp_097b)
                    .setSystemSeatId(seatId)
                    .setPerformActionResp(
                        PerformActionResp.newBuilder()
                            .addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                    ),
            )
            .build()
        val msg = makeGRE(GREMessageType.EdictalMessage_695e, counter.currentGsId(), seatId, counter.nextMsgId()) {
            it.edictalMessage = edictal
        }
        return BundleResult(listOf(msg))
    }

    /**
     * Game-over sequence: 3x GS Diff + IntermissionReq.
     * Pure proto construction — no bridge or game engine access.
     *
     * Pattern matches real server recordings:
     * - gs1: GameInfo(stage=GameOver, matchState=GameComplete, 1 result scope=Game),
     *        players with PendingLoss, teams, LossOfGame annotation (if lethal)
     * - gs2: GameInfo(stage=GameOver, matchState=MatchComplete, 2 results Game+Match)
     * - gs3: bare diff with pendingMessageCount=1
     * - IntermissionReq: options, intermissionPrompt(27) with WinningTeamId param
     *
     * @param reason Game_ae0a for natural game end, Concede for concession
     * @param losingPlayerSeatId seat of the losing player (for LossOfGame annotation)
     * @param lossReason 0=LifeTotal, 3=Concede (maps to Arena's LossOfGame detail)
     */
    fun gameOverBundle(
        winningTeam: Int,
        matchId: String,
        seatId: Int,
        counter: MessageCounter,
        reason: ResultReason = ResultReason.Game_ae0a,
        losingPlayerSeatId: Int = 0,
        lossReason: Int = 0,
        bridge: GameBridge? = null,
    ): BundleResult {
        val prevGsId = counter.currentGsId()
        val losingTeam = if (winningTeam == 1) 2 else 1

        // Shared GameInfo fields matching initial bundle (StateMapper.buildFromGame)
        fun baseGameInfo() = GameInfo.newBuilder()
            .setMatchID(matchId)
            .setGameNumber(1)
            .setStage(GameStage.GameOver)
            .setType(GameType.Duel)
            .setVariant(GameVariant.Normal)
            .setMatchWinCondition(MatchWinCondition.SingleElimination)
            .setSuperFormat(SuperFormat.Constructed)
            .setMulliganType(MulliganType.London)
            .setDeckConstraintInfo(
                DeckConstraintInfo.newBuilder()
                    .setMinDeckSize(60).setMaxDeckSize(250).setMaxSideboardSize(15),
            )

        val gameResult = ResultSpec.newBuilder()
            .setScope(MatchScope.Game_a146)
            .setResult(ResultType.WinLoss)
            .setWinningTeamId(winningTeam)
            .setReason(reason)

        val matchResult = ResultSpec.newBuilder()
            .setScope(MatchScope.Match)
            .setResult(ResultType.WinLoss)
            .setWinningTeamId(winningTeam)
            .setReason(reason)

        // gs1: GameComplete with Game result only, PendingLoss players
        val gs1Info = baseGameInfo()
            .setMatchState(MatchState.GameComplete)
            .addResults(gameResult)
        val gs1Id = counter.nextGsId()
        val gs1 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff).setGameStateId(gs1Id)
            .setPrevGameStateId(prevGsId)
            .setGameInfo(gs1Info).setUpdate(GameStateUpdate.SendAndRecord)
        // Teams with PendingLoss for losing team
        gs1.addTeams(TeamInfo.newBuilder().setId(losingTeam).addPlayerIds(losingPlayerSeatId).setStatus(TeamStatus.PendingLoss_a458))
        // Players: loser with full state (lifeTotal, maxHandSize, etc.) + PendingLoss status
        val loserPlayer = bridge?.getPlayer(SeatId(losingPlayerSeatId))
        val loserInfo = PlayerMapper.buildPlayerInfo(loserPlayer, losingPlayerSeatId).toBuilder()
            .setStatus(PlayerStatus.PendingLoss_a1c6)
        gs1.addPlayers(loserInfo)
        // Timers (recording shows inactivity timer on gs1)
        gs1.addAllTimers(PlayerMapper.buildTimers())
        // LossOfGame annotation
        if (losingPlayerSeatId != 0) {
            gs1.addAnnotations(AnnotationBuilder.lossOfGame(losingPlayerSeatId, lossReason))
        }

        // gs2: MatchComplete with both Game + Match results
        val gs2Info = baseGameInfo()
            .setMatchState(MatchState.MatchComplete)
            .addResults(gameResult)
            .addResults(matchResult)
        val gs2Id = counter.nextGsId()
        val gs2 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff).setGameStateId(gs2Id)
            .setPrevGameStateId(gs1Id)
            .setGameInfo(gs2Info).setUpdate(GameStateUpdate.SendAndRecord)

        // gs3: bare diff with pendingMessageCount=1 (IntermissionReq follows)
        val gs3Id = counter.nextGsId()
        val gs3 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff).setGameStateId(gs3Id)
            .setPrevGameStateId(gs2Id)
            .setPendingMessageCount(1)
            .setUpdate(GameStateUpdate.SendAndRecord)

        val messages = mutableListOf(
            makeGRE(GREMessageType.GameStateMessage_695e, gs1Id, seatId, counter.nextMsgId()) { it.gameStateMessage = gs1.build() },
            makeGRE(GREMessageType.GameStateMessage_695e, gs2Id, seatId, counter.nextMsgId()) { it.gameStateMessage = gs2.build() },
            makeGRE(GREMessageType.GameStateMessage_695e, gs3Id, seatId, counter.nextMsgId()) { it.gameStateMessage = gs3.build() },
        )

        messages.add(
            makeGRE(GREMessageType.IntermissionReq_695e, gs3Id, seatId, counter.nextMsgId()) {
                it.intermissionReq = IntermissionReq.newBuilder()
                    .setResult(
                        ResultSpec.newBuilder()
                            .setScope(MatchScope.Match)
                            .setResult(ResultType.WinLoss)
                            .setWinningTeamId(winningTeam)
                            .setReason(reason),
                    )
                    .addOptions(
                        UserOption.newBuilder()
                            .setOptionPrompt(Prompt.newBuilder().setPromptId(30))
                            .setResponseType(ClientMessageType.DrawCardResp),
                    )
                    .addOptions(
                        UserOption.newBuilder()
                            .setOptionPrompt(Prompt.newBuilder().setPromptId(29))
                            .setResponseType(ClientMessageType.RevealHandResp),
                    )
                    .setIntermissionPrompt(
                        Prompt.newBuilder()
                            .setPromptId(PromptIds.MATCH_RESULT_WIN_LOSS)
                            .addParameters(
                                PromptParameter.newBuilder()
                                    .setParameterName("WinningTeamId")
                                    .setType(ParameterType.Number)
                                    .setNumberValue(winningTeam),
                            ),
                    )
                    .build()
            },
        )

        return BundleResult(messages)
    }

    /**
     * Timer start: sends [TimerStateMessage] (GRE type 56) with Decision timer running.
     * Real Arena sends this on priority grant — client shows rope countdown.
     */
    fun timerStart(seatId: Int, counter: MessageCounter, durationSec: Int = 30): BundleResult =
        buildTimerBundle(seatId, counter, running = true, durationSec = durationSec)

    /**
     * Timer stop: sends [TimerStateMessage] with running=false.
     * Sent when client responds to an action (pass/cast/play).
     */
    fun timerStop(seatId: Int, counter: MessageCounter, durationSec: Int = 30): BundleResult =
        buildTimerBundle(seatId, counter, running = false, durationSec = durationSec)

    private fun buildTimerBundle(seatId: Int, counter: MessageCounter, running: Boolean, durationSec: Int): BundleResult {
        val timer = TimerStateMessage.newBuilder()
            .setSeatId(seatId)
            .addTimers(
                TimerInfo.newBuilder()
                    .setTimerId(1)
                    .setType(TimerType.Decision)
                    .setDurationSec(durationSec)
                    .setElapsedSec(0)
                    .setRunning(running)
                    .setBehavior(TimerBehavior.Timeout_a3cd),
            )
            .build()
        val msg = makeGRE(GREMessageType.TimerStateMessage_695e, counter.currentGsId(), seatId, counter.nextMsgId()) {
            it.timerStateMessage = timer
        }
        return BundleResult(listOf(msg))
    }

    /** Build a single GRE message. */
    private fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        seatId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val gre = GREToClientMessage.newBuilder()
            .setType(type).setMsgId(msgId).setGameStateId(gsId).addSystemSeatIds(seatId)
        configure(gre)
        return gre.build()
    }
}
