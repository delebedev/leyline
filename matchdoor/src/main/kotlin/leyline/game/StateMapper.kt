package leyline.game

import forge.game.Game
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
import leyline.bridge.findCard
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

    /** Result of [buildFromGame] — GSM plus metadata for message framing. */
    data class BuildResult(
        val gsm: GameStateMessage,
        /** True if a CastSpell zone transfer was detected (triggers QueuedGSM split). */
        val hasCastSpell: Boolean = false,
    )

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
        revealForSeat: Int? = null,
    ): BuildResult {
        val handler = game.phaseHandler
        val human = bridge.getPlayer(SeatId(1))
        val ai = bridge.getPlayer(SeatId(2))
        val frame = GsmFrame.from(game, bridge)

        // ═══ GATHER: drain queues, snapshot mutable state ═══
        val events = bridge.drainEvents().events.toMutableList()
        for (reveal in bridge.drainReveals(viewingSeatId)) {
            events.add(GameEvent.CardsRevealed(reveal.forgeCardIds, reveal.ownerSeatId))
        }
        val initEffectDiff = bridge.effects.emitInitEffectsOnce()
        val boostSnapshot = bridge.snapshotBoosts()
        val effectDiff = bridge.effects.diffBoosts(boostSnapshot)
        // Snapshot persistent state BEFORE compute — computeBatch is pure over this snapshot.
        // See PersistentAnnotationStore class KDoc for lifecycle and ordering invariants.
        val persistSnapshot = bridge.annotations.snapshot()
        val startPersistentId = bridge.annotations.currentPersistentId()
        val startAnnotationId = bridge.annotations.currentAnnotationId()

        // ═══ MAP: engine state → proto objects ═══
        val gameInfo = GameInfo.newBuilder()
            .setMatchID(matchId)
            .setGameNumber(1)
            .setStage(GameStage.Play_a920)
            .setType(GameType.Duel)
            .setVariant(GameVariant.Normal)
            .setMatchState(MatchState.GameInProgress)
            .setMatchWinCondition(MatchWinCondition.SingleElimination)
            .setMulliganType(MulliganType.London)

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
            ZoneIds.P1_HAND, ZoneIds.P1_LIBRARY, ZoneIds.P1_GRAVEYARD, viewingSeatId, revealForSeat,
        )
        zones.add(ZoneMapper.makePrivateZone(ZoneIds.P1_SIDEBOARD, ZoneType.Sideboard, 1))

        // Player 2 zones
        ZoneMapper.addPlayerZones(
            game, ai, 2, bridge, zones, gameObjects,
            ZoneIds.P2_HAND, ZoneIds.P2_LIBRARY, ZoneIds.P2_GRAVEYARD, viewingSeatId, revealForSeat,
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

        // ═══ COMPUTE: annotation pipeline (stages 1-5) ═══
        val transferResult = AnnotationPipeline.detectZoneTransfers(gameObjects, zones, bridge, events)
        val actingSeat = if (handler.priorityPlayer == human) 1 else 2
        val (annotations, transferPersistent, combatResult) =
            computeAnnotations(events, transferResult, actingSeat, bridge)

        // Stages 4-5 + persistent computation
        val remaining = computeRemainingAnnotations(
            events, annotations, transferPersistent, initEffectDiff, effectDiff,
            persistSnapshot, startPersistentId, startAnnotationId, bridge,
        )

        // ═══ ASSEMBLE: build the GSM proto ═══
        val built = assembleGsm(
            gameStateId, gameInfo.build(), frame, transferResult, remaining,
            combatResult, team1.build(), team2.build(), player1, player2,
            updateType, actions, handler, human, bridge,
        )

        // ═══ APPLY: deferred tracking effects (for next GSM) ═══
        // Must run AFTER assembleGsm — the GSM already embedded batch.allAnnotations.
        // applyBatchResult replaces the live store so the next buildFromGame sees updated state.
        for (id in transferResult.retiredIds) bridge.retireToLimbo(InstanceId(id))
        for ((iid, zid) in transferResult.zoneRecordings) bridge.recordZone(InstanceId(iid), zid)
        bridge.annotations.applyBatchResult(remaining.batch)
        bridge.annotations.setAnnotationId(remaining.nextAnnotationId)

        val hasCastSpell = transferResult.transfers.any { it.category == TransferCategory.CastSpell }
        return BuildResult(built, hasCastSpell)
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
        revealForSeat: Int? = null,
    ): BuildResult {
        val prev = bridge.getDiffBaselineState()
        if (prev == null) {
            // No baseline exists — fall back to Full, but snapshot it so the next
            // buildDiffFromGame call has a baseline and produces a real Diff.
            val result = buildFromGame(game, gameStateId, matchId, bridge, actions, updateType, viewingSeatId, revealForSeat)
            bridge.snapshotDiffBaseline(result.gsm)
            return result
        }

        // Build current full state (for comparison + to seed next diff).
        // Pass actions=null to avoid redundant action embedding (we embed below).
        // Use viewingSeatId=0 for the comparison base (needs all objects for accurate diff).
        val fullResult = buildFromGame(game, gameStateId, matchId, bridge, revealForSeat = revealForSeat)
        val current = fullResult.gsm

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
        return BuildResult(built, fullResult.hasCastSpell)
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

    /** Assemble the final GameStateMessage proto from computed components. */
    @Suppress("LongParameterList")
    private fun assembleGsm(
        gameStateId: Int,
        gameInfo: GameInfo,
        frame: GsmFrame,
        transferResult: AnnotationPipeline.TransferResult,
        remaining: RemainingAnnotationsResult,
        combatResult: AnnotationPipeline.CombatAnnotationResult,
        team1: TeamInfo,
        team2: TeamInfo,
        player1: PlayerInfo,
        player2: PlayerInfo,
        updateType: GameStateUpdate,
        actions: ActionsAvailableReq?,
        handler: forge.game.phase.PhaseHandler,
        human: forge.game.player.Player?,
        bridge: GameBridge,
    ): GameStateMessage {
        val prevState = bridge.getDiffBaselineState()
        val effectiveTurnInfo = if (combatResult.hasCombatDamage) {
            frame.turnInfo().toBuilder().setPhase(Phase.Combat_a549).setStep(Step.CombatDamage_a2cb)
        } else {
            frame.turnInfo().toBuilder()
        }

        val builder = GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(gameStateId)
            .setGameInfo(gameInfo)
            .addAllTeams(listOf(team1, team2))
            .setTurnInfo(effectiveTurnInfo)
            .addAllPlayers(listOf(player1, player2))
            .addAllZones(transferResult.patchedZones.sortedBy { it.zoneId })
            .addAllGameObjects(transferResult.patchedObjects)
            .addAllAnnotations(remaining.numbered)
            .addAllPersistentAnnotations(remaining.persistent)
            .addAllTimers(PlayerMapper.buildTimers())
            .setUpdate(updateType)
        if (prevState != null && prevState.gameStateId > 0) {
            builder.setPrevGameStateId(prevState.gameStateId)
        }

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

    /** Result of stages 4-5 + persistent annotation computation. */
    private data class RemainingAnnotationsResult(
        val numbered: List<AnnotationInfo>,
        val persistent: List<AnnotationInfo>,
        val batch: PersistentAnnotationStore.BatchResult,
        val nextAnnotationId: Int,
    )

    /** Stages 4-5: mechanic + effect annotations, persistent computation, numbering. */
    @Suppress("LongParameterList")
    private fun computeRemainingAnnotations(
        events: List<GameEvent>,
        annotations: MutableList<AnnotationInfo>,
        transferPersistent: List<AnnotationInfo>,
        initEffectDiff: EffectTracker.DiffResult,
        effectDiff: EffectTracker.DiffResult,
        persistSnapshot: Map<Int, AnnotationInfo>,
        startPersistentId: Int,
        startAnnotationId: Int,
        bridge: GameBridge,
    ): RemainingAnnotationsResult {
        val castSpellManaForgeIds = events
            .filterIsInstance<GameEvent.SpellCast>()
            .flatMap { it.manaPayments.map { mp -> mp.sourceForgeCardId } }
            .toSet()
        val sacrificedManaForgeIds = events.filterIsInstance<GameEvent.ManaAbilityActivated>()
            .filter { ma -> events.any { it is GameEvent.CardSacrificed && it.forgeCardId == ma.forgeCardId } }
            .map { it.forgeCardId }
            .toSet()
        val manaPaidForgeCardIds = castSpellManaForgeIds + sacrificedManaForgeIds
        val mechanicResult = AnnotationPipeline.mechanicAnnotations(
            events,
            manaPaidForgeCardIds,
            idResolver = { fid -> bridge.getOrAllocInstanceId(fid) },
            effectIdAllocator = { bridge.effects.nextEffectId() },
            activeStealForgeCardIds = bridge.annotations.activeStealForgeCardIds(),
        )
        annotations.addAll(mechanicResult.transient)

        // AbilityWordActive: scan battlefield for ability word conditions
        val humanPlayer = bridge.getPlayer(SeatId(1))
        val abilityWordPersistent = if (humanPlayer != null) {
            val bfCards = humanPlayer.getZone(forge.game.zone.ZoneType.Battlefield).cards.toList()
            AbilityWordScanner.scan(
                battlefieldCards = bfCards,
                player = humanPlayer,
                instanceIdResolver = { fid -> bridge.getOrAllocInstanceId(fid) },
                registryResolver = { card ->
                    val grpId = bridge.cards.findGrpIdByName(card.name) ?: 0
                    val cardData = bridge.cards.findByGrpId(grpId)
                    bridge.abilityRegistryFor(card, cardData)
                },
            ).map { entry ->
                AnnotationBuilder.abilityWordActive(
                    instanceId = entry.instanceId,
                    abilityWordName = entry.abilityWordName,
                    value = entry.value,
                    threshold = entry.threshold,
                    abilityGrpId = entry.abilityGrpId,
                    affectorId = entry.affectorId ?: entry.instanceId,
                )
            }
        } else {
            emptyList()
        }

        if (initEffectDiff.created.isNotEmpty()) {
            val (initTransient, _) = AnnotationPipeline.effectAnnotations(initEffectDiff)
            annotations.addAll(initTransient)
        }

        val sourceAbilityResolver = buildSourceAbilityResolver(bridge)
        val (effectTransient, effectPersistent) = AnnotationPipeline.effectAnnotations(effectDiff, sourceAbilityResolver)
        annotations.addAll(effectTransient)

        val enrichedMechanicResult = mechanicResult.copy(abilityWordPersistent = abilityWordPersistent)
        val batch = PersistentAnnotationStore.computeBatch(
            currentActive = persistSnapshot,
            startPersistentId = startPersistentId,
            effectPersistent = effectPersistent,
            effectDiff = effectDiff,
            transferPersistent = transferPersistent,
            mechanicResult = enrichedMechanicResult,
            resolveInstanceId = { fid -> bridge.getOrAllocInstanceId(fid) },
            resolveForgeCardId = { iid -> bridge.getForgeCardId(iid) },
        )

        // Emit LayeredEffectDestroyed for reverted steals
        for (effectId in batch.revertedEffectIds) {
            annotations.add(AnnotationBuilder.layeredEffectDestroyed(effectId))
        }

        // Track steal lifecycle
        bridge.annotations.addSteals(mechanicResult.controllerChangedEffects.map { it.forgeCardId })
        bridge.annotations.removeSteals(mechanicResult.controllerRevertedForgeCardIds)

        var annId = startAnnotationId
        val numbered = annotations.map { it.toBuilder().setId(annId++).build() }
        return RemainingAnnotationsResult(numbered, batch.allAnnotations, batch, annId)
    }

    /** Stages 2-3 of the annotation pipeline: transfers → annotations + combat. */
    private data class AnnotationPipelineResult(
        val annotations: MutableList<AnnotationInfo>,
        val transferPersistent: MutableList<AnnotationInfo>,
        val combatResult: AnnotationPipeline.CombatAnnotationResult,
    )

    private fun computeAnnotations(
        events: List<GameEvent>,
        transferResult: AnnotationPipeline.TransferResult,
        actingSeat: Int,
        bridge: GameBridge,
    ): AnnotationPipelineResult {
        val annotations = mutableListOf<AnnotationInfo>()
        val transferPersistent = mutableListOf<AnnotationInfo>()
        for (transfer in transferResult.transfers) {
            val (transient, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat)
            annotations.addAll(transient)
            transferPersistent.addAll(persistent)
        }
        for (ev in events.filterIsInstance<GameEvent.PhaseChanged>()) {
            annotations.add(AnnotationBuilder.phaseOrStepModified(ev.seatId, ev.phase, ev.step))
        }
        val combatResult = AnnotationPipeline.combatAnnotations(events, bridge)
        annotations.addAll(combatResult.annotations)
        return AnnotationPipelineResult(annotations, transferPersistent, combatResult)
    }

    /** Keywords whose triggered/resolved effects produce P/T boosts with staticId=0. */
    private val PT_BOOST_KEYWORDS = setOf("PROWESS")

    /**
     * Build a resolver: (cardInstanceId, staticId) → sourceAbilityGRPID.
     *
     * Two resolution paths:
     * - **staticId > 0**: continuous effect from a StaticAbility — use [AbilityRegistry]
     *   to look up the specific ability. Falls back to keyword parent tracing for
     *   non-intrinsic temporaries.
     * - **staticId == 0**: resolved spell/trigger pump (e.g. Prowess) — falls back to
     *   [CardData.keywordAbilityGrpIds] heuristic since Forge doesn't tag these with
     *   a source ability ID.
     */
    private fun buildSourceAbilityResolver(
        bridge: GameBridge,
    ): (InstanceId, Long) -> Int? {
        val game = bridge.getGame() ?: return { _, _ -> null }
        return resolver@{ instanceId, staticId ->
            val forgeCardId = bridge.getForgeCardId(instanceId) ?: return@resolver null
            val card = findCard(game, forgeCardId) ?: return@resolver null
            val grpId = bridge.cards.findGrpIdByName(card.name) ?: return@resolver null
            val cardData = bridge.cards.findByGrpId(grpId) ?: return@resolver null

            // Resolved pump effects (Prowess, Giant Growth): staticId = 0
            // Fall back to keyword heuristic — best we can do without Forge tagging
            if (staticId == 0L) {
                for (keyword in PT_BOOST_KEYWORDS) {
                    cardData.keywordAbilityGrpIds[keyword]?.let { return@resolver it }
                }
                return@resolver null
            }

            if (staticId > Int.MAX_VALUE) return@resolver null

            // Continuous effects: use AbilityRegistry for precise lookup
            val registry = bridge.abilityRegistryFor(card, cardData) ?: return@resolver null
            registry.forStaticAbility(staticId.toInt())?.let { return@resolver it }

            // Keyword fallback: temporary statics from keyword triggers
            // trace back to parent keyword via Forge's StaticAbility.getKeyword()
            val sourceStatic = card.staticAbilities?.firstOrNull { it.id == staticId.toInt() }
            val parentKeyword = sourceStatic?.keyword ?: return@resolver null
            for (sa in parentKeyword.abilities) {
                registry.forSpellAbility(sa.id)?.let { return@resolver it }
            }
            for (trig in parentKeyword.triggers) {
                registry.forTrigger(trig.id)?.let { return@resolver it }
            }
            for (st in parentKeyword.staticAbilities) {
                registry.forStaticAbility(st.id)?.let { return@resolver it }
            }
            null
        }
    }
}
