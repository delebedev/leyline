package forge.nexus.game

import forge.game.Game
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Pure functions that build GRE message bundles for each flow milestone.
 *
 * No side effects, no Netty, no mutable handler state — takes everything as params,
 * returns messages + updated counters. Handler sends them; tests assert on them.
 */
object BundleBuilder {

    data class BundleResult(
        val messages: List<GREToClientMessage>,
        val nextMsgId: Int,
        val nextGsId: Int,
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
        msgId: Int,
        gsId: Int,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId

        nextGs++
        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        // Capture previous snapshot BEFORE buildDiffFromGame overwrites it
        val prevSnapshot = bridge.getPreviousState()
        // Build state first (without actions) — triggers instanceId realloc on zone transfers.
        // Then build actions so they reference the new (post-move) instanceIds.
        val gsBase = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)
        val actions = ActionMapper.buildActions(game, seatId, bridge)

        // Detect phase/step change vs previous snapshot and inject PhaseOrStepModified
        val gsWithPhaseAnnotation = if (prevSnapshot != null &&
            gsBase.hasTurnInfo() &&
            prevSnapshot.hasTurnInfo() &&
            (gsBase.turnInfo.phase != prevSnapshot.turnInfo.phase || gsBase.turnInfo.step != prevSnapshot.turnInfo.step)
        ) {
            val handler = game.phaseHandler
            val human = bridge.getPlayer(1)
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
        val gs = StateMapper.embedActions(gsWithPhaseAnnotation, actions, game, bridge, recipientSeatId = seatId)

        val messages = listOf(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
                it.gameStateMessage = gs
            },
            makeGRE(GREMessageType.ActionsAvailableReq_695e, nextGs, seatId, nextMsg++) {
                it.actionsAvailableReq = actions
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
            },
        )

        return BundleResult(messages, nextMsg, nextGs)
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
        msgId: Int,
        gsId: Int,
    ): BundleResult {
        var nextMsg = msgId
        val nextGs = gsId + 1

        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)

        val messages = listOf(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
                it.gameStateMessage = gs
            },
        )

        return BundleResult(messages, nextMsg, nextGs)
    }

    /**
     * AI action diff: single GS Diff with SendHiFi.
     *
     * Real server sends exactly one GSM per AI action — no echo, no
     * pendingMessageCount. Uses SendHiFi (transient update the client
     * doesn't need to persist as a save point). Actions are embedded
     * without the pending flag so the client dispatches immediately.
     */
    fun aiActionDiff(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        msgId: Int,
        gsId: Int,
        phaseChanged: Boolean = false,
        turnStarted: Boolean = false,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId

        val handler = game.phaseHandler
        val human = bridge.getPlayer(1)
        val activeSeat = if (handler.playerTurn == human) 1 else 2
        // Build state first (triggers instanceId realloc), then actions with new IDs
        val gsBase = StateMapper.buildDiffFromGame(
            game,
            ++nextGs,
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
                if (turnStarted) addAnnotations(
                    AnnotationBuilder.newTurnStarted(activeSeat)
                        .toBuilder().setId(bridge.nextAnnotationId()).build(),
                )
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
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
                it.gameStateMessage = gs.build()
            },
        )

        return BundleResult(messages, nextMsg, nextGs)
    }

    /**
     * True when the only action available is Pass (no Cast, Play, Activate).
     * Used to decide whether to auto-pass or send state to the client.
     */
    fun shouldAutoPass(actions: ActionsAvailableReq): Boolean =
        actions.actionsList.all {
            it.actionType == ActionType.Pass ||
                it.actionType == ActionType.FloatMana ||
                it.actionType == ActionType.ActivateMana
        }

    // --- Request builders (delegate to StateMapper) ---
    // MatchSession uses these instead of calling StateMapper directly,
    // keeping StateMapper as an internal dependency of the bundle layer.

    /** Build playable actions for a seat (with legality checks). */
    fun buildActions(game: Game, seatId: Int, bridge: GameBridge): ActionsAvailableReq =
        ActionMapper.buildActions(game, seatId, bridge)

    /** Build a [SelectTargetsReq] from a pending interactive prompt. */
    fun buildSelectTargetsReq(
        prompt: forge.web.game.InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): SelectTargetsReq = StateMapper.buildSelectTargetsReq(prompt, bridge)

    /** Build a [DeclareAttackersReq] listing legal attackers. */
    fun buildDeclareAttackersReq(game: Game, seatId: Int, bridge: GameBridge): DeclareAttackersReq =
        StateMapper.buildDeclareAttackersReq(game, seatId, bridge)

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
        msgId: Int,
        gsId: Int,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId + 1

        val phase = PlayerMapper.mapPhase(game.phaseHandler.phase)
        val step = PlayerMapper.mapStep(game.phaseHandler.phase)
        // Naive actions: always show human's full hand (Cast/Play) regardless of phase.
        // Real server embeds Cast/Play actions regardless of current phase (cosmetic only;
        // actual priority gating uses ActionsAvailableReq sent when human gets priority).
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)
        val handler = game.phaseHandler
        val human = bridge.getPlayer(1)
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
        val gs1 = StateMapper.buildTransitionState(
            game,
            nextGs,
            prevGameStateId = gsId,
            matchId,
            bridge,
            phase,
            step,
            isStageTransition = true,
            actions = actions,
            actionSeatId = seatId,
        )
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
            it.gameStateMessage = gs1
        }

        // Message 2: SendHiFi echo (turnInfo + actions, no annotations)
        val msg1GsId = nextGs
        nextGs++
        val echoBuilder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(nextGs)
            .setPrevGameStateId(msg1GsId)
            .setTurnInfo(turnInfo)
            .setUpdate(GameStateUpdate.SendHiFi)
        embedActions(echoBuilder, actions, seatId, pending = false)
        val msg2 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
            it.gameStateMessage = echoBuilder.build()
        }

        // Message 3: SendAndRecord with 1x PhaseOrStepModified
        val msg2GsId = nextGs
        nextGs++
        val commitBuilder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(nextGs)
            .setPrevGameStateId(msg2GsId)
            .setTurnInfo(turnInfo)
            .addAnnotations(
                AnnotationBuilder.phaseOrStepModified(activeSeat, phase.number, step.number)
                    .toBuilder().setId(bridge.nextAnnotationId()).build(),
            )
            .addAllTimers(PlayerMapper.buildTimers())
            .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(commitBuilder, actions, seatId)
        val msg3 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
            it.gameStateMessage = commitBuilder.build()
        }

        // Message 4: PromptReq (promptId=37)
        val msg4 = makeGRE(GREMessageType.PromptReq, nextGs, seatId, nextMsg++) {
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.STARTING_PLAYER).build())
        }

        // Message 5: ActionsAvailableReq (promptId=2)
        val msg5 = makeGRE(GREMessageType.ActionsAvailableReq_695e, nextGs, seatId, nextMsg++) {
            it.actionsAvailableReq = actions
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
        }

        return BundleResult(listOf(msg1, msg2, msg3, msg4, msg5), nextMsg, nextGs)
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
     * Declare-attackers bundle: Diff (DeclareAttack step) + DeclareAttackersReq (prompt id=6).
     */
    fun declareAttackersBundle(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        msgId: Int,
        gsId: Int,
        prebuiltReq: DeclareAttackersReq? = null,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId + 1

        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
            it.gameStateMessage = gs
        }

        val req = prebuiltReq ?: StateMapper.buildDeclareAttackersReq(game, seatId, bridge)
        val msg2 = makeGRE(GREMessageType.DeclareAttackersReq_695e, nextGs, seatId, nextMsg++) {
            it.declareAttackersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_TARGETS).build())
        }

        return BundleResult(listOf(msg1, msg2), nextMsg, nextGs)
    }

    /**
     * Declare-blockers bundle: Diff (DeclareBlock step) + DeclareBlockersReq (prompt id=7).
     */
    fun declareBlockersBundle(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        msgId: Int,
        gsId: Int,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId + 1

        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
            it.gameStateMessage = gs
        }

        val req = StateMapper.buildDeclareBlockersReq(game, seatId, bridge)
        val msg2 = makeGRE(GREMessageType.DeclareBlockersReq_695e, nextGs, seatId, nextMsg++) {
            it.declareBlockersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
        }

        return BundleResult(listOf(msg1, msg2), nextMsg, nextGs)
    }

    /**
     * Select-targets bundle: GameState + SelectTargetsReq (prompt id=10).
     */
    fun selectTargetsBundle(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        msgId: Int,
        gsId: Int,
        req: SelectTargetsReq,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId + 1

        // Interactive prompts use Send updateType (not SendAndRecord/SendHiFi)
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = GameStateUpdate.Send, viewingSeatId = seatId)
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
            it.gameStateMessage = gs
        }

        val msg2 = makeGRE(GREMessageType.SelectTargetsReq_695e, nextGs, seatId, nextMsg++) {
            it.selectTargetsReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DISTRIBUTE_DAMAGE).build())
        }

        return BundleResult(listOf(msg1, msg2), nextMsg, nextGs)
    }

    /**
     * Wrap a GameStateMessage as QueuedGameStateMessage (type 51) for opponent during prompts.
     */
    fun queuedGameState(
        gameState: GameStateMessage,
        seatId: Int,
        msgId: Int,
        gsId: Int,
    ): GREToClientMessage = makeGRE(GREMessageType.QueuedGameStateMessage, gsId, seatId, msgId) {
        it.gameStateMessage = gameState
    }

    /**
     * Server-forced pass (EdictalMessage). Tells the client "I'm passing priority for seat X".
     * Breaks the client out of autoPassPriority mode so it re-renders action buttons.
     */
    fun edictalPass(seatId: Int, msgId: Int, gsId: Int): BundleResult {
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
        val msg = makeGRE(GREMessageType.EdictalMessage_695e, gsId, seatId, msgId) {
            it.edictalMessage = edictal
        }
        return BundleResult(listOf(msg), msgId + 1, gsId)
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
