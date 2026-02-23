package forge.nexus.game

import forge.game.Game
import forge.game.card.Card
import forge.game.combat.CombatUtil
import forge.game.phase.PhaseType
import forge.game.player.Player
import forge.web.game.InteractivePromptBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Maps live Forge [forge.game.Game] state to the client's [GameStateMessage] protobuf.
 *
 * Core method: [buildFromGame] snapshots zones, players, phase, and card objects
 * from the engine and produces a Full GameStateMessage the MTGA client renders.
 *
 * Zone IDs follow the real server layout (start at 18):
 * - 18-19: Revealed (per player)
 * - 24: Suppressed, 25: Pending, 26: Command
 * - 27: Stack, 28: Battlefield, 29: Exile, 30: Limbo
 * - 31-34: Player 1 (Hand, Library, Graveyard, Sideboard)
 * - 35-38: Player 2 (Hand, Library, Graveyard, Sideboard)
 */
object StateMapper {
    private val log = LoggerFactory.getLogger(StateMapper::class.java)

    /** @see ObjectMapper.STACK_ABILITY_ID_OFFSET */
    private val STACK_ABILITY_ID_OFFSET = ObjectMapper.STACK_ABILITY_ID_OFFSET

    // Zone IDs — see ZoneIds object
    private const val ZONE_REVEALED_P1 = ZoneIds.REVEALED_P1
    private const val ZONE_REVEALED_P2 = ZoneIds.REVEALED_P2
    private const val ZONE_SUPPRESSED = ZoneIds.SUPPRESSED
    private const val ZONE_PENDING = ZoneIds.PENDING
    private const val ZONE_COMMAND = ZoneIds.COMMAND
    private const val ZONE_STACK = ZoneIds.STACK
    private const val ZONE_BATTLEFIELD = ZoneIds.BATTLEFIELD
    private const val ZONE_EXILE = ZoneIds.EXILE
    private const val ZONE_LIMBO = ZoneIds.LIMBO
    private const val ZONE_P1_HAND = ZoneIds.P1_HAND
    private const val ZONE_P1_LIBRARY = ZoneIds.P1_LIBRARY
    private const val ZONE_P1_GRAVEYARD = ZoneIds.P1_GRAVEYARD
    private const val ZONE_P1_SIDEBOARD = ZoneIds.P1_SIDEBOARD
    private const val ZONE_P2_HAND = ZoneIds.P2_HAND
    private const val ZONE_P2_LIBRARY = ZoneIds.P2_LIBRARY
    private const val ZONE_P2_GRAVEYARD = ZoneIds.P2_GRAVEYARD
    private const val ZONE_P2_SIDEBOARD = ZoneIds.P2_SIDEBOARD

    // ---- Three-stage diff pipeline — delegates to AnnotationPipeline ----

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
    ): GameStateMessage {
        val game = bridge.getGame()!!
        val human = bridge.getPlayer(1)
        val ai = bridge.getPlayer(2)

        val zones = mutableListOf<ZoneInfo>()
        val gameObjects = mutableListOf<GameObjectInfo>()

        // Both seats' hand + library only (real server omits graveyard at deal-hand).
        // Only include GameObjectInfo for the viewing seat's hand — opponent's hand
        // cards appear in objectInstanceIds (for count) but render face-down.
        addHandAndLibrary(game, human, 1, bridge, zones, gameObjects, ZONE_P1_HAND, ZONE_P1_LIBRARY, viewingSeatId = seatId)
        addHandAndLibrary(game, ai, 2, bridge, zones, gameObjects, ZONE_P2_HAND, ZONE_P2_LIBRARY, viewingSeatId = seatId)

        // Players — both have pendingMessageType: MulliganResp during mulligan
        val player1 = buildPlayerInfo(human, 1).toBuilder()
            .setPendingMessageType(ClientMessageType.MulliganResp_097b).build()
        val player2 = buildPlayerInfo(ai, 2).toBuilder()
            .setPendingMessageType(ClientMessageType.MulliganResp_097b).build()

        // activePlayer=2 (seat 2 won die roll in template), decisionPlayer=2
        val turnInfo = TurnInfo.newBuilder()
            .setActivePlayer(2).setDecisionPlayer(2)

        // Build actions for the viewing seat's opening hand (Cast/Play from hand)
        val actions = buildActions(game, seatId, bridge)

        val gsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .addPlayers(player1).addPlayers(player2)
            .setTurnInfo(turnInfo)
            .addAllZones(zones.sortedBy { it.zoneId })
            .addAllGameObjects(gameObjects)
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
                    .setAction(stripActionForGsm(action)),
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
            .setMulliganReq(MulliganReq.newBuilder().setMulliganType(MulliganType.London))
            .build()

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
        val human = bridge.getPlayer(1)
        val ai = bridge.getPlayer(2)

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
        val player1 = buildPlayerInfo(human, 1)
        val player2 = buildPlayerInfo(ai, 2).toBuilder()
            .setPendingMessageType(ClientMessageType.ChooseStartingPlayerResp_097b)
            .build()

        val zones = mutableListOf<ZoneInfo>()
        // Shared zones (9)
        zones.add(makeZone(ZONE_REVEALED_P1, ZoneType.Revealed, 1, Visibility.Public))
        zones.add(makeZone(ZONE_REVEALED_P2, ZoneType.Revealed, 2, Visibility.Public))
        zones.add(makeZone(ZONE_SUPPRESSED, ZoneType.Suppressed, 0, Visibility.Public))
        zones.add(makeZone(ZONE_PENDING, ZoneType.Pending, 0, Visibility.Public))
        zones.add(makeZone(ZONE_COMMAND, ZoneType.Command, 0, Visibility.Public))
        zones.add(makeZone(ZONE_STACK, ZoneType.Stack, 0, Visibility.Public))
        zones.add(makeZone(ZONE_BATTLEFIELD, ZoneType.Battlefield, 0, Visibility.Public))
        zones.add(makeZone(ZONE_EXILE, ZoneType.Exile, 0, Visibility.Public))
        zones.add(makeZone(ZONE_LIMBO, ZoneType.Limbo, 0, Visibility.Public))
        // Per-player zones (4 each = 8)
        addInitialPlayerZones(human, 1, bridge, zones, ZONE_P1_HAND, ZONE_P1_LIBRARY, ZONE_P1_GRAVEYARD, ZONE_P1_SIDEBOARD)
        addInitialPlayerZones(ai, 2, bridge, zones, ZONE_P2_HAND, ZONE_P2_LIBRARY, ZONE_P2_GRAVEYARD, ZONE_P2_SIDEBOARD)

        val builder = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(gameStateId)
            .setGameInfo(gameInfo)
            .addTeams(TeamInfo.newBuilder().setId(1).addPlayerIds(1).setStatus(TeamStatus.InGame_a458))
            .addTeams(TeamInfo.newBuilder().setId(2).addPlayerIds(2).setStatus(TeamStatus.InGame_a458))
            .addPlayers(player1).addPlayers(player2)
            .setTurnInfo(TurnInfo.newBuilder().setDecisionPlayer(2))
            .addAllZones(zones.sortedBy { it.zoneId })
            .addAllTimers(buildTimers())
            .setUpdate(GameStateUpdate.SendAndRecord)
        if (pendingMessageCount > 0) builder.setPendingMessageCount(pendingMessageCount)
        return builder.build()
    }

    // --- Delegates to ZoneMapper ---

    private fun addInitialPlayerZones(
        player: Player?,
        seatId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        handZoneId: Int,
        libZoneId: Int,
        gyZoneId: Int,
        sbZoneId: Int,
    ) = ZoneMapper.addInitialPlayerZones(player, seatId, bridge, zones, handZoneId, libZoneId, gyZoneId, sbZoneId)

    // --- Real game state from Forge engine ---

    /**
     * Build a full [GameStateMessage] from live Forge [forge.game.Game] state.
     * Reads zones, players, phase info from the engine and maps cards
     * to client instanceIds via the bridge's card ID mapping.
     *
     * [viewingSeatId] controls hand visibility: opponent's hand cards get
     * objectInstanceIds (for card count) but no GameObjectInfo (renders face-down).
     * Use 0 to include all objects (internal snapshots for diffing).
     */
    fun buildFromGame(
        game: Game,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
    ): GameStateMessage {
        val handler = game.phaseHandler
        val human = bridge.getPlayer(1)
        val ai = bridge.getPlayer(2)

        val gameInfo = GameInfo.newBuilder()
            .setMatchID(matchId)
            .setGameNumber(1)
            .setStage(GameStage.Play_a920)
            .setType(GameType.Duel)
            .setVariant(GameVariant.Normal)
            .setMatchState(MatchState.GameInProgress)
            .setMatchWinCondition(MatchWinCondition.SingleElimination)
            .setMulliganType(MulliganType.London)

        val turnInfo = TurnInfo.newBuilder()
            .setPhase(mapPhase(handler.phase))
            .setStep(mapStep(handler.phase))
            .setTurnNumber(handler.turn.coerceAtLeast(1))
            .setActivePlayer(if (handler.playerTurn == human) 1 else 2)
            .setPriorityPlayer(if (handler.priorityPlayer == human) 1 else 2)
            .setDecisionPlayer(if (handler.priorityPlayer == human) 1 else 2)

        val player1 = buildPlayerInfo(human, 1)
        val player2 = buildPlayerInfo(ai, 2)

        val team1 = TeamInfo.newBuilder().setId(1).addPlayerIds(1).setStatus(TeamStatus.InGame_a458)
        val team2 = TeamInfo.newBuilder().setId(2).addPlayerIds(2).setStatus(TeamStatus.InGame_a458)

        val zones = mutableListOf<ZoneInfo>()
        val gameObjects = mutableListOf<GameObjectInfo>()

        // Standard zone layout (17 zones, IDs 18-38) — must send all for Full state
        zones.add(makeZone(ZONE_REVEALED_P1, ZoneType.Revealed, 1, Visibility.Public))
        zones.add(makeZone(ZONE_REVEALED_P2, ZoneType.Revealed, 2, Visibility.Public))
        zones.add(makeZone(ZONE_SUPPRESSED, ZoneType.Suppressed, 0, Visibility.Public))
        zones.add(makeZone(ZONE_PENDING, ZoneType.Pending, 0, Visibility.Public))
        zones.add(makeZone(ZONE_COMMAND, ZoneType.Command, 0, Visibility.Public))
        zones.add(makeZone(ZONE_STACK, ZoneType.Stack, 0, Visibility.Public))
        zones.add(makeZone(ZONE_BATTLEFIELD, ZoneType.Battlefield, 0, Visibility.Public))
        zones.add(makeZone(ZONE_EXILE, ZoneType.Exile, 0, Visibility.Public))
        // Limbo zone: include all previously accumulated retired instanceIds.
        // New retirements are appended in the annotation loop below.
        val limboZone = ZoneInfo.newBuilder()
            .setZoneId(ZONE_LIMBO)
            .setType(ZoneType.Limbo)
            .setVisibility(Visibility.Public)
        for (id in bridge.getLimboInstanceIds()) {
            limboZone.addObjectInstanceIds(id)
        }
        zones.add(limboZone.build())

        // Player 1 zones
        addPlayerZones(
            game, human, 1, bridge, zones, gameObjects,
            ZONE_P1_HAND, ZONE_P1_LIBRARY, ZONE_P1_GRAVEYARD, viewingSeatId,
        )
        zones.add(makePrivateZone(ZONE_P1_SIDEBOARD, ZoneType.Sideboard, 1))

        // Player 2 zones
        addPlayerZones(
            game, ai, 2, bridge, zones, gameObjects,
            ZONE_P2_HAND, ZONE_P2_LIBRARY, ZONE_P2_GRAVEYARD, viewingSeatId,
        )
        zones.add(makePrivateZone(ZONE_P2_SIDEBOARD, ZoneType.Sideboard, 2))

        // Populate shared zones with any cards
        addSharedZoneCards(
            game,
            ForgeZoneType.Battlefield,
            ZONE_BATTLEFIELD,
            bridge,
            zones,
            gameObjects,
            human,
            ai,
        )
        addSharedZoneCards(
            game,
            ForgeZoneType.Stack,
            ZONE_STACK,
            bridge,
            zones,
            gameObjects,
            human,
            ai,
        )
        addSharedZoneCards(
            game,
            ForgeZoneType.Exile,
            ZONE_EXILE,
            bridge,
            zones,
            gameObjects,
            human,
            ai,
        )

        // Stack abilities (triggers, activated abilities not represented as zone cards)
        addStackAbilities(game, bridge, zones, gameObjects, human)

        log.info(
            "buildFromGame: phase={} turn={} hand={} objects={} zones={}",
            handler.phase,
            handler.turn,
            human?.getZone(ForgeZoneType.Hand)?.size() ?: 0,
            gameObjects.size,
            zones.size,
        )

        // --- Three-stage annotation pipeline (delegated to AnnotationPipeline) ---
        // Stage 1: Detect zone transfers, realloc IDs, get patched objects/zones
        val events = bridge.drainEvents()
        val transferResult = AnnotationPipeline.detectZoneTransfers(gameObjects, zones, bridge, events)
        // Apply deferred tracking side effects
        for (id in transferResult.retiredIds) bridge.retireToLimbo(id)
        for ((iid, zid) in transferResult.zoneRecordings) bridge.recordZone(iid, zid)

        // Stage 2: Generate annotations from transfers (pure, no side effects)
        val actingSeat = if (handler.priorityPlayer == human) 1 else 2
        val annotations = mutableListOf<AnnotationInfo>()
        val persistentAnnotations = mutableListOf<AnnotationInfo>()
        for (transfer in transferResult.transfers) {
            val (transient, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat)
            annotations.addAll(transient)
            persistentAnnotations.addAll(
                persistent.map {
                    it.toBuilder().setId(bridge.nextPersistentAnnotationId()).build()
                },
            )
        }

        // Stage 3: Combat damage annotations (must be added before numbering)
        annotations.addAll(AnnotationPipeline.combatAnnotations(game, bridge))

        // Stage 4: Mechanic annotations (Group B: counters, shuffle, scry, tokens)
        annotations.addAll(
            AnnotationPipeline.mechanicAnnotations(events) { forgeCardId ->
                bridge.getOrAllocInstanceId(forgeCardId)
            },
        )

        val numberedAnnotations = annotations.map {
            it.toBuilder().setId(bridge.nextAnnotationId()).build()
        }

        // prevGameStateId: reference prior state if one exists
        val prevState = bridge.getPreviousState()

        val builder = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(gameStateId)
            .setGameInfo(gameInfo)
            .addAllTeams(listOf(team1.build(), team2.build()))
            .setTurnInfo(turnInfo)
            .addAllPlayers(listOf(player1, player2))
            .addAllZones(transferResult.patchedZones.sortedBy { it.zoneId })
            .addAllGameObjects(transferResult.patchedObjects)
            .addAllAnnotations(numberedAnnotations)
            .addAllPersistentAnnotations(persistentAnnotations)
            .addAllTimers(buildTimers())
            .setUpdate(updateType)
        if (prevState != null && prevState.gameStateId > 0) {
            builder.setPrevGameStateId(prevState.gameStateId)
        }

        // Embed stripped-down actions in GSM (real server uses minimal format:
        // Cast=instanceId+manaCost, Play=instanceId, ActivateMana=instanceId+abilityGrpId,
        // no grpId/facetId/shouldStop/autoTapSolution, no actionId)
        if (actions != null) {
            val activeSeat = if (handler.priorityPlayer == human) 1 else 2
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo.newBuilder()
                        .setSeatId(activeSeat)
                        .setAction(stripActionForGsm(action)),
                )
            }
        }

        return builder.build()
    }

    /**
     * Build a Diff [GameStateMessage] containing only zones/objects that changed
     * since the previous snapshot. Falls back to Full if no previous state exists.
     * Updates the bridge's snapshot after building so the next diff is relative
     * to this state.
     */
    fun buildDiffFromGame(
        game: Game,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
    ): GameStateMessage {
        val prev = bridge.getPreviousState()
            ?: return buildFromGame(game, gameStateId, matchId, bridge, actions, updateType, viewingSeatId)

        // Build current full state (for comparison + to seed next diff).
        // Pass actions=null to avoid redundant action embedding (we embed below).
        // Use viewingSeatId=0 for the comparison base (needs all objects for accurate diff).
        val current = buildFromGame(game, gameStateId, matchId, bridge)

        // Compute changed zones (by objectInstanceIds)
        val prevZoneMap = prev.zonesList.associateBy { it.zoneId }
        val changedZones = current.zonesList.filter { zone ->
            val prevZone = prevZoneMap[zone.zoneId]
            prevZone == null || prevZone.objectInstanceIdsList != zone.objectInstanceIdsList
        }

        // Compute changed/new objects, filtering out opponent hand objects
        val prevObjMap = prev.gameObjectsList.associateBy { it.instanceId }
        val opponentHandZoneId = opponentHandZone(viewingSeatId)
        val changedObjects = current.gameObjectsList.filter { obj ->
            if (opponentHandZoneId != 0 && obj.zoneId == opponentHandZoneId) return@filter false
            val prevObj = prevObjMap[obj.instanceId]
            prevObj == null || prevObj != obj
        }

        // Detect objects in prev but not in current (e.g. abilities leaving stack).
        // Limbo-retired IDs still appear in zone objectInstanceIds, so exclude those.
        val currentObjIds = current.gameObjectsList.map { it.instanceId }.toSet()
        val currentZoneTrackedIds = current.zonesList.flatMap { it.objectInstanceIdsList }.toSet()
        val deletedIds = prev.gameObjectsList
            .map { it.instanceId }
            .filter { it !in currentObjIds && it !in currentZoneTrackedIds }

        val builder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .setTurnInfo(current.turnInfo)
            .addAllPlayers(current.playersList)
            .addAllZones(changedZones.sortedBy { it.zoneId })
            .addAllGameObjects(changedObjects)
            .addAllAnnotations(current.annotationsList)
            .addAllPersistentAnnotations(current.persistentAnnotationsList)
            .addAllTimers(buildTimers())
            .setUpdate(updateType)
            .setPrevGameStateId(prev.gameStateId)

        if (deletedIds.isNotEmpty()) {
            builder.addAllDiffDeletedInstanceIds(deletedIds)
        }

        // Embed stripped-down actions + set pendingMessageCount when AAR follows
        if (actions != null) {
            builder.setPendingMessageCount(1)
            val handler = game.phaseHandler
            val human = bridge.getPlayer(1)
            val activeSeat = if (handler.priorityPlayer == human) 1 else 2
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo.newBuilder()
                        .setSeatId(activeSeat)
                        .setAction(stripActionForGsm(action)),
                )
            }
        }

        // Update snapshot for next diff (reuse the full GSM already built above)
        bridge.snapshotState(current)

        val built = builder.build()
        if (built.gameStateId != 0 && built.gameStateId == built.prevGameStateId) {
            log.error(
                "SELF-REF gsId={} prev.gsId={} prev.prevGsId={} param={} caller={}",
                built.gameStateId,
                prev.gameStateId,
                prev.prevGameStateId,
                gameStateId,
                Thread.currentThread().stackTrace[2].let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" },
            )
        }
        return built
    }

    /** Embed stripped-down actions into an already-built GSM (post-realloc). */
    fun embedActions(
        gsm: GameStateMessage,
        actions: ActionsAvailableReq,
        game: Game,
        bridge: GameBridge,
        recipientSeatId: Int = 0,
    ): GameStateMessage {
        val builder = gsm.toBuilder()
            .setPendingMessageCount(1)
        // Actions are always attributed to the recipient (human) seat,
        // not the active/priority player. Real server embeds the recipient's
        // actions so the client knows what it can do regardless of whose turn it is.
        val seatForActions = if (recipientSeatId != 0) {
            recipientSeatId
        } else {
            val human = bridge.getPlayer(1)
            if (game.phaseHandler.priorityPlayer == human) 1 else 2
        }
        for (action in actions.actionsList) {
            builder.addActions(
                ActionInfo.newBuilder()
                    .setSeatId(seatForActions)
                    .setAction(stripActionForGsm(action)),
            )
        }
        return builder.build()
    }

    /**
     * Build a Diff GameStateMessage for phase transitions (Beginning→Main1 sequence).
     * Matches real server: stage=Play, phase/step, life totals, timers.
     * Optionally embeds actions (for the Main1 state).
     */
    fun buildTransitionState(
        game: Game,
        gameStateId: Int,
        prevGameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        phase: Phase,
        step: Step,
        isStageTransition: Boolean = false,
        actions: ActionsAvailableReq? = null,
        actionSeatId: Int = 0,
    ): GameStateMessage {
        val handler = game.phaseHandler
        val human = bridge.getPlayer(1)

        val activeSeat = if (handler.playerTurn == human) 1 else 2
        val prioritySeat = if (handler.priorityPlayer == human) 1 else 2

        val turnInfo = TurnInfo.newBuilder()
            .setPhase(phase)
            .setStep(step)
            .setTurnNumber(handler.turn.coerceAtLeast(1))
            .setActivePlayer(activeSeat)
            .setPriorityPlayer(prioritySeat)
            .setDecisionPlayer(prioritySeat)

        val builder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .setPrevGameStateId(prevGameStateId)
            .setTurnInfo(turnInfo)
            .addPlayers(buildPlayerInfo(bridge.getPlayer(1), 1))
            .addPlayers(buildPlayerInfo(bridge.getPlayer(2), 2))
            .addAnnotations(
                AnnotationBuilder.phaseOrStepModified(activeSeat, phase.number, step.number)
                    .toBuilder().setId(bridge.nextAnnotationId()).build(),
            ) // phase change
            .addAnnotations(
                AnnotationBuilder.phaseOrStepModified(activeSeat, phase.number, step.number)
                    .toBuilder().setId(bridge.nextAnnotationId()).build(),
            ) // step change
            .addAllTimers(buildTimers())
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
            val embedSeat = if (actionSeatId != 0) actionSeatId else activeSeat
            builder.setPendingMessageCount(1)
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo.newBuilder()
                        .setSeatId(embedSeat)
                        .setAction(stripActionForGsm(action)),
                )
            }
        }

        return builder.build()
    }

    /**
     * Build playable actions for a seat from the current game state.
     * Includes: ActivateMana, Activate, Cast, Play, Pass.
     */
    // --- Delegates to ActionMapper ---

    fun buildActions(game: Game, seatId: Int, bridge: GameBridge): ActionsAvailableReq =
        ActionMapper.buildActions(game, seatId, bridge)

    fun buildNaiveActions(seatId: Int, bridge: GameBridge): ActionsAvailableReq =
        ActionMapper.buildNaiveActions(seatId, bridge)

    // --- Targeting requests ---

    /**
     * Build a [SelectTargetsReq] from an [InteractivePromptBridge.PendingPrompt].
     * Maps prompt candidate refs (entity IDs) to client instanceIds via the bridge.
     */
    fun buildSelectTargetsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): SelectTargetsReq {
        val builder = SelectTargetsReq.newBuilder()
        val selBuilder = TargetSelection.newBuilder()
        for (ref in prompt.request.candidateRefs) {
            val instanceId = bridge.getOrAllocInstanceId(ref.entityId)
            selBuilder.addTargets(
                wotc.mtgo.gre.external.messaging.Messages.Target.newBuilder()
                    .setTargetInstanceId(instanceId)
                    .setLegalAction(SelectAction.Select_a1ad),
            )
        }
        selBuilder.setMinTargets(prompt.request.min)
        selBuilder.setMaxTargets(prompt.request.max)
        builder.addTargets(selBuilder)
        return builder.build()
    }

    /**
     * Build a [SelectNReq] from a pending prompt with candidateRefs.
     * Used for "choose N cards" prompts (discard, sacrifice selection, etc.).
     *
     * Maps prompt candidate entity IDs to client instanceIds. The client
     * responds with SelectNResp containing selected instanceIds.
     */
    fun buildSelectNReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): SelectNReq {
        val builder = SelectNReq.newBuilder()
            .setMinSel(prompt.request.min)
            .setMaxSel(prompt.request.max.coerceAtLeast(prompt.request.min))
            .setContext(SelectionContext.Discard_a163) // TODO: map promptType → context
            .setListType(SelectionListType.Static)
            .setIdType(IdType.InstanceId_ab2c)
            .setValidationType(SelectionValidationType.NonRepeatable)
        for (ref in prompt.request.candidateRefs) {
            val instanceId = bridge.getOrAllocInstanceId(ref.entityId)
            builder.addIds(instanceId)
        }
        builder.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N))
        return builder.build()
    }

    // --- Combat requests ---

    /**
     * Build [DeclareAttackersReq] listing all creatures that can legally attack.
     * Each attacker includes legal damage recipients (opponent player seat).
     */
    fun buildDeclareAttackersReq(game: Game, seatId: Int, bridge: GameBridge): DeclareAttackersReq {
        val player = bridge.getPlayer(seatId) ?: return DeclareAttackersReq.getDefaultInstance()
        val builder = DeclareAttackersReq.newBuilder()

        val opponentSeatId = if (seatId == 1) 2 else 1
        val defaultRecipient = DamageRecipient.newBuilder()
            .setType(DamageRecType.Player_a0e5)
            .setPlayerSystemSeatId(opponentSeatId)
            .build()

        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canAttack(card)) continue

            val instanceId = bridge.getOrAllocInstanceId(card.id)
            val attacker = Attacker.newBuilder()
                .setAttackerInstanceId(instanceId)
                .addLegalDamageRecipients(defaultRecipient)
                .setSelectedDamageRecipient(defaultRecipient)
            builder.addAttackers(attacker)
            builder.addQualifiedAttackers(attacker)
        }
        builder.setCanSubmitAttackers(true)

        log.info("buildDeclareAttackersReq: seat={} attackers={}", seatId, builder.attackersCount)
        return builder.build()
    }

    /**
     * Build [DeclareBlockersReq] listing all creatures that can legally block.
     * Each blocker includes the attacker instanceIds it can block.
     */
    fun buildDeclareBlockersReq(game: Game, seatId: Int, bridge: GameBridge): DeclareBlockersReq {
        val player = bridge.getPlayer(seatId) ?: return DeclareBlockersReq.getDefaultInstance()
        val combat = game.phaseHandler.combat ?: return DeclareBlockersReq.getDefaultInstance()
        val builder = DeclareBlockersReq.newBuilder()

        // Collect attacker instanceIds
        val attackerInstanceIds = combat.attackers.map { bridge.getOrAllocInstanceId(it.id) }

        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canBlock(card, combat)) continue

            val instanceId = bridge.getOrAllocInstanceId(card.id)
            val blocker = Blocker.newBuilder()
                .setBlockerInstanceId(instanceId)
                .addAllAttackerInstanceIds(attackerInstanceIds)
                .setMinAttackers(0)
                .setMaxAttackers(1)
            builder.addBlockers(blocker)
        }

        log.info("buildDeclareBlockersReq: seat={} blockers={}", seatId, builder.blockersCount)
        return builder.build()
    }

    /**
     * Resolve the correct updateType for a game state message.
     * - SendAndRecord: state change the client must persist (zone transfers, actions)
     * - SendHiFi: transient update (phase echoes, state refreshes)
     *
     * Note: real server uses SendAndRecord for ALL zone-transfer diffs, regardless
     * of whose turn it is. This heuristic (acting == viewing) is an approximation
     * used by postAction; aiActionDiff hardcodes SendAndRecord directly.
     */
    fun resolveUpdateType(game: Game, bridge: GameBridge, viewingSeatId: Int): GameStateUpdate {
        val human = bridge.getPlayer(1)
        val actingSeat = if (game.phaseHandler.priorityPlayer == human) 1 else 2
        return if (actingSeat == viewingSeatId) {
            GameStateUpdate.SendAndRecord
        } else {
            GameStateUpdate.SendHiFi
        }
    }

    /** Empty Diff used as priority-pass marker in the double-diff pattern. */
    fun buildEmptyDiff(gameStateId: Int): GameStateMessage =
        GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .addAllTimers(buildTimers())
            .setUpdate(GameStateUpdate.SendHiFi)
            .build()

    // --- Delegates to PlayerMapper ---

    fun mapPhase(phase: PhaseType?): Phase = PlayerMapper.mapPhase(phase)
    fun mapStep(phase: PhaseType?): Step = PlayerMapper.mapStep(phase)
    internal fun buildPlayerInfo(player: Player?, seatId: Int): PlayerInfo = PlayerMapper.buildPlayerInfo(player, seatId)
    internal fun buildTimers(): List<TimerInfo> = PlayerMapper.buildTimers()

    private fun addPlayerZones(
        game: Game,
        player: Player?,
        seatId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        handZoneId: Int,
        libZoneId: Int,
        gyZoneId: Int,
        viewingSeatId: Int = 0,
    ) = ZoneMapper.addPlayerZones(game, player, seatId, bridge, zones, gameObjects, handZoneId, libZoneId, gyZoneId, viewingSeatId)

    private fun addHandAndLibrary(
        game: Game,
        player: Player?,
        seatId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        handZoneId: Int,
        libZoneId: Int,
        viewingSeatId: Int = 0,
    ) = ZoneMapper.addHandAndLibrary(game, player, seatId, bridge, zones, gameObjects, handZoneId, libZoneId, viewingSeatId)

    private fun addSharedZoneCards(
        game: Game,
        forgeZone: ForgeZoneType,
        arenaZoneId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        human: Player?,
        ai: Player?,
    ) = ZoneMapper.addSharedZoneCards(game, forgeZone, arenaZoneId, bridge, zones, gameObjects, human, ai)

    private fun addStackAbilities(
        game: Game,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        human: Player?,
    ) = ZoneMapper.addStackAbilities(game, bridge, zones, gameObjects, human)

    // --- Delegates to ObjectMapper ---

    private fun buildCardObject(card: Card, instanceId: Int, zoneId: Int, ownerSeatId: Int): GameObjectInfo =
        ObjectMapper.buildCardObject(card, instanceId, zoneId, ownerSeatId)

    private fun passOnlyActions(): ActionsAvailableReq = ActionMapper.passOnlyActions()

    /** @see AnnotationPipeline.inferCategory */
    internal fun inferCategory(obj: GameObjectInfo, srcZone: Int, destZone: Int): TransferCategory =
        AnnotationPipeline.inferCategory(obj, srcZone, destZone)

    internal fun stripActionForGsm(action: Action): Action = ActionMapper.stripActionForGsm(action)

    // --- Delegates to ZoneMapper (helpers) ---

    private fun makeZone(zoneId: Int, type: ZoneType, ownerSeatId: Int, visibility: Visibility): ZoneInfo =
        ZoneMapper.makeZone(zoneId, type, ownerSeatId, visibility)

    private fun opponentHandZone(viewingSeatId: Int): Int = ZoneMapper.opponentHandZone(viewingSeatId)

    private fun makePrivateZone(zoneId: Int, type: ZoneType, ownerSeatId: Int): ZoneInfo =
        ZoneMapper.makePrivateZone(zoneId, type, ownerSeatId)
}
