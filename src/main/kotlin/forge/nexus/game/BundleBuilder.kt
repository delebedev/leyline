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
     * Game-start bundle (post-keep):
     *   GRE 1: Diff, Beginning/Upkeep, SendHiFi (stage transition)
     *   GRE 2: Diff, empty priority-pass marker (gsId++)
     *   GRE 3: Full, Main1, SendAndRecord, zones + objects + actions
     *   GRE 4: ActionsAvailableReq
     */
    fun gameStart(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        msgId: Int,
        gsId: Int,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId
        val messages = mutableListOf<GREToClientMessage>()

        // GRE 1: Beginning/Upkeep transition (stage → Play)
        nextGs++
        val beginGs = StateMapper.buildTransitionState(
            game,
            nextGs,
            matchId,
            bridge,
            Phase.Beginning_a549,
            Step.Upkeep_a2cb,
            isStageTransition = true,
        )
        messages.add(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
                it.gameStateMessage = beginGs
            },
        )

        // GRE 2: empty priority-pass marker (double-diff pattern)
        nextGs++
        messages.add(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
                it.gameStateMessage = StateMapper.buildEmptyDiff(nextGs)
            },
        )

        // GRE 3: Full state at Main1 with zones, objects, and actions.
        // Must be Full (not Diff) because prior states used template instanceIds
        // that don't match the bridge's ID mapping.
        nextGs++
        val actions = StateMapper.buildActions(game, seatId, bridge)
        val mainGs = StateMapper.buildFromGame(
            game,
            nextGs,
            matchId,
            bridge,
            actions,
            updateType = StateMapper.resolveUpdateType(game, bridge, seatId),
            viewingSeatId = seatId,
        )
        messages.add(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
                it.gameStateMessage = mainGs
            },
        )

        // GRE 4: ActionsAvailableReq
        messages.add(
            makeGRE(GREMessageType.ActionsAvailableReq_695e, nextGs, seatId, nextMsg++) {
                it.actionsAvailableReq = actions
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
            },
        )

        return BundleResult(messages, nextMsg, nextGs)
    }

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
        // Build state first (without actions) — triggers instanceId realloc on zone transfers.
        // Then build actions so they reference the new (post-move) instanceIds.
        val gsBase = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)
        val actions = StateMapper.buildActions(game, seatId, bridge)
        // Re-embed stripped actions into the GSM
        val gs = StateMapper.embedActions(gsBase, actions, game, bridge)

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
     * AI action diff: GS Diff + empty marker for opponent-perspective state updates.
     *
     * Always uses SendHiFi (opponent viewing AI's actions). No ActionsAvailableReq.
     * Produces the double-diff pattern matching real server AI turn messages:
     *   1. GS Diff with annotations (zone transfers, state changes)
     *   2. GS Diff empty marker (turnInfo only)
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
        val prioritySeat = if (handler.priorityPlayer == human) 1 else 2
        // Build state first (triggers instanceId realloc), then actions with new IDs
        val gsBase = StateMapper.buildDiffFromGame(
            game,
            ++nextGs,
            matchId,
            bridge,
            updateType = GameStateUpdate.SendHiFi,
            viewingSeatId = seatId,
        )
        val actions = StateMapper.buildActions(game, seatId, bridge)

        // Message 1: Diff with annotations + actions
        val gsWithAnnotations = if (phaseChanged || turnStarted) {
            gsBase.toBuilder().apply {
                if (turnStarted) addAnnotations(AnnotationBuilder.newTurnStarted())
                if (phaseChanged) {
                    addAnnotations(AnnotationBuilder.phaseOrStepModified())
                    addAnnotations(AnnotationBuilder.phaseOrStepModified())
                }
            }.build()
        } else {
            gsBase
        }
        val gs = StateMapper.embedActions(gsWithAnnotations, actions, game, bridge)

        // Message 2: Echo with turnInfo + actions (matches real server pattern)
        val turnInfo = TurnInfo.newBuilder()
            .setPhase(StateMapper.mapPhase(handler.phase))
            .setStep(StateMapper.mapStep(handler.phase))
            .setTurnNumber(handler.turn.coerceAtLeast(1))
            .setActivePlayer(activeSeat)
            .setPriorityPlayer(prioritySeat)
            .setDecisionPlayer(prioritySeat)
        val msg1GsId = nextGs
        val echoBuilder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(++nextGs)
            .setPrevGameStateId(msg1GsId)
            .setTurnInfo(turnInfo)
            .setUpdate(GameStateUpdate.SendHiFi)
        embedActions(echoBuilder, actions, activeSeat, pending = false)

        val messages = listOf(
            makeGRE(GREMessageType.GameStateMessage_695e, msg1GsId, seatId, nextMsg++) {
                it.gameStateMessage = gs
            },
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
                it.gameStateMessage = echoBuilder.build()
            },
        )

        return BundleResult(messages, nextMsg, nextGs)
    }

    /**
     * True when the only action available is Pass (no Cast, Play, Activate).
     * Used to decide whether to auto-pass or send state to the client.
     */
    fun shouldAutoPass(actions: ActionsAvailableReq): Boolean =
        actions.actionsList.all { it.actionType == ActionType.Pass || it.actionType == ActionType.FloatMana }

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

        val phase = StateMapper.mapPhase(game.phaseHandler.phase)
        val step = StateMapper.mapStep(game.phaseHandler.phase)
        val actions = StateMapper.buildActions(game, seatId, bridge)
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
            matchId,
            bridge,
            phase,
            step,
            isStageTransition = true,
            actions = actions,
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
        embedActions(echoBuilder, actions, activeSeat, pending = false)
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
            .addAnnotations(AnnotationBuilder.phaseOrStepModified())
            .addAllTimers(StateMapper.buildTimers())
            .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(commitBuilder, actions, activeSeat)
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
                    .setAction(StateMapper.stripActionForGsm(action)),
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
        val gs = StateMapper.buildFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)
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
        val gs = StateMapper.buildFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)
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
        val gs = StateMapper.buildFromGame(game, nextGs, matchId, bridge, updateType = GameStateUpdate.Send, viewingSeatId = seatId)
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
