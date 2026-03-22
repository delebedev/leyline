package leyline.game

import forge.game.Game
import leyline.bridge.SeatId
import leyline.game.mapper.ActionMapper
import leyline.game.mapper.PlayerMapper
import leyline.game.mapper.PromptIds
import leyline.game.mapper.ZoneIds
import leyline.game.mapper.ZoneMapper
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Frozen snapshot of turn/phase/seat state for proto construction.
 * No bridge ref, no game ref — plain values.
 *
 * Created once per bundle call via [from], then threaded through
 * GSM builders that need seat/phase info.
 */
data class GsmFrame(
    val activeSeat: Int,
    val prioritySeat: Int,
    val turnNumber: Int,
    val phase: Phase,
    val step: Step,
) {
    /** Build a [TurnInfo] proto from this frame's fields. */
    fun turnInfo(): TurnInfo = TurnInfo.newBuilder()
        .setPhase(phase)
        .setStep(step)
        .setTurnNumber(turnNumber)
        .setActivePlayer(activeSeat)
        .setPriorityPlayer(prioritySeat)
        .setDecisionPlayer(prioritySeat)
        .build()

    /** Build a PhaseOrStepModified annotation, assigning an ID from [idSource]. */
    fun phaseAnnotation(idSource: () -> Int): AnnotationInfo =
        AnnotationBuilder.phaseOrStepModified(activeSeat, phase.number, step.number)
            .toBuilder().setId(idSource()).build()

    companion object {
        /** Snapshot current game state into a frame. */
        fun from(game: forge.game.Game, bridge: GameBridge): GsmFrame {
            val handler = game.phaseHandler
            val human = bridge.getPlayer(SeatId(1))
            return GsmFrame(
                activeSeat = if (handler.playerTurn == human) 1 else 2,
                prioritySeat = if (handler.priorityPlayer == human) 1 else 2,
                turnNumber = handler.turn.coerceAtLeast(1),
                phase = PlayerMapper.mapPhase(handler.phase),
                step = PlayerMapper.mapStep(handler.phase),
            )
        }
    }
}

/**
 * Lifecycle GameStateMessage factories — pre-game, mulligan, phase transition,
 * game-over, and utility GSMs.
 *
 * Pure proto construction. No session state, no bridge mutation.
 * Extracted from [StateMapper] which retains [StateMapper.buildFromGame] and
 * [StateMapper.buildDiffFromGame] (the stateful diff pipeline).
 */
object GsmBuilder {
    private val log = LoggerFactory.getLogger(GsmBuilder::class.java)

    /**
     * Build a DeckMessage from a list of grpIds (one per card in the deck).
     */
    fun buildDeckMessage(deckGrpIds: List<Int>): DeckMessage {
        val builder = DeckMessage.newBuilder()
        deckGrpIds.forEach { builder.addDeckCards(it) }
        return builder.build()
    }

    /**
     * Build a DealHand GSM (Diff) for a seat at mulligan time.
     * Shows both players' hand/library zones with card objects for the target seat's hand.
     */
    fun buildDealHand(
        bridge: GameBridge,
        gameStateId: Int,
        seatId: Int,
        diffDeletedInstanceIds: List<Int> = emptyList(),
    ): GameStateMessage {
        val game = bridge.getGame()!!
        val human = bridge.getPlayer(SeatId(1))
        val ai = bridge.getPlayer(SeatId(2))

        val zones = mutableListOf<ZoneInfo>()
        val gameObjects = mutableListOf<GameObjectInfo>()

        // Both seats' hand + library only (real server omits graveyard at deal-hand).
        // Only include GameObjectInfo for the viewing seat's hand — opponent's hand
        // cards appear in objectInstanceIds (for count) but render face-down.
        ZoneMapper.addHandAndLibrary(game, human, 1, bridge, zones, gameObjects, ZoneIds.P1_HAND, ZoneIds.P1_LIBRARY, viewingSeatId = seatId)
        ZoneMapper.addHandAndLibrary(game, ai, 2, bridge, zones, gameObjects, ZoneIds.P2_HAND, ZoneIds.P2_LIBRARY, viewingSeatId = seatId)

        // Players — both have pendingMessageType: MulliganResp during mulligan
        val player1 = PlayerMapper.buildPlayerInfo(human, 1).toBuilder()
            .setPendingMessageType(ClientMessageType.MulliganResp_097b).build()
        val player2 = PlayerMapper.buildPlayerInfo(ai, 2).toBuilder()
            .setPendingMessageType(ClientMessageType.MulliganResp_097b).build()

        // activePlayer=2 (seat 2 won die roll in template), decisionPlayer=2
        val turnInfo = TurnInfo.newBuilder()
            .setActivePlayer(2).setDecisionPlayer(2)

        // Build actions for the viewing seat's opening hand (Cast/Play from hand)
        val actions = ActionMapper.buildActions(game, seatId, bridge)

        val gsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .addPlayers(player1).addPlayers(player2)
            .setTurnInfo(turnInfo)
            .addAllZones(zones.sortedBy { it.zoneId })
            .addAllGameObjects(gameObjects)
            .addAllDiffDeletedInstanceIds(diffDeletedInstanceIds)
            .addAnnotations(
                AnnotationInfo.newBuilder().setId(49)
                    .setAffectorId(2).addAffectedIds(2)
                    .addType(AnnotationType.NewTurnStarted),
            )
            .setPrevGameStateId(gameStateId - 1)
            .setUpdate(GameStateUpdate.SendAndRecord)

        // Embed stripped actions matching real server deal-hand shape
        for (action in actions.actionsList) {
            gsm.addActions(
                ActionInfo.newBuilder()
                    .setSeatId(seatId)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }

        return gsm.build()
    }

    /**
     * Build a MulliganReq GRE message.
     */
    fun buildMulliganReq(
        msgId: Int,
        gameStateId: Int,
        seatId: Int,
        numCards: Int = 7,
        mulliganCount: Int = 0,
    ): GREToClientMessage =
        GREToClientMessage.newBuilder()
            .setType(GREMessageType.MulliganReq_aa0d)
            .addSystemSeatIds(seatId)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .setPrompt(
                Prompt.newBuilder().setPromptId(PromptIds.MULLIGAN)
                    .addParameters(
                        PromptParameter.newBuilder()
                            .setParameterName("NumberOfCards")
                            .setType(ParameterType.Number)
                            .setNumberValue(numCards),
                    ),
            )
            .setMulliganReq(
                MulliganReq.newBuilder()
                    .setMulliganType(MulliganType.London)
                    .setFreeMulliganCount(0)
                    .setMulliganCount(mulliganCount),
            )
            .build()

    /**
     * Build a GroupReq GRE message for London mulligan tuck.
     *
     * Real server sends TWO groupSpecs:
     *   1. Keep group: (handSize - cardsToTuck) cards stay in Hand/Top
     *   2. Tuck group: cardsToTuck cards go to Library/Bottom
     * groupType=Ordered because the player must order cards going to bottom.
     */
    fun buildGroupReq(
        msgId: Int,
        gameStateId: Int,
        seatId: Int,
        handInstanceIds: List<Int>,
        cardsToTuck: Int,
    ): GREToClientMessage {
        val keepCount = handInstanceIds.size - cardsToTuck
        return GREToClientMessage.newBuilder()
            .setType(GREMessageType.GroupReq_695e)
            .addSystemSeatIds(seatId)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .setPrompt(
                Prompt.newBuilder().setPromptId(PromptIds.GROUP_SCRY)
                    .addParameters(
                        PromptParameter.newBuilder()
                            .setParameterName("CardId")
                            .setType(ParameterType.Number),
                    ),
            )
            .setGroupReq(
                GroupReq.newBuilder()
                    .addAllInstanceIds(handInstanceIds)
                    .addGroupSpecs(
                        GroupSpecification.newBuilder()
                            .setLowerBound(keepCount)
                            .setUpperBound(keepCount)
                            .setZoneType(ZoneType.Hand)
                            .setSubZoneType(SubZoneType.Top),
                    )
                    .addGroupSpecs(
                        GroupSpecification.newBuilder()
                            .setLowerBound(cardsToTuck)
                            .setUpperBound(cardsToTuck)
                            .setZoneType(ZoneType.Library)
                            .setSubZoneType(SubZoneType.Bottom),
                    )
                    .setGroupType(GroupType.Ordered)
                    .setContext(GroupingContext.LondonMulligan)
                    .setSourceId(seatId),
            )
            .setAllowCancel(AllowCancel.No_a526)
            .build()
    }

    /**
     * Build a GroupReq for surveil/scry: put cards on top of library or into graveyard/bottom.
     *
     * Surveil 1: one card, two groups — Library/Top (keep) and Graveyard/None (away).
     * Scry 1: one card, two groups — Library/Top (keep) and Library/Bottom (away).
     *
     * @param context [GroupingContext.Surveil] or [GroupingContext.Scry_a0f6]
     * @param sourceInstanceId the card that triggered the surveil/scry (for sourceId)
     */
    fun buildSurveilScryGroupReq(
        msgId: Int,
        gameStateId: Int,
        seatId: Int,
        cardInstanceIds: List<Int>,
        context: GroupingContext,
        sourceInstanceId: Int,
    ): GREToClientMessage {
        val promptId = when (context) {
            GroupingContext.Surveil -> PromptIds.GROUP_SURVEIL
            GroupingContext.Scry_a0f6 -> PromptIds.GROUP_SCRY
            else -> PromptIds.GROUP_SURVEIL
        }
        val awayZone = when (context) {
            GroupingContext.Surveil -> ZoneType.Graveyard
            else -> ZoneType.Library // scry puts on bottom
        }
        val awaySubZone = when (context) {
            GroupingContext.Surveil -> SubZoneType.None_a455
            else -> SubZoneType.Bottom
        }
        return GREToClientMessage.newBuilder()
            .setType(GREMessageType.GroupReq_695e)
            .addSystemSeatIds(seatId)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .setPrompt(
                Prompt.newBuilder().setPromptId(promptId)
                    .addParameters(
                        PromptParameter.newBuilder()
                            .setParameterName("CardId")
                            .setType(ParameterType.Number),
                    ),
            )
            .setGroupReq(
                GroupReq.newBuilder()
                    .addAllInstanceIds(cardInstanceIds)
                    .addGroupSpecs(
                        GroupSpecification.newBuilder()
                            .setLowerBound(0)
                            .setUpperBound(cardInstanceIds.size)
                            .setZoneType(ZoneType.Library)
                            .setSubZoneType(SubZoneType.Top),
                    )
                    .addGroupSpecs(
                        GroupSpecification.newBuilder()
                            .setLowerBound(0)
                            .setUpperBound(cardInstanceIds.size)
                            .setZoneType(awayZone)
                            .setSubZoneType(awaySubZone),
                    )
                    .setGroupType(GroupType.Ordered)
                    .setContext(context)
                    .setSourceId(sourceInstanceId),
            )
            .setAllowCancel(AllowCancel.No_a526)
            .build()
    }

    /**
     * Build the initial Full [GameStateMessage] for the connection bundle.
     * Zones show the pre-deal state: all cards in library, hands empty.
     * No game objects (cards are face-down).
     */
    fun buildInitialGameState(
        matchId: String,
        gameStateId: Int,
        seatId: Int,
        bridge: GameBridge,
        pendingMessageCount: Int = 0,
    ): GameStateMessage {
        val human = bridge.getPlayer(SeatId(1))
        val ai = bridge.getPlayer(SeatId(2))

        val gameInfo = GameInfo.newBuilder()
            .setMatchID(matchId)
            .setGameNumber(1)
            .setStage(GameStage.Start_a920)
            .setType(GameType.Duel)
            .setVariant(GameVariant.Normal)
            .setMatchState(MatchState.GameInProgress)
            .setMatchWinCondition(MatchWinCondition.SingleElimination)
            .setSuperFormat(SuperFormat.Constructed)
            .setMulliganType(MulliganType.London)
            .setDeckConstraintInfo(
                DeckConstraintInfo.newBuilder()
                    .setMinDeckSize(60).setMaxDeckSize(250).setMaxSideboardSize(15),
            )

        // Seat 2 has pending ChooseStartingPlayerResp
        val player1 = PlayerMapper.buildPlayerInfo(human, 1)
        val player2 = PlayerMapper.buildPlayerInfo(ai, 2).toBuilder()
            .setPendingMessageType(ClientMessageType.ChooseStartingPlayerResp_097b)
            .build()

        val zones = mutableListOf<ZoneInfo>()
        // Shared zones (9)
        zones.add(ZoneMapper.makeZone(ZoneIds.REVEALED_P1, ZoneType.Revealed, 1, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.REVEALED_P2, ZoneType.Revealed, 2, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.SUPPRESSED, ZoneType.Suppressed, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.PENDING, ZoneType.Pending, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.COMMAND, ZoneType.Command, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.STACK, ZoneType.Stack, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.EXILE, ZoneType.Exile, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.LIMBO, ZoneType.Limbo, 0, Visibility.Public))
        // Per-player zones (4 each = 8)
        ZoneMapper.addInitialPlayerZones(human, 1, bridge, zones, ZoneIds.P1_HAND, ZoneIds.P1_LIBRARY, ZoneIds.P1_GRAVEYARD, ZoneIds.P1_SIDEBOARD)
        ZoneMapper.addInitialPlayerZones(ai, 2, bridge, zones, ZoneIds.P2_HAND, ZoneIds.P2_LIBRARY, ZoneIds.P2_GRAVEYARD, ZoneIds.P2_SIDEBOARD)

        val builder = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(gameStateId)
            .setGameInfo(gameInfo)
            .addTeams(TeamInfo.newBuilder().setId(1).addPlayerIds(1).setStatus(TeamStatus.InGame_a458))
            .addTeams(TeamInfo.newBuilder().setId(2).addPlayerIds(2).setStatus(TeamStatus.InGame_a458))
            .addPlayers(player1).addPlayers(player2)
            .setTurnInfo(TurnInfo.newBuilder().setDecisionPlayer(2))
            .addAllZones(zones.sortedBy { it.zoneId })
            .addAllTimers(PlayerMapper.buildTimers())
            .setUpdate(GameStateUpdate.SendAndRecord)
        if (pendingMessageCount > 0) builder.setPendingMessageCount(pendingMessageCount)
        return builder.build()
    }

    /**
     * Build a Diff GameStateMessage for phase transitions (Beginning→Main1 sequence).
     * Matches real server: stage=Play, phase/step, life totals, timers.
     * Optionally embeds actions (for the Main1 state).
     */
    fun buildTransitionState(
        gameStateId: Int,
        prevGameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        frame: GsmFrame,
        isStageTransition: Boolean = false,
        actions: ActionsAvailableReq? = null,
        actionSeatId: Int = 0,
    ): GameStateMessage {
        val builder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .setPrevGameStateId(prevGameStateId)
            .setTurnInfo(frame.turnInfo())
            .addPlayers(PlayerMapper.buildPlayerInfo(bridge.getPlayer(SeatId(1)), 1))
            .addPlayers(PlayerMapper.buildPlayerInfo(bridge.getPlayer(SeatId(2)), 2))
            .addAnnotations(frame.phaseAnnotation { bridge.nextAnnotationId() }) // phase change
            .addAnnotations(frame.phaseAnnotation { bridge.nextAnnotationId() }) // step change
            .addAllTimers(PlayerMapper.buildTimers())
            .setUpdate(GameStateUpdate.SendHiFi)

        if (isStageTransition) {
            builder.setGameInfo(
                GameInfo.newBuilder()
                    .setMatchID(matchId)
                    .setStage(GameStage.Play_a920)
                    .setMatchState(MatchState.GameInProgress)
                    .setMulliganType(MulliganType.London),
            )
        }

        // Embed stripped-down actions when AAR follows.
        // actionSeatId = recipient seat (human), not necessarily the active player.
        if (actions != null) {
            val embedSeat = if (actionSeatId != 0) actionSeatId else frame.activeSeat
            builder.setPendingMessageCount(1)
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo.newBuilder()
                        .setSeatId(embedSeat)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
        }

        return builder.build()
    }

    /** Empty Diff used as priority-pass marker in the double-diff pattern. */
    fun buildEmptyDiff(gameStateId: Int): GameStateMessage =
        GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .addAllTimers(PlayerMapper.buildTimers())
            .setUpdate(GameStateUpdate.SendHiFi)
            .build()

    /**
     * Embed stripped-down actions into an already-built GSM (post-realloc).
     *
     * Used by [BundleBuilder.postAction] to re-embed actions after zone-transfer
     * instanceId reallocation and phase annotations have been applied.
     */
    fun embedActions(
        gsm: GameStateMessage,
        actions: ActionsAvailableReq,
        frame: GsmFrame,
        recipientSeatId: Int = 0,
    ): GameStateMessage {
        val builder = gsm.toBuilder()
            .setPendingMessageCount(1)
        val seatForActions = if (recipientSeatId != 0) recipientSeatId else frame.prioritySeat
        for (action in actions.actionsList) {
            builder.addActions(
                ActionInfo.newBuilder()
                    .setSeatId(seatForActions)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }
        return builder.build()
    }
}
