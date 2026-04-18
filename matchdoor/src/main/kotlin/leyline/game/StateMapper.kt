package leyline.game

import forge.game.Game
import leyline.bridge.EffectId
import leyline.bridge.ForgeCardId
import leyline.bridge.GrpId
import leyline.bridge.InstanceId
import leyline.bridge.PromptSideEffect
import leyline.bridge.SeatId
import leyline.bridge.TargetingCoordinator
import leyline.game.mapper.ActionMapper
import leyline.game.mapper.ObjectMapper
import leyline.game.mapper.PlayerMapper
import leyline.game.mapper.ZoneIds
import leyline.game.mapper.ZoneMapper
import leyline.game.snapshot.GsmSnapshot
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Orchestrates the Forge→proto state mapping pipeline.
 *
 * Two core methods:
 * - [buildFromSnapshot]: Full [GameStateMessage] from an immutable [GsmSnapshot] (zones, objects,
 *   players, annotations via [ZoneTransferDetector], [TransferAnnotations], [CombatAnnotations], [MechanicAnnotations])
 * - [buildDiffFromGame]: Diff GSM containing only changes since the current diff baseline
 *
 * Lifecycle GSM factories (deal-hand, mulligan, transitions) live in [GsmBuilder].
 * Interactive request builders (targeting, combat) live in [RequestBuilder].
 * Pure Forge→proto projection lives in the `mapper/` subpackage.
 */
@Suppress("LargeClass") // pipeline orchestrator; stages already delegated to mapper/* and helper objects
object StateMapper {
    private val log = LoggerFactory.getLogger(StateMapper::class.java)

    /** Result of [buildFromSnapshot] — GSM plus metadata for message framing. */
    data class BuildResult(
        val gsm: GameStateMessage,
        /** True if a CastSpell zone transfer was detected (triggers QueuedGSM split). */
        val hasCastSpell: Boolean = false,
    )

    /**
     * Build a Full [GameStateMessage] from an immutable [GsmSnapshot].
     * Maps cards to client instanceIds via the bridge's card ID mapping.
     *
     * [viewingSeatId] controls hand visibility: opponent's hand cards get
     * objectInstanceIds (for card count) but no GameObjectInfo (renders face-down).
     * Use 0 to include all objects (internal snapshots for diffing).
     */
    @Suppress("LongMethod")
    fun buildFromSnapshot(
        snap: GsmSnapshot,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
    ): BuildResult {
        val human = bridge.getPlayer(SeatId(1))
        val ai = bridge.getPlayer(SeatId(2))
        val frame = GsmFrame.from(snap)

        // ═══ GATHER: drain queues, snapshot mutable state ═══
        val events = bridge.drainEvents().events.toMutableList()
        for (reveal in bridge.drainReveals(viewingSeatId)) {
            events.add(GameEvent.CardsRevealed(reveal.forgeCardIds, reveal.ownerSeatId))
        }
        // Evict stale AbilityRegistry entries for transformed cards so the next
        // abilityRegistryFor() call rebuilds from the current face.
        for (ev in events) {
            if (ev is GameEvent.CardTransformed) bridge.evictAbilityRegistry(ev.cardId.value)
        }
        val initEffectDiff = bridge.effects.emitInitEffectsOnce()
        val boostSnapshot = bridge.snapshotBoosts()
        val effectDiff = bridge.effects.diffBoosts(boostSnapshot)
        val keywordSnapshot = bridge.snapshotKeywords()
        val keywordDiff = bridge.effects.diffKeywords(keywordSnapshot)
        // Snapshot persistent state BEFORE compute — computeBatch is pure over this snapshot.
        // See PersistentAnnotationStore class KDoc for lifecycle and ordering invariants.
        val persistSnapshot = bridge.annotations.snapshot()
        val startPersistentId = bridge.annotations.currentPersistentId()
        val startAnnotationId = bridge.annotations.currentAnnotationId()

        // ═══ MAP: engine state → proto objects ═══
        val isBrawl = bridge.isBrawlOrCommander
        val gameVariant = if (isBrawl) GameVariant.Brawl else GameVariant.Normal

        val gameInfo = GameInfo.newBuilder()
            .setMatchID(matchId)
            .setGameNumber(1)
            .setStage(GameStage.Play_a920)
            .setType(GameType.Duel)
            .setVariant(gameVariant)
            .setMatchState(MatchState.GameInProgress)
            .setMatchWinCondition(MatchWinCondition.SingleElimination)
            .setMulliganType(MulliganType.London)
        if (isBrawl) {
            gameInfo.setDeckConstraintInfo(
                DeckConstraintInfo.newBuilder()
                    .setMinDeckSize(58).setMaxDeckSize(59).setMaxSideboardSize(1)
                    .setMinCommanderSize(1).setMaxCommanderSize(2),
            )
            gameInfo.setFreeMulliganCount(1)
        }

        val player1 = PlayerMapper.buildFromSnapshot(snap, 1)
        val player2 = PlayerMapper.buildFromSnapshot(snap, 2)

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

        // Detect active reveal-choose effect, clearing stale state if engine skipped the choice.
        val activeReveal = detectActiveReveal(bridge)
        val revealedHandSeat = activeReveal?.ownerSeatId?.value

        // Player 1 zones
        if (human != null) {
            ZoneMapper.addPlayerZonesFromSnapshot(
                1, snap, bridge, zones, gameObjects,
                ZoneIds.P1_HAND, ZoneIds.P1_LIBRARY, ZoneIds.P1_GRAVEYARD, viewingSeatId, revealForSeat,
                revealHand = revealedHandSeat == 1,
            )
        }
        zones.add(ZoneMapper.makePrivateZone(ZoneIds.P1_SIDEBOARD, ZoneType.Sideboard, 1))

        // Player 2 zones
        if (ai != null) {
            ZoneMapper.addPlayerZonesFromSnapshot(
                2, snap, bridge, zones, gameObjects,
                ZoneIds.P2_HAND, ZoneIds.P2_LIBRARY, ZoneIds.P2_GRAVEYARD, viewingSeatId, revealForSeat,
                revealHand = revealedHandSeat == 2,
            )
        }
        zones.add(ZoneMapper.makePrivateZone(ZoneIds.P2_SIDEBOARD, ZoneType.Sideboard, 2))

        // Populate shared zones with cards.
        ZoneMapper.addSharedZoneCardsFromSnapshot(snap, ForgeZoneType.Battlefield, ZoneIds.BATTLEFIELD, bridge, zones, gameObjects, human, keywordSnapshot)
        ZoneMapper.addSharedZoneCardsFromSnapshot(snap, ForgeZoneType.Stack, ZoneIds.STACK, bridge, zones, gameObjects, human)
        ZoneMapper.addSharedZoneCardsFromSnapshot(snap, ForgeZoneType.Exile, ZoneIds.EXILE, bridge, zones, gameObjects, human)
        ZoneMapper.addSharedZoneCardsFromSnapshot(snap, ForgeZoneType.Command, ZoneIds.COMMAND, bridge, zones, gameObjects, human)

        // Stack abilities (triggers, activated abilities not represented as zone cards)
        ZoneMapper.addStackAbilitiesFromSnapshot(snap, bridge, zones, gameObjects)

        // RevealedCard proxy synthesis / cleanup
        applyRevealProxies(activeReveal, snap, bridge, zones, gameObjects, events)

        log.info(
            "buildFromSnapshot: phase={} turn={} hand={} objects={} zones={}",
            snap.phase.phase,
            snap.phase.turn,
            human?.getZone(ForgeZoneType.Hand)?.size() ?: 0,
            gameObjects.size,
            zones.size,
        )

        // ═══ COMPUTE: annotation pipeline (stages 1-5) ═══
        val transferResult = ZoneTransferDetector.detectZoneTransfers(gameObjects, zones, bridge, events)
        val actingSeat = snap.phase.priorityPlayer?.value ?: 2
        val (annotations, transferPersistent, combatResult) =
            computeAnnotations(events, transferResult, actingSeat, bridge)

        // Snap-derived pAnn inputs — computed here where snap is in scope.
        val qualificationPersistentFromSnap = snap.objects.values
            .filter { it.isOnAdventure }
            .map { AnnotationBuilder.qualification(instanceId = bridge.getOrAllocInstanceId(it.forgeCardId)) }
        val temporaryPermanentPersistentFromSnap = snap.objects.values
            .filter { it.isOnBattlefield && it.endOfTurnLeavePlay }
            .map { AnnotationBuilder.temporaryPermanent(bridge.getOrAllocInstanceId(it.forgeCardId)) }
        val abilityWordPersistentFromSnap = snap.abilityWordEntries.map { entry ->
            AnnotationBuilder.abilityWordActive(
                instanceId = InstanceId(entry.instanceId),
                abilityWordName = entry.abilityWordName,
                value = entry.value,
                threshold = entry.threshold,
                abilityGrpId = entry.abilityGrpId?.let { GrpId(it) },
                affectorId = InstanceId(entry.affectorId ?: entry.instanceId),
                affectedIds = entry.affectedIds.ifEmpty { listOf(entry.instanceId) }.map { InstanceId(it) },
            )
        }

        // Stages 4-5 + persistent computation
        val remaining = computeRemainingAnnotations(
            events, annotations, transferPersistent, initEffectDiff, effectDiff,
            persistSnapshot, startPersistentId, startAnnotationId, bridge, keywordDiff,
            combatResult,
            qualificationPersistentFromSnap = qualificationPersistentFromSnap,
            temporaryPermanentPersistentFromSnap = temporaryPermanentPersistentFromSnap,
            abilityWordPersistentFromSnap = abilityWordPersistentFromSnap,
        )

        // ═══ ASSEMBLE: build the GSM proto ═══
        val built = assembleGsm(
            gameStateId, gameInfo.build(), frame, transferResult, remaining,
            combatResult, team1.build(), team2.build(), player1, player2,
            updateType, actions, actingSeat, bridge,
        )

        // ═══ APPLY: deferred tracking effects (for next GSM) ═══
        // Must run AFTER assembleGsm — the GSM already embedded batch.allAnnotations.
        // applyBatchResult replaces the live store so the next buildFromSnapshot sees updated state.
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
     *
     * Tech debt: compares [GameStateMessage] vs [GameStateMessage] (proto-level diff).
     * Full snapshot-vs-snapshot diff rewrite is a separate follow-up migration.
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
        val snap = GsmSnapshot.capture(game, bridge, matchId, gameStateId)
        if (prev == null) {
            // No baseline exists — fall back to Full, but snapshot it so the next
            // buildDiffFromGame call has a baseline and produces a real Diff.
            val result = buildFromSnapshot(snap, gameStateId, matchId, bridge, actions, updateType, viewingSeatId, revealForSeat)
            bridge.snapshotDiffBaseline(result.gsm)
            return result
        }

        // Build current full state (for comparison + to seed next diff).
        // Pass actions=null to avoid redundant action embedding (we embed below).
        // Use viewingSeatId=0 for the comparison base (needs all objects for accurate diff).
        val fullResult = buildFromSnapshot(snap, gameStateId, matchId, bridge, revealForSeat = revealForSeat)
        val current = fullResult.gsm

        // Compute changed zones (by objectInstanceIds)
        val prevZoneMap = prev.zonesList.associateBy { it.zoneId }
        val changedZones = current.zonesList.filter { zone ->
            val prevZone = prevZoneMap[zone.zoneId]
            prevZone == null ||
                prevZone.objectInstanceIdsList != zone.objectInstanceIdsList ||
                prevZone.visibility != zone.visibility ||
                prevZone.viewersList != zone.viewersList
        }

        // Compute changed/new objects, filtering out opponent hand objects
        // (except RevealedCard proxies and real hand cards during active reveal)
        val prevObjMap = prev.gameObjectsList.associateBy { it.instanceId }
        val opponentHandZoneId = ZoneMapper.opponentHandZone(viewingSeatId)
        val hasActiveReveal = bridge.allSeatIds().any { bridge.promptBridge(it).journal.activeReveal() != null }
        val changedObjects = current.gameObjectsList.filter { obj ->
            if (opponentHandZoneId != 0 && obj.zoneId == opponentHandZoneId) {
                // During reveal-choose: include RevealedCard proxies and Public hand cards
                if (hasActiveReveal && (obj.type == GameObjectType.RevealedCard || obj.visibility == Visibility.Public)) {
                    // fall through to normal change detection
                } else {
                    return@filter false
                }
            }
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
            // Priority player was already resolved during buildFromSnapshot — read from the built GSM.
            val activeSeat = current.turnInfo.priorityPlayer
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
     * Note: protocol uses SendAndRecord for ALL zone-transfer diffs, regardless
     * of whose turn it is. This heuristic (acting == viewing) is an approximation
     * used by postAction; remoteActionDiff hardcodes SendHiFi directly.
     */
    fun resolveUpdateType(snap: GsmSnapshot, viewingSeatId: Int): GameStateUpdate {
        val actingSeat = snap.phase.priorityPlayer?.value ?: snap.phase.activePlayer.value
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
        transferResult: TransferResult,
        remaining: RemainingAnnotationsResult,
        combatResult: CombatAnnotationResult,
        team1: TeamInfo,
        team2: TeamInfo,
        player1: PlayerInfo,
        player2: PlayerInfo,
        updateType: GameStateUpdate,
        actions: ActionsAvailableReq?,
        prioritySeat: Int,
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
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo.newBuilder()
                        .setSeatId(prioritySeat)
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
    @Suppress("LongParameterList", "LongMethod")
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
        keywordDiff: EffectTracker.KeywordDiffResult = EffectTracker.KeywordDiffResult(emptyList(), emptyList()),
        combatResult: CombatAnnotationResult = CombatAnnotationResult(emptyList()),
        qualificationPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        temporaryPermanentPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        abilityWordPersistentFromSnap: List<AnnotationInfo> = emptyList(),
    ): RemainingAnnotationsResult {
        val castSpellManaForgeIds = events
            .filterIsInstance<GameEvent.SpellCast>()
            .flatMap { it.manaPayments.map { mp -> mp.sourceCardId } }
            .toSet()
        val sacrificedManaForgeIds = events.filterIsInstance<GameEvent.ManaAbilityActivated>()
            .filter { ma -> events.any { it is GameEvent.CardSacrificed && it.cardId == ma.cardId } }
            .map { it.cardId }
            .toSet()
        val manaPaidForgeCardIds = castSpellManaForgeIds + sacrificedManaForgeIds
        val mechanicResult = MechanicAnnotations.mechanicAnnotations(
            events,
            manaPaidForgeCardIds,
            idResolver = { fid -> bridge.getOrAllocInstanceId(fid) },
            effectIdAllocator = { bridge.effects.nextEffectId() },
            activeStealForgeCardIds = bridge.annotations.activeStealForgeCardIds(),
        )
        annotations.addAll(mechanicResult.transient)

        // AbilityWordActive: consumed from pre-computed snap entries
        val abilityWordPersistent = abilityWordPersistentFromSnap

        if (initEffectDiff.created.isNotEmpty()) {
            val (initTransient, _) = MechanicAnnotations.effectAnnotations(initEffectDiff)
            annotations.addAll(initTransient)
        }

        val sourceAbilityResolver = SourceAbilityResolverFactory.build(bridge)
        val (effectTransient, effectPersistent) = MechanicAnnotations.effectAnnotations(
            diff = effectDiff,
            sourceAbilityResolver = sourceAbilityResolver,
            keywordDiff = keywordDiff,
            keywordAffectorResolver = { _, _, _ ->
                // Best-effort: use most recent SpellResolved event's instanceId as affector.
                // Full resolver (tracing spell → keyword grant) comes later.
                events.filterIsInstance<GameEvent.SpellResolved>()
                    .lastOrNull()
                    ?.let { bridge.getOrAllocInstanceId(it.cardId).value }
                    ?: 0
            },
            uniqueAbilityIdAllocator = { bridge.effects.nextEffectId() },
        )
        annotations.addAll(effectTransient)

        // Qualification pAnn for adventure-exiled cards (cast-from-exile eligibility marker)
        val qualificationPersistent = qualificationPersistentFromSnap

        // TemporaryPermanent pAnn for any token with EOT-sacrifice (copy or otherwise)
        val temporaryPermanentPersistent = temporaryPermanentPersistentFromSnap

        // TargetSpec pAnn for each targeted spell/ability on the stack
        val targetSpecPersistent = buildTargetSpecAnnotations(bridge)

        val (crewedThisTurnPersistent, crewTypeChangePersistent, crewExpiredAnnotations) =
            computeCrewAnnotations(bridge)
        annotations.addAll(crewExpiredAnnotations)

        val enrichedMechanicResult = mechanicResult.copy(
            abilityWordPersistent = abilityWordPersistent,
            qualificationPersistent = qualificationPersistent + mechanicResult.qualificationPersistent,
            crewedThisTurnPersistent = crewedThisTurnPersistent,
            crewTypeChangePersistent = crewTypeChangePersistent,
            temporaryPermanentPersistent = temporaryPermanentPersistent,
            targetSpecPersistent = targetSpecPersistent,
        )
        val batch = PersistentAnnotationStore.computeBatch(
            currentActive = persistSnapshot,
            startPersistentId = startPersistentId,
            effectPersistent = effectPersistent,
            effectDiff = effectDiff,
            transferPersistent = transferPersistent,
            mechanicResult = enrichedMechanicResult,
            combatResult = combatResult,
            resolveInstanceId = { fid -> bridge.getOrAllocInstanceId(fid) },
            resolveForgeCardId = { iid -> bridge.getForgeCardId(iid) },
        )

        // Emit LayeredEffectDestroyed for reverted steals
        for (effectId in batch.revertedEffectIds) {
            annotations.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effectId)))
        }

        // Track steal lifecycle
        bridge.annotations.addSteals(mechanicResult.controllerChangedEffects.map { it.forgeCardId })
        bridge.annotations.removeSteals(mechanicResult.controllerRevertedForgeCardIds)

        val ordered = AnnotationOrderEnforcer.enforce(annotations)
        var annId = startAnnotationId
        val numbered = ordered.map { it.toBuilder().setId(annId++).build() }
        return RemainingAnnotationsResult(numbered, batch.allAnnotations, batch, annId)
    }

    /** Stages 2-3 of the annotation pipeline: transfers → annotations + combat. */
    internal data class AnnotationPipelineResult(
        val annotations: MutableList<AnnotationInfo>,
        val transferPersistent: MutableList<AnnotationInfo>,
        val combatResult: CombatAnnotationResult,
    )

    /**
     * Assemble stages 2-3 around the key invariant for lethal damage:
     * DamageDealt must land before the victim's destroy transfer.
     */
    internal fun assembleTransferAndCombatAnnotations(
        events: List<GameEvent>,
        transferResult: TransferResult,
        actingSeat: Int,
        combatResult: CombatAnnotationResult,
    ): Pair<MutableList<AnnotationInfo>, MutableList<AnnotationInfo>> {
        val annotations = mutableListOf<AnnotationInfo>()
        val transferPersistent = mutableListOf<AnnotationInfo>()
        val lethalDamageVictims = events
            .filterIsInstance<GameEvent.DamageDealtToCard>()
            .map { it.targetCardId }
            .toSet()
        val (deferredTransfers, immediateTransfers) = transferResult.transfers.partition { transfer ->
            transfer.category == TransferCategory.Destroy &&
                transfer.forgeCardId != null &&
                transfer.forgeCardId in lethalDamageVictims
        }

        fun emitTransfer(transfer: AppliedTransfer) {
            val (transient, persistent) = TransferAnnotations.annotationsForTransfer(transfer, SeatId(actingSeat))
            annotations.addAll(transient)
            transferPersistent.addAll(persistent)
        }

        for (transfer in immediateTransfers) emitTransfer(transfer)
        for (a in transferResult.stackAbilityAppearances) {
            annotations.add(
                AnnotationBuilder.abilityInstanceCreated(
                    InstanceId(a.abilityInstanceId),
                    InstanceId(a.sourceCardInstanceId),
                    a.sourceZoneId,
                ),
            )
        }
        for (d in transferResult.stackAbilityDisappearances) {
            annotations.add(
                AnnotationBuilder.abilityInstanceDeleted(
                    InstanceId(d.abilityInstanceId),
                    InstanceId(d.sourceCardInstanceId),
                ),
            )
        }
        for (ev in events.filterIsInstance<GameEvent.PhaseChanged>()) {
            annotations.add(AnnotationBuilder.phaseOrStepModified(ev.seatId, ev.phase, ev.step))
        }
        annotations.addAll(combatResult.annotations)
        for (transfer in deferredTransfers) emitTransfer(transfer)
        return annotations to transferPersistent
    }

    /**
     * Scan the stack for spells/abilities with targets and emit TargetSpec pAnns.
     * Each card target gets a separate annotation with 1-based index per target group.
     * Removed automatically by upsertByType when the spell resolves (leaves stack).
     */
    private fun buildTargetSpecAnnotations(bridge: GameBridge): List<AnnotationInfo> {
        // Consume targets captured during selectTargetsInteractively.
        // The spell may have already resolved by now (auto-pass), so we can't
        // rely on scanning game.getStack() — the stack is often empty.
        val pending = bridge.drainPendingTargetSpecs()
        if (pending.isEmpty()) return emptyList()

        // TODO: abilityGrpId needs sub-ability registry lookup, promptId needs
        //  prompt-type mapping. Both require Arena card DB. Falls back to card grpId
        //  and 0 until wired.
        return pending.map { spec ->
            val spellIid = bridge.getOrAllocInstanceId(
                ForgeCardId(spec.spellForgeCardId + ObjectMapper.STACK_ABILITY_ID_OFFSET),
            )
            val targetIid = bridge.getOrAllocInstanceId(ForgeCardId(spec.targetForgeCardId))
            val grpId = GrpId(bridge.cardRepository.findGrpIdByName(spec.spellName) ?: 0)
            AnnotationBuilder.targetSpec(
                instanceId = targetIid,
                affectorId = spellIid,
                abilityGrpId = grpId,
                index = spec.index,
                promptId = 0,
                promptParameters = spellIid.value,
            )
        }
    }

    /** Crew annotation scan: CrewedThisTurn pAnns, ModifiedType pAnns, and expired effect annotations. */
    private fun computeCrewAnnotations(
        bridge: GameBridge,
    ): Triple<List<AnnotationInfo>, List<AnnotationInfo>, List<AnnotationInfo>> {
        val crewSnapshots = bridge.snapshotCrewState()
        val crewedThisTurn = crewSnapshots.map { snap ->
            AnnotationBuilder.crewedThisTurn(
                InstanceId(snap.vehicleInstanceId),
                snap.crewSourceInstanceIds.map { InstanceId(it) },
            )
        }
        val typeChange = mutableListOf<AnnotationInfo>()
        val expired = mutableListOf<AnnotationInfo>()

        val currentCrewedFids = crewSnapshots.filter { it.isCreature }.map { it.vehicleForgeCardId }.toSet()
        for (effectId in bridge.releaseCrewEffects(currentCrewedFids)) {
            expired.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effectId)))
        }
        for (snap in crewSnapshots) {
            if (!snap.isCreature) continue
            val effectId = EffectId(bridge.getOrAllocCrewEffectId(snap.vehicleForgeCardId))
            typeChange.add(
                AnnotationBuilder.modifiedTypeLayeredEffect(
                    instanceId = InstanceId(snap.vehicleInstanceId),
                    effectId = effectId,
                    sourceAbilityGrpId = snap.crewAbilityGrpId?.let { GrpId(it) },
                ),
            )
        }
        return Triple(crewedThisTurn, typeChange, expired)
    }

    /**
     * Find the active reveal across all seats, or null. Clears stale reveals where
     * proxies were synthesized but the engine skipped the choice method
     * (e.g., Duress vs all-creature hand → DiscardEffect short-circuits at max==0).
     */
    private fun detectActiveReveal(bridge: GameBridge): PromptSideEffect.RevealStarted? =
        bridge.allSeatIds().firstNotNullOfOrNull { seatId ->
            val prompt = bridge.promptBridge(seatId)
            val reveal = prompt.journal.activeReveal() ?: return@firstNotNullOfOrNull null
            if (!bridge.revealProxies.isEmpty && prompt.getPendingPrompt() == null) {
                TargetingCoordinator.endReveal(prompt) // stale — engine skipped choice
                null
            } else {
                reveal
            }
        }

    /**
     * Synthesize RevealedCard proxy objects during active reveal-choose, or
     * schedule proxy cleanup when the reveal ends. Modifies [zones], [gameObjects],
     * and [events] in place.
     */
    // Nullable `activeReveal` is intentional: the function has two branches —
    // synthesize proxies when non-null, cleanup-and-clear when null.
    @Suppress("CanBeNonNullable")
    private fun applyRevealProxies(
        activeReveal: PromptSideEffect.RevealStarted?,
        snap: leyline.game.snapshot.GsmSnapshot,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        events: MutableList<GameEvent>,
    ) {
        if (activeReveal != null) {
            val ownerSeat = activeReveal.ownerSeatId.value
            val viewerSeat = if (ownerSeat == 1) 2 else 1
            val handZoneId = if (ownerSeat == 1) ZoneIds.P1_HAND else ZoneIds.P2_HAND
            val revealedZoneId = if (ownerSeat == 1) ZoneIds.REVEALED_P1 else ZoneIds.REVEALED_P2

            val revealedZoneIdx = zones.indexOfFirst { it.zoneId == revealedZoneId }
            val revealedZoneBuilder = if (revealedZoneIdx >= 0) {
                zones.removeAt(revealedZoneIdx).toBuilder()
            } else {
                ZoneMapper.makeZone(revealedZoneId, ZoneType.Revealed, ownerSeat, Visibility.Public).toBuilder()
            }

            // Re-use proxy IDs across diffs during the same reveal (stable instanceIds).
            val needsAlloc = bridge.revealProxies.isEmpty
            for (forgeCardId in activeReveal.allHandCardIds) {
                val cardSnap = snap.objects[forgeCardId] ?: continue
                val proxyId = if (needsAlloc) {
                    val id = bridge.ids.allocSynthetic()
                    bridge.revealProxies.allocate(forgeCardId, id)
                    id
                } else {
                    bridge.revealProxies.lookup(forgeCardId) ?: continue
                }
                revealedZoneBuilder.addObjectInstanceIds(proxyId.value)
                gameObjects.add(
                    ObjectMapper.buildRevealedCardProxy(cardSnap, proxyId.value, handZoneId, ownerSeat, viewerSeat, bridge),
                )
            }
            zones.add(revealedZoneBuilder.build())
        } else if (!bridge.revealProxies.isEmpty) {
            // Reveal ended — emit cleanup annotations and clear tracking.
            // Diff naturally detects missing proxy objects via snapshot-compare.
            val deletedProxies = bridge.revealProxies.drain()
            events.add(GameEvent.RevealProxiesDeleted(deletedProxies))
        }
    }
    internal fun computeAnnotations(
        events: List<GameEvent>,
        transferResult: TransferResult,
        actingSeat: Int,
        bridge: GameBridge,
    ): AnnotationPipelineResult {
        val combatTransferredIds = transferResult.transfers
            .mapNotNull { transfer -> transfer.forgeCardId?.let { it to transfer.origId } }
            .toMap()
        val combatResult = CombatAnnotations.combatAnnotations(
            events = events,
            bridge = bridge,
            transferredIds = combatTransferredIds,
        )
        val (annotations, transferPersistent) = assembleTransferAndCombatAnnotations(
            events = events,
            transferResult = transferResult,
            actingSeat = actingSeat,
            combatResult = combatResult,
        )
        return AnnotationPipelineResult(annotations, transferPersistent, combatResult)
    }
}
