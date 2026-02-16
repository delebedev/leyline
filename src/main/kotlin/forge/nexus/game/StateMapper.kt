package forge.nexus.game

import forge.ai.ComputerUtilMana
import forge.game.Game
import forge.game.card.Card
import forge.game.card.CardLists
import forge.game.card.CardPredicates
import forge.game.combat.CombatUtil
import forge.game.phase.PhaseType
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.web.game.InteractivePromptBridge
import forge.web.game.chooseCastAbility
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Maps live Forge [forge.game.Game] state to Arena's [GameStateMessage] protobuf.
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

    /** Offset added to source card IDs for stack ability instance IDs. */
    private const val STACK_ABILITY_ID_OFFSET = 100_000

    // Zone IDs (matching real server layout, starting at 18)
    private const val ZONE_REVEALED_P1 = 18
    private const val ZONE_REVEALED_P2 = 19
    private const val ZONE_SUPPRESSED = 24
    private const val ZONE_PENDING = 25
    private const val ZONE_COMMAND = 26
    private const val ZONE_STACK = 27
    private const val ZONE_BATTLEFIELD = 28
    private const val ZONE_EXILE = 29
    private const val ZONE_LIMBO = 30
    private const val ZONE_P1_HAND = 31
    private const val ZONE_P1_LIBRARY = 32
    private const val ZONE_P1_GRAVEYARD = 33
    private const val ZONE_P1_SIDEBOARD = 34
    private const val ZONE_P2_HAND = 35
    private const val ZONE_P2_LIBRARY = 36
    private const val ZONE_P2_GRAVEYARD = 37
    private const val ZONE_P2_SIDEBOARD = 38

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

        // Both seats' hand + library only (real server omits graveyard at deal-hand)
        addHandAndLibrary(game, human, 1, bridge, zones, gameObjects, ZONE_P1_HAND, ZONE_P1_LIBRARY)
        addHandAndLibrary(game, ai, 2, bridge, zones, gameObjects, ZONE_P2_HAND, ZONE_P2_LIBRARY)

        // Players — both have pendingMessageType: MulliganResp during mulligan
        val player1 = buildPlayerInfo(human, 1).toBuilder()
            .setPendingMessageType(ClientMessageType.MulliganResp_097b)
            .setControllerSeatId(1).build()
        val player2 = buildPlayerInfo(ai, 2).toBuilder()
            .setPendingMessageType(ClientMessageType.MulliganResp_097b)
            .setControllerSeatId(2).build()

        // activePlayer=2 (seat 2 won die roll in template), decisionPlayer=2
        val turnInfo = TurnInfo.newBuilder()
            .setActivePlayer(2).setDecisionPlayer(2)

        val gsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .addPlayers(player1).addPlayers(player2)
            .setTurnInfo(turnInfo)
            .addAllZones(zones)
            .addAllGameObjects(gameObjects)
            .addAnnotations(
                AnnotationInfo.newBuilder().setId(49)
                    .setAffectorId(2).addAffectedIds(2)
                    .addType(AnnotationType.NewTurnStarted),
            )
            .setPrevGameStateId(gameStateId - 1)
            .setUpdate(GameStateUpdate.SendAndRecord)

        // Only include actions for the target seat (stripped for GSM)
        // At deal-hand time, actions are stale — clear them like the template does
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
                Prompt.newBuilder().setPromptId(34)
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
            .addAllZones(zones)
            .addAllTimers(buildTimers())
            .setUpdate(GameStateUpdate.SendAndRecord)
        if (pendingMessageCount > 0) builder.setPendingMessageCount(pendingMessageCount)
        return builder.build()
    }

    /** Player zones for initial bundle: empty hand, full library, empty graveyard/sideboard. */
    private fun addInitialPlayerZones(
        player: Player?,
        seatId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        handZoneId: Int,
        libZoneId: Int,
        gyZoneId: Int,
        sbZoneId: Int,
    ) {
        if (player == null) return
        // Hand — empty, with viewer
        zones.add(
            ZoneInfo.newBuilder().setZoneId(handZoneId).setType(ZoneType.Hand)
                .setOwnerSeatId(seatId).setVisibility(Visibility.Private).addViewers(seatId).build(),
        )
        // Library — all cards (hand + library combined = full deck, pre-deal)
        val libBuilder = ZoneInfo.newBuilder().setZoneId(libZoneId).setType(ZoneType.Library)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Hidden)
        for (card in player.getZone(ForgeZoneType.Library).cards) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(card.id))
        }
        for (card in player.getZone(ForgeZoneType.Hand).cards) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(card.id))
        }
        zones.add(libBuilder.build())
        // Graveyard — empty
        zones.add(makeZone(gyZoneId, ZoneType.Graveyard, seatId, Visibility.Public))
        // Sideboard — empty, with viewer
        zones.add(
            ZoneInfo.newBuilder().setZoneId(sbZoneId).setType(ZoneType.Sideboard)
                .setOwnerSeatId(seatId).setVisibility(Visibility.Private).addViewers(seatId).build(),
        )
    }

    // --- Real game state from Forge engine ---

    // TODO(per-seat): Send different GameStateMessage per seat.
    // Currently: both seats see the same state including opponent's hand objects.
    // For PvP: filter out opponent hand's GameObjectInfo, set opponent hand
    // zone visibility to Hidden, adjust objectInstanceIds count.
    // Not needed for vs-Sparky (AI doesn't use the protocol).
    // See: forge-arena-in-depth/reference/gre-protocol-reference.md "Message routing"

    /**
     * Build a full [GameStateMessage] from live Forge [forge.game.Game] state.
     * Reads zones, players, phase info from the engine and maps cards
     * to Arena instanceIds via the bridge's card ID mapping.
     */
    fun buildFromGame(
        game: Game,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
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
            .setMatchWinCondition(MatchWinCondition.Best2Of3)
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
        zones.add(makeZone(ZONE_LIMBO, ZoneType.Limbo, 0, Visibility.Hidden))

        // Player 1 zones
        addPlayerZones(
            game, human, 1, bridge, zones, gameObjects,
            ZONE_P1_HAND, ZONE_P1_LIBRARY, ZONE_P1_GRAVEYARD,
        )
        zones.add(makeZone(ZONE_P1_SIDEBOARD, ZoneType.Sideboard, 1, Visibility.Private))

        // Player 2 zones
        addPlayerZones(
            game, ai, 2, bridge, zones, gameObjects,
            ZONE_P2_HAND, ZONE_P2_LIBRARY, ZONE_P2_GRAVEYARD,
        )
        zones.add(makeZone(ZONE_P2_SIDEBOARD, ZoneType.Sideboard, 2, Visibility.Private))

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

        // Annotations: detect zone transfers by comparing current vs previous zone.
        // Each category gets companion annotations matching the real server:
        //   PlayLand: ObjectIdChanged + UserActionTaken + ZoneTransfer(PlayLand)
        //   CastSpell: ObjectIdChanged + UserActionTaken + ManaPaid + TappedUntappedPermanent
        //              + AbilityInstanceCreated + ZoneTransfer(CastSpell)
        //   Resolve: ResolutionStart + ZoneTransfer(Resolve) + ResolutionComplete
        val annotations = mutableListOf<AnnotationInfo>()
        for (obj in gameObjects) {
            val prevZone = bridge.getPreviousZone(obj.instanceId)
            if (prevZone != null && prevZone != obj.zoneId) {
                val category = inferCategory(obj, prevZone, obj.zoneId)
                when (category) {
                    "PlayLand" -> {
                        annotations.add(AnnotationBuilder.objectIdChanged(obj.instanceId))
                        annotations.add(AnnotationBuilder.userActionTaken(obj.instanceId))
                    }
                    "CastSpell" -> {
                        annotations.add(AnnotationBuilder.objectIdChanged(obj.instanceId))
                        annotations.add(AnnotationBuilder.userActionTaken(obj.instanceId))
                        annotations.add(AnnotationBuilder.userActionTaken(obj.instanceId)) // mana payment action
                        annotations.add(AnnotationBuilder.manaPaid(obj.instanceId))
                        annotations.add(AnnotationBuilder.tappedUntappedPermanent(obj.instanceId))
                        annotations.add(AnnotationBuilder.abilityInstanceCreated(obj.instanceId))
                        annotations.add(AnnotationBuilder.abilityInstanceDeleted(obj.instanceId))
                    }
                    "Resolve" -> {
                        annotations.add(AnnotationBuilder.resolutionStart(obj.instanceId, obj.grpId))
                    }
                }
                annotations.add(
                    AnnotationBuilder.zoneTransfer(obj.instanceId, prevZone, obj.zoneId, category),
                )
                if (category == "Resolve") {
                    annotations.add(AnnotationBuilder.resolutionComplete(obj.instanceId, obj.grpId))
                }
            }
            bridge.recordZone(obj.instanceId, obj.zoneId)
        }

        // Combat damage annotations: when at damage phase with active combat
        if (handler.phase == PhaseType.COMBAT_DAMAGE || handler.phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE) {
            val combat = handler.combat
            if (combat != null && combat.attackers.isNotEmpty()) {
                for (attacker in combat.attackers) {
                    val iid = bridge.getOrAllocInstanceId(attacker.id)
                    val dmg = attacker.getTotalDamageDoneBy()
                    if (dmg > 0) {
                        annotations.add(AnnotationBuilder.damageDealt(iid, dmg))
                    }
                }
                // Detect life changes vs. previous state
                val prev = bridge.getPreviousState()
                if (prev != null) {
                    for (playerInfo in prev.playersList) {
                        val seat = playerInfo.systemSeatNumber
                        val player = bridge.getPlayer(seat)
                        if (player != null) {
                            val prevLife = playerInfo.lifeTotal
                            val curLife = player.life
                            val delta = curLife - prevLife
                            if (delta != 0) {
                                annotations.add(AnnotationBuilder.modifiedLife(seat, delta))
                            }
                        }
                    }
                }
                annotations.add(AnnotationBuilder.phaseOrStepModified())
                annotations.add(AnnotationBuilder.syntheticEvent())
            }
        }

        val builder = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(gameStateId)
            .setGameInfo(gameInfo)
            .addAllTeams(listOf(team1.build(), team2.build()))
            .setTurnInfo(turnInfo)
            .addAllPlayers(listOf(player1, player2))
            .addAllZones(zones)
            .addAllGameObjects(gameObjects)
            .addAllAnnotations(annotations)
            .addAllTimers(buildTimers())
            .setUpdate(updateType)

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
    ): GameStateMessage {
        val prev = bridge.getPreviousState()
            ?: return buildFromGame(game, gameStateId, matchId, bridge, actions, updateType)

        // Build current full state (for comparison + to seed next diff).
        // Pass actions=null to avoid redundant action embedding (we embed below).
        val current = buildFromGame(game, gameStateId, matchId, bridge)

        // Compute changed zones (by objectInstanceIds)
        val prevZoneMap = prev.zonesList.associateBy { it.zoneId }
        val changedZones = current.zonesList.filter { zone ->
            val prevZone = prevZoneMap[zone.zoneId]
            prevZone == null || prevZone.objectInstanceIdsList != zone.objectInstanceIdsList
        }

        // Compute changed/new objects
        val prevObjMap = prev.gameObjectsList.associateBy { it.instanceId }
        val changedObjects = current.gameObjectsList.filter { obj ->
            val prevObj = prevObjMap[obj.instanceId]
            prevObj == null || prevObj != obj
        }

        val builder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .setTurnInfo(current.turnInfo)
            .addAllPlayers(current.playersList)
            .addAllZones(changedZones)
            .addAllGameObjects(changedObjects)
            .addAllAnnotations(current.annotationsList)
            .addAllTimers(buildTimers())
            .setUpdate(updateType)
            .setPrevGameStateId(prev.gameStateId)

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

        // Update snapshot for next diff
        bridge.snapshotState(game)

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
        matchId: String,
        bridge: GameBridge,
        phase: Phase,
        step: Step,
        isStageTransition: Boolean = false,
        actions: ActionsAvailableReq? = null,
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
            .setTurnInfo(turnInfo)
            .addPlayers(buildPlayerInfo(bridge.getPlayer(1), 1))
            .addPlayers(buildPlayerInfo(bridge.getPlayer(2), 2))
            .addAnnotations(AnnotationBuilder.phaseOrStepModified()) // phase change
            .addAnnotations(AnnotationBuilder.phaseOrStepModified()) // step change
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

        // Embed stripped-down actions when AAR follows
        if (actions != null) {
            builder.setPendingMessageCount(1)
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
     * Build playable actions for a seat from the current game state.
     * Includes: ActivateMana, Activate, Cast, Play, Pass.
     */
    fun buildActions(game: Game, seatId: Int, bridge: GameBridge): ActionsAvailableReq {
        val player = bridge.getPlayer(seatId) ?: return passOnlyActions()
        val builder = ActionsAvailableReq.newBuilder()

        // Battlefield permanents: ActivateMana + Activate
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            val grpId = CardDb.lookupByName(card.name) ?: GameBridge.FALLBACK_GRPID

            // ActivateMana — untapped permanents with mana abilities
            // Real server: grpId, instanceId, facetId, abilityGrpId, manaPaymentOptions,
            //   isBatchable, uniqueAbilityId, manaSelections
            if (!card.isTapped) {
                val manaAbilities = card.manaAbilities
                if (manaAbilities.isNotEmpty()) {
                    val cardData = CardDb.lookup(grpId)
                    val abilityEntry = cardData?.abilityIds?.firstOrNull()
                    val abilityGrpId = abilityEntry?.first ?: 0
                    // uniqueAbilityId: references UniqueAbilityInfo.id on the card object
                    // Not critical for basic flow; omitted for now
                    val sa = manaAbilities.first()
                    val produced = sa.manaPart?.origProduced ?: ""
                    val manaColor = producedToManaColor(produced) ?: ManaColor.Generic

                    val actionBuilder = Action.newBuilder()
                        .setActionType(ActionType.ActivateMana)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId)
                        .setFacetId(instanceId)
                        .setIsBatchable(true)
                    if (abilityGrpId != 0) actionBuilder.setAbilityGrpId(abilityGrpId)

                    // manaPaymentOptions: what mana this source produces
                    actionBuilder.addManaPaymentOptions(
                        ManaPaymentOption.newBuilder().addMana(
                            ManaInfo.newBuilder()
                                .setManaId(10)
                                .setColor(manaColor)
                                .setSrcInstanceId(instanceId)
                                .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                                .setAbilityGrpId(abilityGrpId)
                                .setCount(1),
                        ),
                    )

                    // manaSelections: available mana selection options
                    actionBuilder.addManaSelections(
                        ManaSelection.newBuilder()
                            .setInstanceId(instanceId)
                            .setAbilityGrpId(abilityGrpId)
                            .addOptions(
                                ManaSelectionOption.newBuilder().addMana(
                                    ManaColorCount.newBuilder().setColor(manaColor).setCount(1),
                                ),
                            ),
                    )

                    builder.addActions(actionBuilder)
                }
            }

            // Activate — non-mana activated abilities (same pattern as forge-web PlayableActionQuery)
            for (ability in card.spellAbilities) {
                ability.setActivatingPlayer(player)
                if (!ability.isActivatedAbility) continue
                if (ability.isManaAbility()) continue
                if (!ability.canPlay()) continue
                val cardData = CardDb.lookup(grpId)
                val actionBuilder = Action.newBuilder()
                    .setActionType(ActionType.Activate_add3)
                    .setInstanceId(instanceId)
                    .setGrpId(grpId)
                    .setFacetId(instanceId)
                if (cardData != null) {
                    val abilityEntry = cardData.abilityIds.firstOrNull()
                    if (abilityEntry != null) actionBuilder.setAbilityGrpId(abilityEntry.first)
                }
                builder.addActions(actionBuilder)
            }
        }

        // Lands: playable → actions, not playable → inactiveActions
        val handCards = player.getZone(ForgeZoneType.Hand).cards
        val lands = CardLists.filter(handCards, CardPredicates.LANDS)
        for (card in lands) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            val grpId = CardDb.lookupByName(card.name) ?: GameBridge.FALLBACK_GRPID
            val landAbility = LandAbility(card, card.currentState)
            landAbility.activatingPlayer = player
            if (player.canPlayLand(card, false, landAbility)) {
                builder.addActions(
                    Action.newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId)
                        .setFacetId(instanceId)
                        .setShouldStop(true),
                )
            } else {
                // Greyed-out: land can't be played (already played one this turn)
                builder.addInactiveActions(
                    Action.newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setGrpId(grpId)
                        .setInstanceId(instanceId)
                        .setFacetId(instanceId),
                )
            }
        }

        // Castable non-land spells
        val nonLands = CardLists.filter(handCards, CardPredicates.NON_LANDS)
        for (card in nonLands) {
            val sa = chooseCastAbility(card, player) ?: continue
            val canPay = try {
                ComputerUtilMana.canPayManaCost(sa, player, 0, false)
            } catch (_: Exception) {
                false
            }
            if (!canPay) continue
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            val grpId = CardDb.lookupByName(card.name) ?: GameBridge.FALLBACK_GRPID
            // Real Arena Cast: grpId + instanceId + facetId + manaCost + shouldStop
            // Note: abilityGrpId NOT set on Cast in ActionsAvailableReq (only in GSM embedded actions)
            val actionBuilder = Action.newBuilder()
                .setActionType(ActionType.Cast)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setFacetId(instanceId)
                .setShouldStop(true)
            val cardData = CardDb.lookup(grpId)
            if (cardData != null) {
                for ((color, count) in cardData.manaCost) {
                    actionBuilder.addManaCost(
                        ManaRequirement.newBuilder().addColor(color).setCount(count),
                    )
                }
                // autoTapSolution: recommend which lands to tap for one-click casting
                val autoTap = buildAutoTapSolution(cardData.manaCost, player, bridge)
                if (autoTap != null) actionBuilder.setAutoTapSolution(autoTap)
            }
            builder.addActions(actionBuilder)
        }

        // Pass always available + FloatMana (real server always includes both)
        builder.addActions(Action.newBuilder().setActionType(ActionType.Pass))
        builder.addActions(Action.newBuilder().setActionType(ActionType.FloatMana))

        val manaCount = builder.actionsList.count { it.actionType == ActionType.ActivateMana }
        val activateCount = builder.actionsList.count { it.actionType == ActionType.Activate_add3 }
        val landCount = builder.actionsList.count { it.actionType == ActionType.Play_add3 }
        val castCount = builder.actionsList.count { it.actionType == ActionType.Cast }
        log.info("buildActions: seat={} mana={} activate={} lands={} casts={} total={}", seatId, manaCount, activateCount, landCount, castCount, builder.actionsCount)

        return builder.build()
    }

    // --- Targeting requests ---

    /**
     * Build a [SelectTargetsReq] from an [InteractivePromptBridge.PendingPrompt].
     * Maps prompt candidate refs (entity IDs) to Arena instanceIds via the bridge.
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
     * - SendAndRecord: viewing seat IS the acting player (your actions)
     * - SendHiFi: viewing seat is NOT the acting player (opponent/spectator)
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

    // --- Phase/Step mapping ---

    fun mapPhase(phase: PhaseType?): Phase = when (phase) {
        null -> Phase.None_a549
        PhaseType.UNTAP, PhaseType.UPKEEP, PhaseType.DRAW -> Phase.Beginning_a549
        PhaseType.MAIN1 -> Phase.Main1_a549
        PhaseType.COMBAT_BEGIN, PhaseType.COMBAT_DECLARE_ATTACKERS,
        PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.COMBAT_FIRST_STRIKE_DAMAGE,
        PhaseType.COMBAT_DAMAGE, PhaseType.COMBAT_END,
        -> Phase.Combat_a549
        PhaseType.MAIN2 -> Phase.Main2_a549
        PhaseType.END_OF_TURN, PhaseType.CLEANUP -> Phase.Ending_a549
    }

    fun mapStep(phase: PhaseType?): Step = when (phase) {
        null -> Step.None_a2cb
        PhaseType.UNTAP -> Step.Untap
        PhaseType.UPKEEP -> Step.Upkeep_a2cb
        PhaseType.DRAW -> Step.Draw_a2cb
        PhaseType.MAIN1, PhaseType.MAIN2 -> Step.None_a2cb
        PhaseType.COMBAT_BEGIN -> Step.BeginCombat_a2cb
        PhaseType.COMBAT_DECLARE_ATTACKERS -> Step.DeclareAttack_a2cb
        PhaseType.COMBAT_DECLARE_BLOCKERS -> Step.DeclareBlock_a2cb
        PhaseType.COMBAT_FIRST_STRIKE_DAMAGE -> Step.FirstStrikeDamage_a2cb
        PhaseType.COMBAT_DAMAGE -> Step.CombatDamage_a2cb
        PhaseType.COMBAT_END -> Step.EndCombat_a2cb
        PhaseType.END_OF_TURN -> Step.End_a2cb
        PhaseType.CLEANUP -> Step.Cleanup_a2cb
    }

    // --- Helpers for buildFromGame ---

    internal fun buildPlayerInfo(player: Player?, seatId: Int): PlayerInfo {
        val builder = PlayerInfo.newBuilder()
            .setSystemSeatNumber(seatId)
            .setTeamId(seatId)
            .setStatus(PlayerStatus.InGame_a1c6)
            .setControllerType(ControllerType.Player_abfa)
            .addTimerIds(seatId) // real Arena: timerIds=[seatId]
        if (player != null) {
            builder.setLifeTotal(player.life)
                .setStartingLifeTotal(20)
                .setMaxHandSize(player.maxHandSize)

            // Mana pool — disabled for now: Arena client auto-subtracts floating mana
            // from displayed card costs, causing confusing 0-cost display.
            // TODO: re-enable once we understand the client's cost rendering rules
            // var manaId = 1
            // for (mana in player.manaPool) {
            //     val color = mapManaColor(mana.color)
            //     builder.addManaPool(ManaInfo.newBuilder().setManaId(manaId++).setColor(color))
            // }
        }
        return builder.build()
    }

    /** Inactivity timers — real Arena sends 2 per game state (one per seat). */
    internal fun buildTimers(): List<TimerInfo> = listOf(
        TimerInfo.newBuilder()
            .setTimerId(1)
            .setType(TimerType.Inactivity_a5e2)
            .setDurationSec(1020)
            .setBehavior(TimerBehavior.Timeout_a3cd)
            .setWarningThresholdSec(990)
            .build(),
        TimerInfo.newBuilder()
            .setTimerId(2)
            .setType(TimerType.Inactivity_a5e2)
            .setDurationSec(1020)
            .setRunning(true)
            .setBehavior(TimerBehavior.Timeout_a3cd)
            .setWarningThresholdSec(990)
            .build(),
    )

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
    ) {
        if (player == null) return

        // Hand — visible cards with game objects
        val hand = player.getZone(ForgeZoneType.Hand)
        val handBuilder = ZoneInfo.newBuilder()
            .setZoneId(handZoneId).setType(ZoneType.Hand)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Private)
        for (card in hand.cards) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            handBuilder.addObjectInstanceIds(instanceId)
            gameObjects.add(buildCardObject(card, instanceId, handZoneId, seatId))
        }
        zones.add(handBuilder.build())

        // Library — instance IDs only (hidden)
        val lib = player.getZone(ForgeZoneType.Library)
        val libBuilder = ZoneInfo.newBuilder()
            .setZoneId(libZoneId).setType(ZoneType.Library)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Hidden)
        for (card in lib.cards) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(card.id))
        }
        zones.add(libBuilder.build())

        // Graveyard — visible
        val gy = player.getZone(ForgeZoneType.Graveyard)
        val gyBuilder = ZoneInfo.newBuilder()
            .setZoneId(gyZoneId).setType(ZoneType.Graveyard)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Public)
        for (card in gy.cards) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            gyBuilder.addObjectInstanceIds(instanceId)
            gameObjects.add(buildCardObject(card, instanceId, gyZoneId, seatId))
        }
        zones.add(gyBuilder.build())
    }

    /** Hand + library only (no graveyard) — used for deal-hand at mulligan time. */
    private fun addHandAndLibrary(
        game: Game,
        player: Player?,
        seatId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        handZoneId: Int,
        libZoneId: Int,
    ) {
        if (player == null) return

        val hand = player.getZone(ForgeZoneType.Hand)
        val handBuilder = ZoneInfo.newBuilder()
            .setZoneId(handZoneId).setType(ZoneType.Hand)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Private)
        for (card in hand.cards) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            handBuilder.addObjectInstanceIds(instanceId)
            gameObjects.add(buildCardObject(card, instanceId, handZoneId, seatId))
        }
        handBuilder.addViewers(seatId)
        zones.add(handBuilder.build())

        val lib = player.getZone(ForgeZoneType.Library)
        val libBuilder = ZoneInfo.newBuilder()
            .setZoneId(libZoneId).setType(ZoneType.Library)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Hidden)
        for (card in lib.cards) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(card.id))
        }
        zones.add(libBuilder.build())
    }

    private fun addSharedZoneCards(
        game: Game,
        forgeZone: ForgeZoneType,
        arenaZoneId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        human: Player?,
        ai: Player?,
    ) {
        // Find the zone builder we already added
        val zoneBuilder = zones.find { it.zoneId == arenaZoneId }?.toBuilder() ?: return
        zones.removeIf { it.zoneId == arenaZoneId }

        val allCards = game.getCardsIn(forgeZone)
        for (card in allCards) {
            val ownerSeatId = if (card.owner == human) 1 else 2
            val controllerSeatId = if (card.controller == human) 1 else 2
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            zoneBuilder.addObjectInstanceIds(instanceId)

            val grpId = CardDb.lookupByName(card.name) ?: GameBridge.FALLBACK_GRPID
            gameObjects.add(
                CardDb.buildObjectInfo(grpId)
                    .setInstanceId(instanceId)
                    .setType(GameObjectType.Card)
                    .setZoneId(arenaZoneId)
                    .setVisibility(Visibility.Public)
                    .setOwnerSeatId(ownerSeatId)
                    .setControllerSeatId(controllerSeatId)
                    .applyCardFields(card, bridge, game)
                    .build(),
            )
        }
        zones.add(zoneBuilder.build())
    }

    /**
     * Add [GameObjectType.Ability] entries for stack items not already represented
     * as cards in the stack zone. Uses the stack instance's unique ID + offset for
     * stable instance IDs.
     */
    private fun addStackAbilities(
        game: Game,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        human: Player?,
    ) {
        val stack = game.getStack()
        if (stack.isEmpty) return

        val zoneBuilder = zones.find { it.zoneId == ZONE_STACK }?.toBuilder() ?: return
        zones.removeIf { it.zoneId == ZONE_STACK }

        // Track which source cards are already in the zone (from addSharedZoneCards)
        val existingIds = zoneBuilder.objectInstanceIdsList.toSet()

        for (entry in stack) {
            val sourceCard = entry.sourceCard ?: continue
            val cardInstanceId = bridge.getOrAllocInstanceId(sourceCard.id)
            // Skip if the source card is already represented in the stack zone
            if (cardInstanceId in existingIds) continue

            // Use a separate instance ID for the ability on the stack
            val abilityInstanceId = bridge.getOrAllocInstanceId(sourceCard.id + STACK_ABILITY_ID_OFFSET)
            val ownerSeatId = if (sourceCard.owner == human) 1 else 2
            val grpId = CardDb.lookupByName(sourceCard.name) ?: GameBridge.FALLBACK_GRPID

            zoneBuilder.addObjectInstanceIds(abilityInstanceId)
            gameObjects.add(
                CardDb.buildObjectInfo(grpId)
                    .setInstanceId(abilityInstanceId)
                    .setType(GameObjectType.Ability)
                    .setZoneId(ZONE_STACK)
                    .setVisibility(Visibility.Public)
                    .setOwnerSeatId(ownerSeatId)
                    .setControllerSeatId(ownerSeatId)
                    .setObjectSourceGrpId(grpId)
                    .build(),
            )
        }
        zones.add(zoneBuilder.build())
    }

    private fun buildCardObject(card: Card, instanceId: Int, zoneId: Int, ownerSeatId: Int): GameObjectInfo {
        val grpId = CardDb.lookupByName(card.name) ?: GameBridge.FALLBACK_GRPID
        return CardDb.buildObjectInfo(grpId)
            .setInstanceId(instanceId)
            .setType(GameObjectType.Card)
            .setZoneId(zoneId)
            .setVisibility(Visibility.Private)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(ownerSeatId)
            .applyCardFields(card)
            .build()
    }

    /**
     * Apply dynamic Forge game state onto a [GameObjectInfo.Builder] already enriched
     * with static card data from [CardDb.buildObjectInfo].
     *
     * Static fields (types, colors, abilities, base P/T) come from the Arena DB.
     * This method adds: live P/T, tapped, sickness, damage, loyalty, combat, attachment.
     */
    private fun GameObjectInfo.Builder.applyCardFields(card: Card, bridge: GameBridge? = null, game: Game? = null): GameObjectInfo.Builder {
        val type = card.type

        // Live P/T from Forge (may differ from base due to buffs/counters)
        if (type.isCreature) {
            setPower(Int32Value.newBuilder().setValue(card.netPower))
            setToughness(Int32Value.newBuilder().setValue(card.netToughness))
        }

        // Permanent state — battlefield only
        if (card.isInZone(ForgeZoneType.Battlefield)) {
            setIsTapped(card.isTapped)
            if (type.isCreature) {
                setHasSummoningSickness(card.hasSickness())
                if (card.damage > 0) setDamage(card.damage)
            }
            if (type.isPlaneswalker) {
                setLoyalty(UInt32Value.newBuilder().setValue(card.currentLoyalty))
            }
        }

        // Attachment (Auras, Equipment)
        val attachedTo = card.attachedTo
        if (attachedTo != null && bridge != null) {
            setParentId(bridge.getOrAllocInstanceId(attachedTo.id))
        }

        // Combat state
        val combat = game?.phaseHandler?.combat
        if (combat != null && type.isCreature) {
            if (combat.isAttacking(card)) setAttackState(AttackState.Attacking)
            if (combat.isBlocking(card)) setBlockState(BlockState.Blocking)
        }

        return this
    }

    private fun passOnlyActions(): ActionsAvailableReq =
        ActionsAvailableReq.newBuilder()
            .addActions(Action.newBuilder().setActionType(ActionType.Pass))
            .build()

    /**
     * Greedy auto-tap solver: maps mana cost requirements to untapped mana sources.
     * Returns null if no complete solution found (spell still castable via manual tap).
     */
    private fun buildAutoTapSolution(
        manaCost: List<Pair<ManaColor, Int>>,
        player: Player,
        bridge: GameBridge,
    ): AutoTapSolution? {
        if (manaCost.isEmpty()) return null

        data class ManaSource(val card: Card, val instanceId: Int, val color: ManaColor, val abilityGrpId: Int)

        // Collect untapped mana sources with their produced color
        val sources = mutableListOf<ManaSource>()
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (card.isTapped) continue
            for (sa in card.manaAbilities) {
                sa.setActivatingPlayer(player)
                if (!sa.canPlay()) continue
                val produced = sa.manaPart?.origProduced ?: continue
                val manaColor = producedToManaColor(produced) ?: continue
                val instanceId = bridge.getOrAllocInstanceId(card.id)
                val grpId = CardDb.lookupByName(card.name) ?: GameBridge.FALLBACK_GRPID
                val abilityGrpId = CardDb.lookup(grpId)?.abilityIds?.firstOrNull()?.first ?: 0
                sources.add(ManaSource(card, instanceId, manaColor, abilityGrpId))
            }
        }

        // Greedy match: colored requirements first, then generic
        val used = mutableSetOf<Int>() // indices into sources
        val matched = mutableListOf<Pair<ManaSource, ManaColor>>() // (source, paying color)
        val coloredReqs = manaCost.filter { it.first != ManaColor.Generic }
        val genericReqs = manaCost.filter { it.first == ManaColor.Generic }

        // Match colored requirements
        for ((reqColor, reqCount) in coloredReqs) {
            var remaining = reqCount
            for ((idx, src) in sources.withIndex()) {
                if (remaining <= 0) break
                if (idx in used) continue
                if (src.color == reqColor) {
                    used.add(idx)
                    matched.add(src to reqColor)
                    remaining--
                }
            }
            if (remaining > 0) return null // can't fulfill colored requirement
        }

        // Match generic requirements (any color)
        for ((_, reqCount) in genericReqs) {
            var remaining = reqCount
            for ((idx, src) in sources.withIndex()) {
                if (remaining <= 0) break
                if (idx in used) continue
                used.add(idx)
                matched.add(src to src.color)
                remaining--
            }
            if (remaining > 0) return null
        }

        // Build AutoTapSolution matching real server format:
        // Each AutoTapAction has manaPaymentOption with full ManaInfo
        val builder = AutoTapSolution.newBuilder()
        var manaIdCounter = 10 // real server uses ids starting around 10
        for ((src, payingColor) in matched) {
            val manaId = manaIdCounter++
            builder.addAutoTapActions(
                AutoTapAction.newBuilder()
                    .setInstanceId(src.instanceId)
                    .setAbilityGrpId(src.abilityGrpId)
                    .setManaPaymentOption(
                        ManaPaymentOption.newBuilder().addMana(
                            ManaInfo.newBuilder()
                                .setManaId(manaId)
                                .setColor(payingColor)
                                .setSrcInstanceId(src.instanceId)
                                .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                                .setAbilityGrpId(src.abilityGrpId)
                                .setCount(1),
                        ),
                    ),
            )
        }
        return builder.build()
    }

    /** Map Forge's produced-mana string (e.g. "G", "W", "Any") to proto ManaColor. */
    private fun producedToManaColor(produced: String): ManaColor? = when (produced.uppercase().trim()) {
        "W" -> ManaColor.White_afc9
        "U" -> ManaColor.Blue_afc9
        "B" -> ManaColor.Black_afc9
        "R" -> ManaColor.Red_afc9
        "G" -> ManaColor.Green_afc9
        "C" -> ManaColor.Colorless_afc9
        "ANY" -> ManaColor.Generic
        else -> null
    }

    /** Infer a human-readable category for a zone transfer annotation. */
    private fun inferCategory(obj: GameObjectInfo, srcZone: Int, destZone: Int): String =
        when {
            srcZone == ZONE_P1_HAND || srcZone == ZONE_P2_HAND -> when (destZone) {
                ZONE_STACK -> "CastSpell"
                ZONE_BATTLEFIELD -> "PlayLand"
                else -> "ZoneTransfer"
            }
            srcZone == ZONE_STACK && destZone == ZONE_BATTLEFIELD -> "Resolve"
            srcZone == ZONE_BATTLEFIELD -> when (destZone) {
                ZONE_P1_GRAVEYARD, ZONE_P2_GRAVEYARD -> "Destroy"
                ZONE_EXILE -> "Exile"
                else -> "ZoneTransfer"
            }
            else -> "ZoneTransfer"
        }

    /**
     * Strip an Action down to the minimal format used inside GSM embedded actions.
     * Real server: Cast=instanceId+manaCost, Play=instanceId, ActivateMana=instanceId+abilityGrpId.
     * No grpId, facetId, shouldStop, autoTapSolution.
     */
    internal fun stripActionForGsm(action: Action): Action {
        val b = Action.newBuilder().setActionType(action.actionType)
        when (action.actionType) {
            ActionType.Cast -> {
                b.setInstanceId(action.instanceId)
                b.addAllManaCost(action.manaCostList)
            }
            ActionType.Play_add3 -> b.setInstanceId(action.instanceId)
            ActionType.ActivateMana -> {
                b.setInstanceId(action.instanceId)
                if (action.abilityGrpId != 0) b.setAbilityGrpId(action.abilityGrpId)
            }
            ActionType.Pass, ActionType.FloatMana -> {} // empty
            else -> b.setInstanceId(action.instanceId)
        }
        return b.build()
    }

    // --- helpers ---

    private fun makeZone(zoneId: Int, type: ZoneType, ownerSeatId: Int, visibility: Visibility): ZoneInfo =
        ZoneInfo.newBuilder()
            .setZoneId(zoneId)
            .setType(type)
            .setOwnerSeatId(ownerSeatId)
            .setVisibility(visibility)
            .build()
}
