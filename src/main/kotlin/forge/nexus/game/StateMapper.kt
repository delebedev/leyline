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

    /** Offset added to source card IDs for stack ability instance IDs. */
    private const val STACK_ABILITY_ID_OFFSET = 100_000

    /** Starting mana ID for auto-tap solutions; real server uses ids starting around 10. */
    private const val INITIAL_MANA_ID = 10

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

    // ---- Three-stage diff pipeline types and methods ----
    // Stage 1: detectAndApplyZoneTransfers  → List<AppliedTransfer>
    // Stage 2: annotationsForTransfer       → List<AnnotationInfo>  (pure, testable)
    // Stage 3: combatAnnotations            → List<AnnotationInfo>  (pure, testable)

    /**
     * Record of a zone transfer after ID reallocation.
     * Produced by [detectAndApplyZoneTransfers], consumed by [annotationsForTransfer].
     * All fields are plain ints/strings — no Forge engine references, independently testable.
     */
    data class AppliedTransfer(
        val origId: Int,
        val newId: Int,
        val category: TransferCategory,
        val srcZoneId: Int,
        val destZoneId: Int,
        val grpId: Int,
        val ownerSeatId: Int,
    )

    /**
     * Stage 1: Detect zone transfers, realloc instanceIds, patch gameObjects/zones,
     * retire old IDs to Limbo. Returns the list of applied transfers for annotation building.
     *
     * Mutates [gameObjects] and [zones] (patches new instanceIds, appends Limbo entries).
     * Updates [bridge] zone tracking and Limbo state.
     */
    internal fun detectAndApplyZoneTransfers(
        gameObjects: MutableList<GameObjectInfo>,
        zones: MutableList<ZoneInfo>,
        bridge: GameBridge,
        events: List<NexusGameEvent>,
    ): List<AppliedTransfer> {
        val transfers = mutableListOf<AppliedTransfer>()

        for (i in gameObjects.indices) {
            val obj = gameObjects[i]
            val prevZone = bridge.getPreviousZone(obj.instanceId)
            if (prevZone != null && prevZone != obj.zoneId) {
                val forgeCardId = bridge.getForgeCardId(obj.instanceId)
                val category = if (forgeCardId != null && events.isNotEmpty()) {
                    AnnotationBuilder.categoryFromEvents(forgeCardId, events)
                        ?: inferCategory(obj, prevZone, obj.zoneId)
                } else {
                    inferCategory(obj, prevZone, obj.zoneId)
                }
                // Allocate new instanceId for zone transfer (real server does this).
                // Exception: Resolve (Stack→Battlefield) keeps the same instanceId.
                val realloc = if (category != TransferCategory.Resolve && forgeCardId != null) {
                    bridge.reallocInstanceId(forgeCardId)
                } else {
                    InstanceIdRegistry.IdReallocation(obj.instanceId, obj.instanceId)
                }
                val origId = realloc.old
                val newId = realloc.new
                log.debug("zone transfer: iid {} → {} category={}", origId, newId, category)
                // Patch gameObject and zone with new instanceId
                if (newId != origId) {
                    gameObjects[i] = obj.toBuilder().setInstanceId(newId).build()
                    patchZoneInstanceId(zones, obj.zoneId, origId, newId)
                    bridge.retireToLimbo(origId)
                    appendToZone(zones, ZONE_LIMBO, origId)
                    gameObjects.add(
                        obj.toBuilder()
                            .setInstanceId(origId)
                            .setZoneId(ZONE_LIMBO)
                            .setVisibility(Visibility.Private)
                            .clearViewers()
                            .addViewers(obj.ownerSeatId)
                            .build(),
                    )
                }
                transfers.add(
                    AppliedTransfer(origId, newId, category, prevZone, obj.zoneId, obj.grpId, obj.ownerSeatId),
                )
                bridge.recordZone(newId, obj.zoneId)
            } else {
                bridge.recordZone(obj.instanceId, obj.zoneId)
            }
        }
        return transfers
    }

    /**
     * Stage 2: Generate annotations for a single zone transfer.
     * **Pure function** — no bridge access, no side effects. Independently testable.
     *
     * Returns (transient annotations, persistent annotations).
     */
    internal fun annotationsForTransfer(
        transfer: AppliedTransfer,
        actingSeat: Int,
    ): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        val (origId, newId, category, srcZone, destZone, grpId, _) = transfer
        val annotations = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()

        when (category) {
            TransferCategory.PlayLand -> {
                annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
                annotations.add(AnnotationBuilder.userActionTaken(newId, actingSeat, actionType = 3))
            }
            TransferCategory.CastSpell -> {
                annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
                annotations.add(AnnotationBuilder.abilityInstanceCreated(newId))
                annotations.add(AnnotationBuilder.tappedUntappedPermanent(newId, newId))
                annotations.add(AnnotationBuilder.manaPaid(newId))
                annotations.add(AnnotationBuilder.abilityInstanceDeleted(newId))
                annotations.add(AnnotationBuilder.userActionTaken(newId, actingSeat, actionType = 1))
            }
            TransferCategory.Resolve -> {
                annotations.add(AnnotationBuilder.resolutionStart(newId, grpId))
                annotations.add(AnnotationBuilder.resolutionComplete(newId, grpId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, actingSeat))
            }
            TransferCategory.Destroy, TransferCategory.Exile, TransferCategory.ZoneTransfer -> {}
        }

        // Persistent: EnteredZoneThisTurn for cards landing on battlefield
        if (destZone == ZONE_BATTLEFIELD) {
            persistent.add(AnnotationBuilder.enteredZoneThisTurn(ZONE_BATTLEFIELD, newId))
        }

        return annotations to persistent
    }

    /**
     * Stage 3: Generate combat damage annotations.
     * **Pure function** given game state and previous player info.
     * No bridge mutation — only reads instanceId mappings.
     */
    internal fun combatAnnotations(
        game: Game,
        bridge: GameBridge,
    ): List<AnnotationInfo> {
        val handler = game.phaseHandler
        if (handler.phase != PhaseType.COMBAT_DAMAGE && handler.phase != PhaseType.COMBAT_FIRST_STRIKE_DAMAGE) {
            return emptyList()
        }
        val combat = handler.combat ?: return emptyList()
        if (combat.attackers.isEmpty()) return emptyList()

        val annotations = mutableListOf<AnnotationInfo>()

        for (attacker in combat.attackers) {
            val iid = bridge.getOrAllocInstanceId(attacker.id)
            val dmg = attacker.getTotalDamageDoneBy()
            if (dmg > 0) {
                annotations.add(AnnotationBuilder.damageDealt(iid, dmg))
            }
        }

        val prev = bridge.getPreviousState()
        if (prev != null) {
            for (playerInfo in prev.playersList) {
                val seat = playerInfo.systemSeatNumber
                val player = bridge.getPlayer(seat)
                if (player != null) {
                    val delta = player.life - playerInfo.lifeTotal
                    if (delta != 0) {
                        annotations.add(AnnotationBuilder.modifiedLife(seat, delta))
                    }
                }
            }
        }

        val human = bridge.getPlayer(1)
        val activeSeat = if (handler.playerTurn == human) 1 else 2
        val protoPhase = mapPhase(handler.phase).number
        val protoStep = mapStep(handler.phase).number
        annotations.add(AnnotationBuilder.phaseOrStepModified(activeSeat, protoPhase, protoStep))
        annotations.add(AnnotationBuilder.syntheticEvent())
        return annotations
    }

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

        // --- Three-stage annotation pipeline ---
        // Stage 1: Detect zone transfers, realloc IDs, patch objects/zones
        val events = bridge.eventCollector?.drainEvents() ?: emptyList()
        val transfers = detectAndApplyZoneTransfers(gameObjects, zones, bridge, events)

        // Stage 2: Generate annotations from transfers (pure, no side effects)
        val actingSeat = if (handler.priorityPlayer == human) 1 else 2
        val annotations = mutableListOf<AnnotationInfo>()
        val persistentAnnotations = mutableListOf<AnnotationInfo>()
        for (transfer in transfers) {
            val (transient, persistent) = annotationsForTransfer(transfer, actingSeat)
            annotations.addAll(transient)
            persistentAnnotations.addAll(
                persistent.map {
                    it.toBuilder().setId(bridge.nextPersistentAnnotationId()).build()
                },
            )
        }
        val numberedAnnotations = annotations.map {
            it.toBuilder().setId(bridge.nextAnnotationId()).build()
        }

        // Stage 3: Combat damage annotations
        annotations.addAll(combatAnnotations(game, bridge))

        // prevGameStateId: reference prior state if one exists
        val prevState = bridge.getPreviousState()

        val builder = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(gameStateId)
            .setGameInfo(gameInfo)
            .addAllTeams(listOf(team1.build(), team2.build()))
            .setTurnInfo(turnInfo)
            .addAllPlayers(listOf(player1, player2))
            .addAllZones(zones.sortedBy { it.zoneId })
            .addAllGameObjects(gameObjects)
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

        // Update snapshot for next diff (pass gsId so prevGameStateId is correct)
        bridge.snapshotState(game, gameStateId)

        return builder.build()
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
            .setTurnInfo(turnInfo)
            .addPlayers(buildPlayerInfo(bridge.getPlayer(1), 1))
            .addPlayers(buildPlayerInfo(bridge.getPlayer(2), 2))
            .addAnnotations(AnnotationBuilder.phaseOrStepModified(activeSeat, phase.number, step.number)) // phase change
            .addAnnotations(AnnotationBuilder.phaseOrStepModified(activeSeat, phase.number, step.number)) // step change
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
            // Real client Cast: grpId + instanceId + facetId + manaCost + shouldStop
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

    /**
     * Naive action list: Cast for all non-lands, Play for all lands in hand,
     * ActivateMana for untapped permanents — no canPlay/canPay checks.
     * Real server embeds human's potential actions during AI turn regardless of phase.
     */
    fun buildNaiveActions(seatId: Int, bridge: GameBridge): ActionsAvailableReq {
        val player = bridge.getPlayer(seatId) ?: return passOnlyActions()
        val builder = ActionsAvailableReq.newBuilder()

        // ActivateMana for untapped permanents with mana abilities
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (card.isTapped) continue
            if (card.manaAbilities.isEmpty()) continue
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            val grpId = CardDb.lookupByName(card.name) ?: GameBridge.FALLBACK_GRPID
            val cardData = CardDb.lookup(grpId)
            val abilityGrpId = cardData?.abilityIds?.firstOrNull()?.first ?: 0
            val sa = card.manaAbilities.first()
            val produced = sa.manaPart?.origProduced ?: ""
            val manaColor = producedToManaColor(produced) ?: ManaColor.Generic
            val actionBuilder = Action.newBuilder()
                .setActionType(ActionType.ActivateMana)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setFacetId(instanceId)
                .setIsBatchable(true)
            if (abilityGrpId != 0) actionBuilder.setAbilityGrpId(abilityGrpId)
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

        val handCards = player.getZone(ForgeZoneType.Hand).cards

        // Play for all lands
        for (card in CardLists.filter(handCards, CardPredicates.LANDS)) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            val grpId = CardDb.lookupByName(card.name) ?: GameBridge.FALLBACK_GRPID
            builder.addActions(
                Action.newBuilder()
                    .setActionType(ActionType.Play_add3)
                    .setInstanceId(instanceId)
                    .setGrpId(grpId)
                    .setFacetId(instanceId)
                    .setShouldStop(true),
            )
        }

        // Cast for all non-land spells (no mana check)
        for (card in CardLists.filter(handCards, CardPredicates.NON_LANDS)) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            val grpId = CardDb.lookupByName(card.name) ?: GameBridge.FALLBACK_GRPID
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
            }
            builder.addActions(actionBuilder)
        }

        builder.addActions(Action.newBuilder().setActionType(ActionType.Pass))
        builder.addActions(Action.newBuilder().setActionType(ActionType.FloatMana))

        log.info(
            "buildNaiveActions: seat={} mana={} lands={} casts={} total={}",
            seatId,
            builder.actionsList.count { it.actionType == ActionType.ActivateMana },
            builder.actionsList.count { it.actionType == ActionType.Play_add3 },
            builder.actionsList.count { it.actionType == ActionType.Cast },
            builder.actionsCount,
        )

        return builder.build()
    }

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
            .setControllerSeatId(seatId)
            .setControllerType(ControllerType.Player_abfa)
            .addTimerIds(seatId) // real client: timerIds=[seatId]
        if (player != null) {
            builder.setLifeTotal(player.life)
                .setStartingLifeTotal(20)
                .setMaxHandSize(player.maxHandSize)

            // Mana pool — disabled for now: client auto-subtracts floating mana
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

    /** Inactivity timers — real client sends 2 per game state (one per seat). */
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
        viewingSeatId: Int = 0,
    ) {
        if (player == null) return

        // Hand — objectInstanceIds always (for card count), GameObjectInfo only for viewer.
        // Real server omits GameObjectInfo for opponent's hand → renders face-down.
        val canSeeHand = viewingSeatId == 0 || viewingSeatId == seatId
        val hand = player.getZone(ForgeZoneType.Hand)
        val handBuilder = ZoneInfo.newBuilder()
            .setZoneId(handZoneId).setType(ZoneType.Hand)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Private)
            .addViewers(seatId)
        for (card in hand.cards) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            handBuilder.addObjectInstanceIds(instanceId)
            if (canSeeHand) {
                gameObjects.add(buildCardObject(card, instanceId, handZoneId, seatId))
            }
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
        viewingSeatId: Int = 0,
    ) {
        if (player == null) return

        val hand = player.getZone(ForgeZoneType.Hand)
        val handBuilder = ZoneInfo.newBuilder()
            .setZoneId(handZoneId).setType(ZoneType.Hand)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Private)
        // Real server only includes GameObjectInfo for the viewing seat's hand.
        // Opponent hand cards appear in objectInstanceIds (for count) but have
        // no GameObjectInfo — client renders them face-down.
        val canSeeHand = viewingSeatId == 0 || viewingSeatId == seatId
        for (card in hand.cards) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            handBuilder.addObjectInstanceIds(instanceId)
            if (canSeeHand) {
                gameObjects.add(buildCardObject(card, instanceId, handZoneId, seatId))
            }
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
     * Static fields (types, colors, abilities, base P/T) come from the client DB.
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
        var manaIdCounter = INITIAL_MANA_ID
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

    /** Infer category for a zone transfer annotation from zone IDs. */
    internal fun inferCategory(obj: GameObjectInfo, srcZone: Int, destZone: Int): TransferCategory =
        when {
            srcZone == ZONE_P1_HAND || srcZone == ZONE_P2_HAND -> when (destZone) {
                ZONE_STACK -> TransferCategory.CastSpell
                ZONE_BATTLEFIELD -> TransferCategory.PlayLand
                else -> TransferCategory.ZoneTransfer
            }
            srcZone == ZONE_STACK && destZone == ZONE_BATTLEFIELD -> TransferCategory.Resolve
            srcZone == ZONE_BATTLEFIELD -> when (destZone) {
                ZONE_P1_GRAVEYARD, ZONE_P2_GRAVEYARD -> TransferCategory.Destroy
                ZONE_EXILE -> TransferCategory.Exile
                else -> TransferCategory.ZoneTransfer
            }
            else -> TransferCategory.ZoneTransfer
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

    /** Replace oldId with newId in a zone's objectInstanceIds list (after instanceId realloc). */
    private fun patchZoneInstanceId(zones: MutableList<ZoneInfo>, zoneId: Int, oldId: Int, newId: Int) {
        val idx = zones.indexOfFirst { it.zoneId == zoneId }
        if (idx < 0) return
        val zone = zones[idx]
        val ids = zone.objectInstanceIdsList.toMutableList()
        val idIdx = ids.indexOf(oldId)
        if (idIdx >= 0) {
            ids[idIdx] = newId
            zones[idx] = zone.toBuilder()
                .clearObjectInstanceIds()
                .addAllObjectInstanceIds(ids)
                .build()
        }
    }

    /** Append an instanceId to a zone's objectInstanceIds list. */
    private fun appendToZone(zones: MutableList<ZoneInfo>, zoneId: Int, instanceId: Int) {
        val idx = zones.indexOfFirst { it.zoneId == zoneId }
        if (idx < 0) return
        zones[idx] = zones[idx].toBuilder().addObjectInstanceIds(instanceId).build()
    }

    private fun makeZone(zoneId: Int, type: ZoneType, ownerSeatId: Int, visibility: Visibility): ZoneInfo =
        ZoneInfo.newBuilder()
            .setZoneId(zoneId)
            .setType(type)
            .setOwnerSeatId(ownerSeatId)
            .setVisibility(visibility)
            .build()

    /** Returns the hand zone ID of the opponent, or 0 if viewingSeatId is 0 (no filtering). */
    private fun opponentHandZone(viewingSeatId: Int): Int = when (viewingSeatId) {
        1 -> ZONE_P2_HAND
        2 -> ZONE_P1_HAND
        else -> 0
    }

    /** Private zone with viewers=[ownerSeatId] (hand, sideboard). */
    private fun makePrivateZone(zoneId: Int, type: ZoneType, ownerSeatId: Int): ZoneInfo =
        ZoneInfo.newBuilder()
            .setZoneId(zoneId)
            .setType(type)
            .setOwnerSeatId(ownerSeatId)
            .setVisibility(Visibility.Private)
            .addViewers(ownerSeatId)
            .build()
}
