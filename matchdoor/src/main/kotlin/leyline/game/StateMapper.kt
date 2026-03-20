package leyline.game

import forge.game.Game
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
import leyline.game.mapper.ActionMapper
import leyline.game.mapper.PlayerMapper
import leyline.game.mapper.ZoneIds
import leyline.game.mapper.ZoneMapper
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Orchestrates the Forge→proto state mapping pipeline.
 *
 * Two core methods:
 * - [buildFromGame]: Full [GameStateMessage] from live engine state (zones, objects,
 *   players, annotations via [AnnotationPipeline])
 * - [buildDiffFromGame]: Diff GSM containing only changes since the current diff baseline
 *
 * Lifecycle GSM factories (deal-hand, mulligan, transitions) live in [GsmBuilder].
 * Interactive request builders (targeting, combat) live in [RequestBuilder].
 * Pure Forge→proto projection lives in the `mapper/` subpackage.
 */
object StateMapper {
    private val log = LoggerFactory.getLogger(StateMapper::class.java)

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
        val human = bridge.getPlayer(SeatId(1))
        val ai = bridge.getPlayer(SeatId(2))

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
            .setPhase(PlayerMapper.mapPhase(handler.phase))
            .setStep(PlayerMapper.mapStep(handler.phase))
            .setTurnNumber(handler.turn.coerceAtLeast(1))
            .setActivePlayer(if (handler.playerTurn == human) 1 else 2)
            .setPriorityPlayer(if (handler.priorityPlayer == human) 1 else 2)
            .setDecisionPlayer(if (handler.priorityPlayer == human) 1 else 2)

        val player1 = PlayerMapper.buildPlayerInfo(human, 1)
        val player2 = PlayerMapper.buildPlayerInfo(ai, 2)

        val team1 = TeamInfo.newBuilder().setId(1).addPlayerIds(1).setStatus(TeamStatus.InGame_a458)
        val team2 = TeamInfo.newBuilder().setId(2).addPlayerIds(2).setStatus(TeamStatus.InGame_a458)

        val zones = mutableListOf<ZoneInfo>()
        val gameObjects = mutableListOf<GameObjectInfo>()

        // Standard zone layout (17 zones, IDs 18-38) — must send all for Full state
        zones.add(ZoneMapper.makeZone(ZoneIds.REVEALED_P1, ZoneType.Revealed, 1, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.REVEALED_P2, ZoneType.Revealed, 2, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.SUPPRESSED, ZoneType.Suppressed, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.PENDING, ZoneType.Pending, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.COMMAND, ZoneType.Command, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.STACK, ZoneType.Stack, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.EXILE, ZoneType.Exile, 0, Visibility.Public))
        // Limbo zone: include all previously accumulated retired instanceIds.
        // New retirements are appended in the annotation loop below.
        val limboZone = ZoneInfo.newBuilder()
            .setZoneId(ZoneIds.LIMBO)
            .setType(ZoneType.Limbo)
            .setVisibility(Visibility.Public)
        for (id in bridge.getLimboInstanceIds()) {
            limboZone.addObjectInstanceIds(id.value)
        }
        zones.add(limboZone.build())

        // Player 1 zones
        ZoneMapper.addPlayerZones(
            game, human, 1, bridge, zones, gameObjects,
            ZoneIds.P1_HAND, ZoneIds.P1_LIBRARY, ZoneIds.P1_GRAVEYARD, viewingSeatId,
        )
        zones.add(ZoneMapper.makePrivateZone(ZoneIds.P1_SIDEBOARD, ZoneType.Sideboard, 1))

        // Player 2 zones
        ZoneMapper.addPlayerZones(
            game, ai, 2, bridge, zones, gameObjects,
            ZoneIds.P2_HAND, ZoneIds.P2_LIBRARY, ZoneIds.P2_GRAVEYARD, viewingSeatId,
        )
        zones.add(ZoneMapper.makePrivateZone(ZoneIds.P2_SIDEBOARD, ZoneType.Sideboard, 2))

        // Populate shared zones with any cards
        ZoneMapper.addSharedZoneCards(game, ForgeZoneType.Battlefield, ZoneIds.BATTLEFIELD, bridge, zones, gameObjects, human, ai)
        ZoneMapper.addSharedZoneCards(game, ForgeZoneType.Stack, ZoneIds.STACK, bridge, zones, gameObjects, human, ai)
        ZoneMapper.addSharedZoneCards(game, ForgeZoneType.Exile, ZoneIds.EXILE, bridge, zones, gameObjects, human, ai)

        // Stack abilities (triggers, activated abilities not represented as zone cards)
        ZoneMapper.addStackAbilities(game, bridge, zones, gameObjects, human)

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
        val events = bridge.drainEvents().toMutableList()
        // Drain reveal records from the prompt bridge (captured in WebPlayerController.reveal())
        for (reveal in bridge.drainReveals(viewingSeatId)) {
            events.add(GameEvent.CardsRevealed(reveal.forgeCardIds, reveal.ownerSeatId))
        }
        val transferResult = AnnotationPipeline.detectZoneTransfers(gameObjects, zones, bridge, events)
        // Apply deferred tracking side effects
        for (id in transferResult.retiredIds) bridge.retireToLimbo(InstanceId(id))
        for ((iid, zid) in transferResult.zoneRecordings) bridge.recordZone(InstanceId(iid), zid)

        // Stage 2: Generate annotations from transfers (pure, no side effects)
        val actingSeat = if (handler.priorityPlayer == human) 1 else 2
        val annotations = mutableListOf<AnnotationInfo>()
        val transferPersistent = mutableListOf<AnnotationInfo>()
        for (transfer in transferResult.transfers) {
            val (transient, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat)
            annotations.addAll(transient)
            transferPersistent.addAll(persistent)
        }

        // Stage 3: Combat damage annotations (event-driven — events captured before Forge clears combat)
        annotations.addAll(AnnotationPipeline.combatAnnotations(events, bridge))

        // Stage 4: Mechanic annotations (Group B: counters, shuffle, scry, tokens + Group A+: attachments)
        val mechanicResult = AnnotationPipeline.mechanicAnnotations(events) { forgeCardId ->
            bridge.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value
        }
        annotations.addAll(mechanicResult.transient)

        // gsId=1 init effects: 3 effects created+destroyed immediately
        val initDiff = bridge.effects.emitInitEffectsOnce()
        if (initDiff.created.isNotEmpty()) {
            val (initTransient, _) = AnnotationPipeline.effectAnnotations(initDiff)
            annotations.addAll(initTransient)
            // No persistent annotations stored — they're destroyed in the same GSM
        }

        // Stage 5: Layered effect lifecycle (P/T boost diffing)
        val boostSnapshot = bridge.snapshotBoosts()
        val effectDiff = bridge.effects.diffBoosts(boostSnapshot)

        // Resolve sourceAbilityGRPID: instanceId → card keyword → abilityGrpId
        val sourceAbilityResolver = buildSourceAbilityResolver(game, bridge)
        val (effectTransient, effectPersistent) = AnnotationPipeline.effectAnnotations(effectDiff, sourceAbilityResolver)
        annotations.addAll(effectTransient)

        // Apply all persistent annotation mutations in one batch
        bridge.annotations.applyBatch(
            effectPersistent = effectPersistent,
            effectDiff = effectDiff,
            transferPersistent = transferPersistent,
            mechanicResult = mechanicResult,
        ) { forgeCardId -> bridge.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value }

        val allPersistentAnnotations = bridge.annotations.getAll()

        val numberedAnnotations = annotations.map {
            it.toBuilder().setId(bridge.nextAnnotationId()).build()
        }

        // prevGameStateId: reference prior state if one exists
        val prevState = bridge.getDiffBaselineState()
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
            .addAllPersistentAnnotations(allPersistentAnnotations)
            .addAllTimers(PlayerMapper.buildTimers())
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
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
        }

        return builder.build()
    }

    /**
     * Build a Diff [GameStateMessage] containing only zones/objects that changed
     * since the current diff baseline. Falls back to Full if no baseline exists.
     * Updates the bridge's diff baseline after building so the next diff is relative
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
        val prev = bridge.getDiffBaselineState()
        if (prev == null) {
            // No baseline exists — fall back to Full, but snapshot it so the next
            // buildDiffFromGame call has a baseline and produces a real Diff.
            val full = buildFromGame(game, gameStateId, matchId, bridge, actions, updateType, viewingSeatId)
            bridge.snapshotDiffBaseline(full)
            return full
        }

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
        val opponentHandZoneId = ZoneMapper.opponentHandZone(viewingSeatId)
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
            .addAllDiffDeletedPersistentAnnotationIds(bridge.annotations.drainDeletions())
            .addAllTimers(PlayerMapper.buildTimers())
            .setUpdate(updateType)
            .setPrevGameStateId(prev.gameStateId)

        if (deletedIds.isNotEmpty()) {
            builder.addAllDiffDeletedInstanceIds(deletedIds)
        }

        // Embed stripped-down actions + set pendingMessageCount when AAR follows
        if (actions != null) {
            builder.setPendingMessageCount(1)
            val handler = game.phaseHandler
            val human = bridge.getPlayer(SeatId(1))
            val activeSeat = if (handler.priorityPlayer == human) 1 else 2
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo.newBuilder()
                        .setSeatId(activeSeat)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
        }

        // Update snapshot for next diff (reuse the full GSM already built above)
        bridge.snapshotDiffBaseline(current)

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

    /**
     * Resolve the correct updateType for a game state message.
     * - SendAndRecord: state change the client must persist (zone transfers, actions)
     * - SendHiFi: transient update (phase echoes, state refreshes)
     *
     * Note: real server uses SendAndRecord for ALL zone-transfer diffs, regardless
     * of whose turn it is. This heuristic (acting == viewing) is an approximation
     * used by postAction; remoteActionDiff hardcodes SendHiFi directly.
     */
    fun resolveUpdateType(game: Game, bridge: GameBridge, viewingSeatId: Int): GameStateUpdate {
        val human = bridge.getPlayer(SeatId(1))
        val actingSeat = if (game.phaseHandler.priorityPlayer == human) 1 else 2
        return if (actingSeat == viewingSeatId) {
            GameStateUpdate.SendAndRecord
        } else {
            GameStateUpdate.SendHiFi
        }
    }

    /** Keywords whose triggered/static abilities produce P/T boosts.
     *  Only Prowess for now — battle cry, lord anthems, and other continuous
     *  P/T effects don't yet carry sourceAbilityGRPID. Extend as needed. */
    private val PT_BOOST_KEYWORDS = setOf("PROWESS")

    /**
     * Build a resolver: cardInstanceId → sourceAbilityGRPID.
     * Scans battlefield once, then checks each card for P/T-boost keywords.
     */
    private fun buildSourceAbilityResolver(game: Game, bridge: GameBridge): (Int) -> Int? {
        // Build instanceId → card name map from battlefield (same cards snapshotBoosts iterates)
        val instanceIdToName = mutableMapOf<Int, String>()
        for (player in game.players) {
            for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
                val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
                instanceIdToName[instanceId] = card.name
            }
        }

        return resolver@{ instanceId ->
            val name = instanceIdToName[instanceId] ?: return@resolver null
            val grpId = bridge.cards.findGrpIdByName(name) ?: return@resolver null
            val cardData = bridge.cards.findByGrpId(grpId) ?: return@resolver null
            for (keyword in PT_BOOST_KEYWORDS) {
                cardData.keywordAbilityGrpIds[keyword]?.let { return@resolver it }
            }
            null
        }
    }
}
