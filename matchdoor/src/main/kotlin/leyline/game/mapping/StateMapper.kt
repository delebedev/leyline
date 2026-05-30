package leyline.game.mapping

import leyline.DevCheck
import leyline.bridge.coord.TargetingCoordinator
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.EffectId
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationOrderEnforcer
import leyline.game.annotations.AppliedTransfer
import leyline.game.annotations.CombatAnnotationResult
import leyline.game.annotations.CombatAnnotations
import leyline.game.annotations.MechanicAnnotations
import leyline.game.annotations.TransferAnnotations
import leyline.game.annotations.TransferCategory
import leyline.game.annotations.TransferResult
import leyline.game.annotations.ZoneTransferDetector
import leyline.game.bundle.GsmFrame
import leyline.game.codes.CounterTypes
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.SnapDeltaSynthesizer
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityWireIdentity
import leyline.game.state.AbilityWordActiveKind
import leyline.game.state.BridgeMutations
import leyline.game.state.ColorProductionKind
import leyline.game.state.CommanderDesignationKind
import leyline.game.state.CrewedThisTurnKind
import leyline.game.state.DayNightDesignationKind
import leyline.game.state.DelayedTriggerAffecteesKind
import leyline.game.state.EffectTracker
import leyline.game.state.FaceDownDisguiseKind
import leyline.game.state.FrameContext
import leyline.game.state.GameBridge
import leyline.game.state.HolderBatch
import leyline.game.state.LeftUnlockedDesignationKind
import leyline.game.state.LinkInfoChoiceKind
import leyline.game.state.ModifiedTypeForCrewKind
import leyline.game.state.MutateLayeredEffectKind
import leyline.game.state.PersistentAnnotationKind
import leyline.game.state.PersistentAnnotationStore
import leyline.game.state.PlottedDesignationKind
import leyline.game.state.PreparedDesignationKind
import leyline.game.state.QualificationKind
import leyline.game.state.RightUnlockedDesignationKind
import leyline.game.state.SaddledDesignationKind
import leyline.game.state.SaddledThisTurnKind
import leyline.game.state.TargetSpecKind
import leyline.game.state.TemporaryPermanentKind
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Orchestrates the GsmSnapshot → proto state-mapping pipeline.
 *
 * Two core methods:
 * - [buildFromSnapshot]: Full [GameStateMessage] from a captured [leyline.game.snapshot.GsmSnapshot].
 * - [buildDiff]: Diff GSM by snap-vs-snap field comparison; returns [leyline.game.state.BridgeMutations]
 *   for the caller to apply via [leyline.game.state.GameBridge.applyMutations]. Pure on
 *   ordering-sensitive outputs.
 *
 * Lifecycle GSM factories (deal-hand, mulligan, transitions) live in [leyline.game.bundle.GsmBuilder].
 * Interactive request builders (targeting, combat) live in [leyline.game.bundle.RequestBuilder].
 * Pure Forge→proto projection lives in the `mapper/` subpackage.
 *
 * ## Purity boundary
 *
 * Single contract: both [buildFromSnapshot] and [buildDiff] return
 * [leyline.game.state.BridgeMutations] as data; callers apply via [leyline.game.state.GameBridge.applyMutations].
 * No inline writes during compute, no mode flags. Ordering-sensitive writes
 * (id reallocations, limbo retires, zone recordings, persistent annotation
 * batch, nextAnnotationId, delayed-trigger holder lifecycle) flow exclusively
 * through the returned mutations.
 *
 * Inputs to [buildDiff] are pure values: `prev: GsmSnapshot?`, `cur: GsmSnapshot`,
 * `events: FrameEventLog`. Outputs are pure: `GameStateMessage` + [leyline.game.state.BridgeMutations].
 *
 * The acceptance forcing function for this boundary is [PureDiffReplayTest],
 * which replays recorded `(snap, events, diff)` tuples through [buildDiff] on
 * a fresh bridge and asserts byte-equal Diff GSMs across scenarios. A
 * regression there signals newly-introduced impurity.
 *
 * ## Residual in-stage bridge reads/writes (accepted, by design)
 *
 * These remain inside the pipeline for bounded reasons — not ordering-sensitive
 * for the replayed scenarios, or part of a deliberate boundary. This list is
 * a working catalog, not a completeness claim: the replay test is the real
 * contract, not the enumeration. Extend the test scenarios (targeted spells,
 * vehicles, reveals, steals) to grow the coverage before relying on the list.
 *
 * Reads of effectively-immutable / card-DB state:
 * - [leyline.game.state.GameBridge.getOrAllocInstanceId] for NEW fids (first-seen cards). Monotonic
 *   allocator; ordering-irrelevant for correctness.
 * - `bridge.cardRepository.findGrpIdByName` / `findByGrpId`. Read-only card DB.
 *
 * Reads of live Forge state (deliberate bridge boundary):
 * - `bridge.snapshotBoosts()` / `bridge.snapshotKeywords()` — capture layered-
 *   effect snapshots for diff computation. Read-only at call site.
 * - `bridge.promptBridge(seat).journal.activeReveal()` — prompt-journal read
 *   for active-reveal detection. Journal state is still bridge-attached.
 *
 * Reads-then-writes on bridge-attached tracker state (not yet lifted onto snap):
 * - `bridge.effects` (EffectTracker) — layered-effect lifecycle state.
 * - `bridge.revealProxies` — RevealedCard proxy tracker, tied to transactional
 *   reveal-choose effects that span bundles.
 * - `bridge.annotations.activeStealForgeCardIds()` / `addSteals` / `removeSteals` —
 *   steal lifecycle.
 * - `bridge.snapshotCrewState()` / `bridge.getOrAllocCrewEffectId()` /
 *   `bridge.releaseCrewEffects()` — vehicle crew lifecycle.
 * - `bridge.drainPendingTargetSpecs()` — pending targeted-spell spec drain;
 *   ordering-sensitive but currently not exercised by the replay test.
 *   Highest-priority candidate to either lift or cover.
 *
 * Incidental in-stage writes:
 * - `bridge.evictAbilityRegistry(...)` — cache invalidation for zone-changed
 *   and transformed cards. Side-effectful but idempotent; ordering-irrelevant.
 * - `bridge.ids.reserveNextInstanceId()` inside zone-transfer compute —
 *   reserves a counter slot without committing map writes. Monotonic, so
 *   replay on a fresh bridge starts from 1 and stays deterministic.
 *
 * Any NEW in-stage bridge touch should be justified in PR review — either
 * it joins the catalog with a scope rationale, the replay test is extended
 * to cover it, or it gets lifted onto snap.
 */
@Suppress("LargeClass") // pipeline orchestrator; stages already delegated to mapper/* and helper objects
object StateMapper {
    private val log = LoggerFactory.getLogger(StateMapper::class.java)
    private val disturbBackPlayerZoneIds =
        setOf(
            ZoneIds.P1_HAND,
            ZoneIds.P2_HAND,
            ZoneIds.P1_GRAVEYARD,
            ZoneIds.P2_GRAVEYARD,
        )

    /** Result of [buildFromSnapshot] / [buildDiff] — GSM plus metadata for message framing. */
    data class BuildResult(
        val gsm: GameStateMessage,
        /** Ordering-sensitive bridge mutations computed during the build. Caller applies via [leyline.game.state.GameBridge.applyMutations]. */
        val mutations: BridgeMutations = BridgeMutations.Companion.EMPTY,
    )

    /**
     * Build a Full [GameStateMessage] from an immutable [leyline.game.snapshot.GsmSnapshot].
     * Maps cards to client instanceIds via the bridge's card ID mapping.
     *
     * [viewingSeatId] controls hand visibility: opponent's hand cards get
     * objectInstanceIds (for card count) but no GameObjectInfo (renders face-down).
     * Use 0 to include all objects (internal snapshots for diffing).
     */
    @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
    fun buildFromSnapshot(
        snap: GsmSnapshot,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        prev: GsmSnapshot? = null,
        /**
         * Bundle events consumed by the annotation pipeline. Defaults to closing
         * the bridge frame via [GameBridge.closeBundleFrame] — previously this was
         * done inside this function. Callers in the bundle loop (BundleBuilder)
         * pass an explicit log so the frame closes once per bundle and the
         * mapper is pure on event inputs.
         */
        events: FrameEventLog = bridge.closeBundleFrame(viewingSeatId),
    ): BuildResult {
        val human = bridge.getPlayer(SeatId(1))
        val ai = bridge.getPlayer(SeatId(2))
        val frame = GsmFrame.Companion.from(snap)

        // ═══ GATHER: snapshot mutable state (events arrive from caller) ═══
        // applyRevealProxies may append RevealProxiesDeleted on reveal end; keep local mutable copy.
        // Snap delta drives PowerToughnessChanged + CardTransformed instead of a parallel
        // diff in GameEventCollector — see SnapDeltaSynthesizer for the gating rules.
        val eventsMutable = events.events.toMutableList()
        if (prev != null) {
            eventsMutable += SnapDeltaSynthesizer.synthesize(prev, snap)
        }
        // Evict stale AbilityRegistry entries when card traits may be exposed
        // differently on the new face or in the new zone.
        for (ev in eventsMutable) {
            if (ev is GameEvent.CardTransformed) bridge.evictAbilityRegistry(ev.cardId.value)
            if (ev is GameEvent.ZoneChanged) bridge.evictAbilityRegistry(ev.cardId.value)
        }
        val initEffectDiff = bridge.effects.emitInitEffectsOnce()
        val boostSnapshot = bridge.snapshotBoosts()
        val effectDiff = bridge.effects.diffBoosts(boostSnapshot)
        val keywordSnapshot = bridge.snapshotKeywords()
        val keywordDiff = bridge.effects.diffKeywords(keywordSnapshot)
        // Persistent annotation baseline is carried on the snapshot (captured
        // at snap time in SnapshotCapture). computeBatch is pure over this value.
        // See PersistentAnnotationStore class KDoc for lifecycle and ordering invariants.
        val persistentState = snap.persistentAnnotationState
        val persistSnapshot = persistentState.activeAnnotations
        val startPersistentId = persistentState.nextPersistentId
        val startAnnotationId = persistentState.nextAnnotationId

        // ═══ MAP: engine state → proto objects ═══
        val isBrawl = bridge.isBrawlOrCommander
        val gameVariant = if (isBrawl) GameVariant.Brawl else GameVariant.Normal

        val gameInfo =
            GameInfo
                .newBuilder()
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
                DeckConstraintInfo
                    .newBuilder()
                    .setMinDeckSize(58)
                    .setMaxDeckSize(59)
                    .setMaxSideboardSize(1)
                    .setMinCommanderSize(1)
                    .setMaxCommanderSize(1),
            )
            gameInfo.setFreeMulliganCount(1)
        }

        val player1 = PlayerMapper.buildFromSnapshot(snap, 1)
        val player2 = PlayerMapper.buildFromSnapshot(snap, 2)

        val team1 =
            TeamInfo
                .newBuilder()
                .setId(1)
                .addPlayerIds(1)
                .setStatus(TeamStatus.InGame_a458)
        val team2 =
            TeamInfo
                .newBuilder()
                .setId(2)
                .addPlayerIds(2)
                .setStatus(TeamStatus.InGame_a458)

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
        // TriggerHolder iids are spliced in after the holder-tracker diff runs
        // below — see the splice that wraps `transferResultWithHolders`. They
        // must reflect post-diff state (holder added → in zone, holder removed
        // → out of zone) so the deletion GSM's Limbo listing doesn't disagree
        // with `diffDeletedInstanceIds`.
        val limboZone =
            ZoneInfo
                .newBuilder()
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
                SeatId(1),
                snap,
                bridge,
                zones,
                gameObjects,
                ZoneIds.P1_HAND,
                ZoneIds.P1_LIBRARY,
                ZoneIds.P1_GRAVEYARD,
                ZoneIds.P1_SIDEBOARD,
                viewingSeatId,
                revealForSeat,
                revealHand = revealedHandSeat == 1,
            )
        }

        // Player 2 zones
        if (ai != null) {
            ZoneMapper.addPlayerZonesFromSnapshot(
                SeatId(2),
                snap,
                bridge,
                zones,
                gameObjects,
                ZoneIds.P2_HAND,
                ZoneIds.P2_LIBRARY,
                ZoneIds.P2_GRAVEYARD,
                ZoneIds.P2_SIDEBOARD,
                viewingSeatId,
                revealForSeat,
                revealHand = revealedHandSeat == 2,
            )
        }

        // Populate shared zones with cards.
        ZoneMapper.addSharedZoneCardsFromSnapshot(
            snap,
            ForgeZoneType.Battlefield,
            ZoneIds.BATTLEFIELD,
            bridge,
            zones,
            gameObjects,
            human,
            keywordSnapshot,
        )
        ZoneMapper.addSharedZoneCardsFromSnapshot(snap, ForgeZoneType.Stack, ZoneIds.STACK, bridge, zones, gameObjects, human)
        ZoneMapper.addSharedZoneCardsFromSnapshot(snap, ForgeZoneType.Merged, ZoneIds.SUPPRESSED, bridge, zones, gameObjects, human)
        ZoneMapper.addSharedZoneCardsFromSnapshot(snap, ForgeZoneType.Exile, ZoneIds.EXILE, bridge, zones, gameObjects, human)
        ZoneMapper.addSharedZoneCardsFromSnapshot(snap, ForgeZoneType.Command, ZoneIds.COMMAND, bridge, zones, gameObjects, human)

        // Stack abilities (triggers, activated abilities not represented as zone cards)
        ZoneMapper.addStackAbilitiesFromSnapshot(snap, bridge, zones, gameObjects)

        // RevealedCard proxy synthesis / cleanup (may append RevealProxiesDeleted to eventsMutable)
        applyRevealProxies(activeReveal, snap, bridge, zones, gameObjects, eventsMutable)

        log.debug(
            "buildFromSnapshot: phase={} turn={} hand={} objects={} zones={}",
            snap.phase.phase,
            snap.phase.turn,
            human?.getZone(ForgeZoneType.Hand)?.size() ?: 0,
            gameObjects.size,
            zones.size,
        )

        // ═══ COMPUTE: annotation pipeline (stages 1-5) ═══
        var transferResult = ZoneTransferDetector.detectZoneTransfers(gameObjects, zones, bridge, eventsMutable)
        recordParadigmSourceStackIids(transferResult, bridge)
        // Frame-scoped id resolver — uses the planned-realloc map so any consumer
        // asking "what iid will the client see for this card?" gets the
        // post-realloc answer even before applyMutations runs.
        val frameIds = FrameIdResolver(bridge, FrameIdResolver.postReallocIids(transferResult))
        transferResult = transferResult.withDecayedCleanupAffectors(eventsMutable, snap, bridge, frameIds)
        val actingSeat = snap.phase.priorityPlayer?.value ?: 2
        val (annotations, transferPersistent, combatResult) =
            computeAnnotations(
                eventsMutable,
                transferResult,
                actingSeat,
                bridge,
                prev = prev,
                snap = snap,
                frameIds = frameIds,
            )

        val decayedCleanupSourcesThisGsm = updateDecayedCleanupSources(eventsMutable, snap, bridge, transferResult)

        val persistentFeedResult =
            PersistentFeedBuilder.build(
                events = eventsMutable,
                snap = snap,
                prev = prev,
                bridge = bridge,
                frameIds = frameIds,
                decayedCleanupSourcesThisGsm = decayedCleanupSourcesThisGsm,
                transferResult = transferResult,
            )
        val holderBatch = bridge.delayedTriggerHolders.computeBatch(persistentFeedResult.currentHolders)
        val postDiffActiveIids =
            (bridge.delayedTriggerHolders.activeIids() + holderBatch.added.map { it.iid }) -
                holderBatch.removed.toSet()
        transferResult = transferResult.withDelayedTriggerHolders(holderBatch, postDiffActiveIids, bridge)
        val persistentFeeds = persistentFeedResult.feeds
        // Transient gain/lose Designation annotations — diff prev vs cur on the
        // `Source on battlefield with isPrepared` set. Gains insert before the
        // Stack→Battlefield Resolve ZoneTransfer for the same source iid to match
        // the protocol's bracket order (annotation 848 before 849 in the spec).
        // Loses append at the end (cast acceptance has no co-located ZT for the
        // source — the ZT is on the copy moving Exile→Stack). Skipped on full
        // snapshot rebuild (prev == null) — the persistent Designation pAnn alone
        // re-syncs client state on rebuild.
        if (prev != null) {
            insertStateDesignationTransients(annotations, prev, snap) { fid ->
                bridge.getOrAllocInstanceId(fid)
            }
            insertDayNightDesignationTransients(annotations, prev.dayTime, snap.dayTime)
        }

        // Stages 4-5 + persistent computation
        val battlefieldIids: Set<Int> = frameIds.battlefieldInstanceIds(snap)
        // Stack contents (cards) plus stack-resident Ability gameObjects — both
        // can be the affector of a TriggeringObject. The Ability instance ids
        // are synthesised against [FrameIdResolver.stackAbilityForgeId] and
        // don't appear in the snapshot's zone contents.
        val stackIids: Set<Int> = frameIds.stackInstanceIds(snap)
        val resolvingStackIids: Set<Int> =
            (
                transferResult.transfers
                    .filter { it.srcZoneId == ZoneIds.STACK }
                    .map { it.origId } +
                    eventsMutable
                        .filterIsInstance<GameEvent.SpellResolved>()
                        .filter { it.isTrigger || it.isAbility }
                        .map { stackAbilityIidFor(it.abilityForgeId, it.cardId, frameIds) }
            ).toSet()
        val controllerOf: Map<Int, SeatId> =
            snap.boundCards.values.associate { bound ->
                bridge.getOrAllocInstanceId(bound.forgeCardId).value to bound.snapshot.controller
            }
        val frameContext =
            FrameContext(
                phase = snap.phase.phase,
                activePlayerSeat = snap.phase.activePlayer,
                battlefieldIids = battlefieldIids,
                controllerOf = controllerOf,
                stackIids = stackIids,
                resolvingStackIids = resolvingStackIids,
            )
        val remaining =
            computeRemainingAnnotations(
                eventsMutable,
                annotations,
                transferPersistent,
                initEffectDiff,
                effectDiff,
                persistSnapshot,
                startPersistentId,
                startAnnotationId,
                bridge,
                snap,
                frameContext,
                frameIds,
                keywordDiff,
                combatResult,
                persistentFeeds,
                transferResult = transferResult,
            )

        // ═══ ASSEMBLE: build the GSM proto ═══
        val built =
            assembleGsm(
                gameStateId,
                gameInfo.build(),
                frame,
                transferResult,
                remaining,
                combatResult,
                team1.build(),
                team2.build(),
                player1,
                player2,
                updateType,
                actions,
                actingSeat,
                prev?.gameStateId,
            )

        // ═══ COLLECT mutations (always) ═══
        val mutations =
            BridgeMutations(
                idReallocations = transferResult.idReallocations,
                retiredIds = transferResult.retiredIds.map { InstanceId(it) },
                zoneRecordings = transferResult.zoneRecordings.map { (iid, zid) -> InstanceId(iid) to zid },
                persistentBatch = remaining.batch,
                nextAnnotationId = remaining.nextAnnotationId,
                holderBatch = holderBatch,
                diffDeletedInstanceIds = stackTransferDeletedIds(transferResult).map { InstanceId(it) },
            )

        return BuildResult(built, mutations)
    }

    private fun recordParadigmSourceStackIids(
        transferResult: TransferResult,
        bridge: GameBridge,
    ) {
        for (transfer in transferResult.transfers) {
            val forgeCardId = transfer.forgeCardId ?: continue
            val isParadigm =
                bridge.findCard(forgeCardId)?.hasKeyword("Paradigm") == true ||
                    bridge.cardRepository.findKeywordAbilityGrpId(transfer.grpId, KeywordAbilityIds.PARADIGM) != null
            if (!isParadigm) continue
            val isOriginalCast =
                transfer.category == TransferCategory.CastSpell &&
                    (transfer.srcZoneId == ZoneIds.P1_HAND || transfer.srcZoneId == ZoneIds.P2_HAND) &&
                    transfer.destZoneId == ZoneIds.STACK
            val isStackSelfExile =
                transfer.category == TransferCategory.Exile &&
                    transfer.srcZoneId == ZoneIds.STACK &&
                    transfer.destZoneId == ZoneIds.EXILE
            if (isOriginalCast) {
                bridge.paradigmSourceStackIids[forgeCardId] = transfer.newId
            } else if (isStackSelfExile) {
                bridge.paradigmSourceStackIids.putIfAbsent(forgeCardId, transfer.origId)
            }
        }
    }

    private fun stackTransferDeletedIds(transferResult: TransferResult): List<Int> =
        (
            transferResult.transfers
                .filter { it.srcZoneId == ZoneIds.STACK && it.destZoneId != ZoneIds.BATTLEFIELD }
                .filter { transfer ->
                    transfer.origId != transfer.newId ||
                        transferResult.patchedObjects.none {
                            it.instanceId == transfer.newId && it.zoneId == transfer.destZoneId
                        }
                }.map { it.origId }
        )

    /**
     * Build a Diff [GameStateMessage] by snap-vs-snap field comparison.
     *
     * Genuinely pure on ordering-sensitive outputs: reads persistent state from
     * [cur.persistentAnnotationState] (not [bridge.annotations]); returns
     * [BridgeMutations] for the caller to apply via [GameBridge.applyMutations].
     *
     * `prev == null` → returns the Full GSM built from `cur` (first bundle, post-handshake).
     * Otherwise emits only zones/objects whose CardSnapshot/ZoneSnapshot field-equality
     * differs between `prev` and `cur`. Player/turn/annotation/persistent-annotation
     * lists are taken from the freshly-built current full GSM (current-bundle events
     * already applied).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition", "LongParameterList")
    fun buildDiff(
        prev: GsmSnapshot?,
        cur: GsmSnapshot,
        events: FrameEventLog,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
    ): BuildResult {
        if (prev == null) {
            // First bundle — Full GSM with mutations returned for caller-apply.
            return buildFromSnapshot(
                cur,
                gameStateId,
                matchId,
                bridge,
                actions = actions,
                updateType = updateType,
                viewingSeatId = viewingSeatId,
                revealForSeat = revealForSeat,
                prev = null,
                events = events,
            )
        }

        // Library reveal forces a Full GSM. Library cards don't move zones or
        // change CardSnapshot fields when a search reveals them, so the diff
        // filter (snap-vs-snap object delta + zone-moved fids) discards them
        // and the picker can't render face-up. Sending Full re-emits every
        // game object including the library, with per-object visibility set
        // to Private + viewers=[searchingSeat] so only the searcher sees the
        // contents (matches the protocol shape Arena uses for cycling /
        // tutor searches).
        if (revealForSeat != null) {
            return buildFromSnapshot(
                cur,
                gameStateId,
                matchId,
                bridge,
                actions = actions,
                updateType = updateType,
                viewingSeatId = viewingSeatId,
                revealForSeat = revealForSeat,
                prev = prev,
                events = events,
            )
        }

        // Build current full GSM (viewingSeatId=0 to include all objects for accurate diff).
        val fullResult =
            buildFromSnapshot(
                cur,
                gameStateId,
                matchId,
                bridge,
                revealForSeat = revealForSeat,
                prev = prev,
                events = events,
            )
        val current = fullResult.gsm

        // Snap-vs-snap zone delta: any zone whose snapshot field-equality differs.
        val changedZoneIds =
            cur.zones.keys
                .asSequence()
                .filter { id -> prev.zones[id] != cur.zones[id] }
                .toSet()
        val opponentHandZoneId = ZoneMapper.opponentHandZone(viewingSeatId)
        val opponentSideboardZoneId = ZoneMapper.opponentSideboardZone(viewingSeatId)
        val hasActiveReveal = bridge.allSeatIds().any { bridge.promptBridge(SeatId(it)).journal.activeReveal() != null }
        // Protocol-only zones not tracked in GsmSnapshot must always be included when non-empty:
        //   - Limbo (id=30): grows monotonically; always send when it has content.
        //   - REVEALED_P1/P2 (id=18/19): synthesized by applyRevealProxies during active reveal.
        //   - Hand zone of revealed seat: visibility flipped to Public by buildFromSnapshot but
        //     ZoneSnapshot still records Private, so snap equality check misses the change.
        val opponentRevealedHandZoneId: Int? =
            when {
                hasActiveReveal -> {
                    val ownerSeat =
                        bridge
                            .allSeatIds()
                            .firstNotNullOfOrNull {
                                bridge
                                    .promptBridge(SeatId(it))
                                    .journal
                                    .activeReveal()
                                    ?.ownerSeatId
                                    ?.value
                            }
                    ownerSeat?.let { ZoneIds.handOf(it) }
                }
                else -> null
            }
        val changedZones =
            current.zonesList
                .filter { zone ->
                    zone.zoneId in changedZoneIds ||
                        (
                            zone.zoneId == ZoneIds.LIMBO &&
                                (
                                    zone.objectInstanceIdsCount > 0 ||
                                        fullResult.mutations.holderBatch.removed
                                            .isNotEmpty()
                                )
                        ) ||
                        (zone.zoneId == ZoneIds.REVEALED_P1 || zone.zoneId == ZoneIds.REVEALED_P2) ||
                        (opponentRevealedHandZoneId != null && zone.zoneId == opponentRevealedHandZoneId)
                }.map { zone ->
                    redactOpponentSideboardZone(zone, opponentSideboardZoneId)
                }

        // Snap-vs-snap object delta: any card whose CardSnapshot field-equality differs.
        // Plus opponent-hand filter + active-reveal exception preserved.
        val cardSnapshotChangedFids =
            cur.objects.keys
                .asSequence()
                .filter { fid -> prev.objects[fid] != cur.objects[fid] }
                .toSet()

        // Cards whose zone changed (CardSnapshot doesn't carry zoneId; ZoneSnapshot.contents does).
        val prevZoneOf: Map<ForgeCardId, Int> =
            prev.zones.values
                .flatMap { z -> z.contents.map { it to z.id } }
                .toMap()
        val curZoneOf: Map<ForgeCardId, Int> =
            cur.zones.values
                .flatMap { z -> z.contents.map { it to z.id } }
                .toMap()
        val zoneMovedFids =
            (prevZoneOf.keys + curZoneOf.keys)
                .asSequence()
                .filter { fid -> prevZoneOf[fid] != curZoneOf[fid] }
                .toSet()

        val prevDisturbBackSourceFids = projectedDisturbBackSourceFids(prev)
        val curDisturbBackSourceFids = projectedDisturbBackSourceFids(cur)
        val changedFids = cardSnapshotChangedFids + zoneMovedFids
        val changedDisturbBackIds =
            disturbBackInstanceIds(
                changedFids.filter { it in prevDisturbBackSourceFids || it in curDisturbBackSourceFids },
                bridge,
            )
        val changedInstanceIds =
            changedFids.map { bridge.getOrAllocInstanceId(it).value }.toSet() + changedDisturbBackIds
        // instanceIds tracked in the prev snapshot (to detect truly new objects like RevealedCard proxies)
        val prevInstanceIds =
            prev.objects.keys
                .map { bridge.getOrAllocInstanceId(it).value }
                .toSet() +
                disturbBackInstanceIds(prevDisturbBackSourceFids, bridge)
        val changedObjects =
            current.gameObjectsList.filter { obj ->
                // Always include new objects absent from prev (e.g. RevealedCard proxies synthesized mid-diff).
                if (obj.instanceId !in prevInstanceIds) {
                    // Still apply opponent-hand filter unless reveal is active
                    if (opponentHandZoneId != 0 && obj.zoneId == opponentHandZoneId) {
                        return@filter hasActiveReveal && (obj.type == GameObjectType.RevealedCard || obj.visibility == Visibility.Public)
                    }
                    if (opponentSideboardZoneId != 0 && obj.zoneId == opponentSideboardZoneId) return@filter false
                    return@filter true
                }
                if (obj.instanceId !in changedInstanceIds) {
                    // During active reveal, always include opponent hand cards (visibility changed outside CardSnapshot)
                    if (hasActiveReveal &&
                        opponentHandZoneId != 0 &&
                        obj.zoneId == opponentHandZoneId &&
                        (obj.type == GameObjectType.RevealedCard || obj.visibility == Visibility.Public)
                    ) {
                        return@filter true
                    }
                    return@filter false
                }
                if (opponentHandZoneId != 0 && obj.zoneId == opponentHandZoneId) {
                    if (hasActiveReveal && (obj.type == GameObjectType.RevealedCard || obj.visibility == Visibility.Public)) {
                        // fall through
                    } else {
                        return@filter false
                    }
                }
                if (opponentSideboardZoneId != 0 && obj.zoneId == opponentSideboardZoneId) return@filter false
                true
            }

        // Deleted IDs: in prev.objects but not in cur.objects, minus IDs still tracked
        // in cur zone listings (limbo-retired IDs that still appear in zone contents).
        val currentObjIds = current.gameObjectsList.map { it.instanceId }.toSet()
        val currentZoneTrackedIds = current.zonesList.flatMap { it.objectInstanceIdsList }.toSet()
        val deletedDisturbBackIds = disturbBackInstanceIds(prevDisturbBackSourceFids - curDisturbBackSourceFids, bridge)
        val deletedIds =
            ((prev.objects.keys - cur.objects.keys).map { bridge.getOrAllocInstanceId(it).value } + deletedDisturbBackIds)
                .filter { it !in currentObjIds && it !in currentZoneTrackedIds }

        val previousTurnInfo = GsmFrame.Companion.from(prev).turnInfo()
        val includeTurnInfo =
            current.turnInfo != previousTurnInfo ||
                current.annotationsList.any { ann ->
                    AnnotationType.PhaseOrStepModified in ann.typeList ||
                        AnnotationType.ResolutionStart in ann.typeList ||
                        AnnotationType.ResolutionComplete in ann.typeList
                }
        val previousPlayers = listOf(PlayerMapper.buildFromSnapshot(prev, 1), PlayerMapper.buildFromSnapshot(prev, 2))
        val playerPayloadNeeded =
            events.events.any { it is GameEvent.ManaAbilityActivated } ||
                current.annotationsList.any { ann ->
                    AnnotationType.ModifiedLife in ann.typeList || AnnotationType.LossOfGame_af5a in ann.typeList
                }
        val includePlayers =
            current.playersList != previousPlayers || playerPayloadNeeded

        val builder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gameStateId)
                .addAllZones(changedZones.sortedBy { it.zoneId })
                .addAllGameObjects(changedObjects)
                .addAllAnnotations(current.annotationsList)
                // Emit only newly-added persistent annotations: the client accumulates
                // across diffs and removes via diffDeletedPersistentAnnotationIds. IDs
                // already present before this bundle's computeBatch are carried on the
                // client; re-sending them is redundant wire traffic that diverges from
                // the protocol spec. Baseline is cur's captured state (taken before
                // computeBatch ran), not prev's — prev predates the last apply.
                .addAllPersistentAnnotations(
                    current.persistentAnnotationsList.filter { it.id !in cur.persistentAnnotationState.activeAnnotations.keys },
                )
                // Drain THIS frame's deletions directly from the just-computed batch.
                // Reading from a queue populated by the prior frame's applyMutations
                // would lag deletes by one frame; end-of-stream value transitions
                // could then orphan their delete entirely.
                .addAllDiffDeletedPersistentAnnotationIds(fullResult.mutations.persistentBatch.deletedIds)
                .addAllTimers(PlayerMapper.buildTimers())
                .setUpdate(updateType)
                .setPrevGameStateId(prev.gameStateId)

        if (includeTurnInfo) builder.setTurnInfo(current.turnInfo)
        if (includePlayers) builder.addAllPlayers(current.playersList)

        // Fold TriggerHolder gameObjects retired this GSM into the delete list.
        // The batch is compute-time data; applyMutations commits tracker state
        // only after this GSM is assembled.
        val holderDeletions = fullResult.mutations.holderBatch.removed
        val allDeletedIds = (deletedIds + holderDeletions + fullResult.mutations.diffDeletedInstanceIds.map { it.value }).distinct()
        if (allDeletedIds.isNotEmpty()) {
            builder.addAllDiffDeletedInstanceIds(allDeletedIds)
        }

        // Embed stripped actions + set pendingMessageCount when AAR follows
        if (actions != null) {
            builder.setPendingMessageCount(1)
            val activeSeat = current.turnInfo.priorityPlayer
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo
                        .newBuilder()
                        .setSeatId(activeSeat)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
        }

        val built = builder.build()
        if (built.gameStateId != 0 && built.gameStateId == built.prevGameStateId) {
            log.error(
                "SELF-REF gsId={} prev.gsId={} param={} caller={}",
                built.gameStateId,
                prev.gameStateId,
                gameStateId,
                Thread.currentThread().stackTrace[2].let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" },
            )
        }
        return BuildResult(built, fullResult.mutations)
    }

    private fun redactOpponentSideboardZone(
        zone: ZoneInfo,
        opponentSideboardZoneId: Int,
    ): ZoneInfo {
        if (opponentSideboardZoneId == 0 || zone.zoneId != opponentSideboardZoneId) return zone
        return zone.toBuilder().clearObjectInstanceIds().build()
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
    fun resolveUpdateType(
        snap: GsmSnapshot,
        viewingSeatId: Int,
    ): GameStateUpdate {
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
        prevGsId: Int?,
    ): GameStateMessage {
        val effectiveTurnInfo =
            if (combatResult.hasCombatDamage) {
                frame
                    .turnInfo()
                    .toBuilder()
                    .setPhase(Phase.Combat_a549)
                    .setStep(combatResult.damageStep)
            } else {
                frame.turnInfo().toBuilder()
            }

        val builder =
            GameStateMessage
                .newBuilder()
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
        if (prevGsId != null && prevGsId > 0) {
            builder.setPrevGameStateId(prevGsId)
        }

        if (actions != null) {
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo
                        .newBuilder()
                        .setSeatId(prioritySeat)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
        }
        return builder.build()
    }

    private fun projectedDisturbBackSourceFids(snap: GsmSnapshot): Set<ForgeCardId> {
        val playerZoneFids =
            disturbBackPlayerZoneIds
                .asSequence()
                .flatMap { zoneId ->
                    snap.zones[zoneId]
                        ?.contents
                        .orEmpty()
                        .asSequence()
                }

        return playerZoneFids
            .filter { fid ->
                val cardSnap = snap.objects[fid] ?: return@filter false
                cardSnap.othersideGrpId != 0 &&
                    snap.boundCards[fid]?.altCost(KeywordAbilityIds.DISTURB) != null
            }.toSet()
    }

    private fun disturbBackInstanceIds(
        sourceFids: Iterable<ForgeCardId>,
        bridge: GameBridge,
    ): Set<Int> =
        sourceFids
            .mapTo(mutableSetOf()) { fid ->
                bridge.getOrAllocInstanceId(FrameIdResolver.disturbBackForgeId(fid)).value
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
        snap: GsmSnapshot,
        frameContext: FrameContext,
        frameIds: FrameIdResolver,
        keywordDiff: EffectTracker.KeywordDiffResult = EffectTracker.KeywordDiffResult(emptyList(), emptyList()),
        combatResult: CombatAnnotationResult = CombatAnnotationResult(emptyList()),
        persistentFeeds: PersistentFeedSet = PersistentFeedSet(),
        transferResult: TransferResult,
    ): RemainingAnnotationsResult {
        val castSpellManaForgeIds =
            events
                .filterIsInstance<GameEvent.SpellCast>()
                .flatMap { it.manaPayments.map { mp -> mp.sourceCardId } }
                .toSet()
        val sacrificedManaForgeIds =
            events
                .filterIsInstance<GameEvent.ManaAbilityActivated>()
                .filter { ma -> events.any { it is GameEvent.CardSacrificed && it.cardId == ma.cardId } }
                .map { it.cardId }
                .toSet()
        val manaPaidForgeCardIds = castSpellManaForgeIds + sacrificedManaForgeIds
        val castStackIidsByCard =
            transferResult.transfers
                .asSequence()
                .filter { it.category == TransferCategory.CastSpell }
                .mapNotNull { transfer -> transfer.forgeCardId?.let { it to InstanceId(transfer.newId) } }
                .toMap()
        val castSpellTransferCardIds = castStackIidsByCard.keys
        val mechanicResult =
            MechanicAnnotations.mechanicAnnotations(
                events,
                manaPaidForgeCardIds,
                idResolver = { fid -> frameIds.cardIid(fid) },
                effectIdAllocator = { leyline.bridge.types.EffectId(bridge.effects.nextEffectId()) },
                activeStealForgeCardIds = bridge.annotations.activeStealForgeCardIds(),
                manaAbilityGrpIdResolver = { fid ->
                    val card = bridge.getGame()?.let { leyline.bridge.findCard(it, fid) }
                    val grpId =
                        if (card != null) {
                            val subtypes = card.type.subtypes.map { it.lowercase() }
                            leyline.game.data.BasicLandAbilities.BY_SUBTYPE
                                .firstOrNull { it.first in subtypes }
                                ?.second ?: 0
                        } else {
                            0
                        }
                    leyline.bridge.types.GrpId(grpId)
                },
                counterAffectorResolver = { eventIndex, ev -> counterAffectorFor(eventIndex, ev, events, frameIds, snap, bridge) },
                playerCounterAffectorResolver = { eventIndex, ev -> playerCounterAffectorFor(eventIndex, ev, events, frameIds) },
                stackInstanceResolver = { ev -> castStackIidsByCard[ev.cardId] },
                castSpellTransferCardIds = castSpellTransferCardIds,
            )
        // Token entries belong before combat damage: a Mobilize trigger that
        // resolves between attacker declaration and combat damage produces tokens
        // that themselves attack and deal damage. The client identity map needs
        // them in place before processing the DamageDealt entries that reference
        // their iids — otherwise the tokens visually pop in after first damage
        // animates. Other mechanic annotations (counters, scry, surveil, …) keep
        // their post-combat slot since they typically come from damage-triggered
        // effects.
        val (tokenCreatedAnns, otherMechanic) =
            mechanicResult.transient.partition { ann ->
                AnnotationType.TokenCreated in ann.typeList
            }
        if (tokenCreatedAnns.isNotEmpty()) {
            val firstDamageIdx =
                annotations.indexOfFirst { ann ->
                    AnnotationType.DamageDealt_af5a in ann.typeList
                }
            if (firstDamageIdx >= 0) {
                annotations.addAll(firstDamageIdx, tokenCreatedAnns)
            } else {
                annotations.addAll(tokenCreatedAnns)
            }
        }
        annotations.addAll(otherMechanic)
        annotations.addAll(buildStaticChoiceResultAnnotations(bridge, frameIds))

        // AbilityWordActive: consumed from pre-computed snap entries
        val abilityWordPersistent = persistentFeeds.abilityWord

        if (initEffectDiff.created.isNotEmpty()) {
            val (initTransient, _) = MechanicAnnotations.effectAnnotations(initEffectDiff)
            annotations.addAll(initTransient)
        }

        val sourceAbilityResolver = SourceAbilityResolverFactory.build(bridge)
        val (effectTransient, effectPersistent) =
            MechanicAnnotations.effectAnnotations(
                diff = effectDiff,
                sourceAbilityResolver = sourceAbilityResolver,
                keywordDiff = keywordDiff,
                keywordAffectorResolver = { _, _, _ ->
                    // Best-effort: use most recent SpellResolved event's instanceId as affector.
                    // Full resolver (tracing spell → keyword grant) comes later.
                    events
                        .filterIsInstance<GameEvent.SpellResolved>()
                        .lastOrNull()
                        ?.let { bridge.getOrAllocInstanceId(it.cardId) }
                        ?: leyline.bridge.types.InstanceId(0)
                },
                boostAffectorResolver = { effect, sourceAbilityGrpId ->
                    if (sourceAbilityGrpId?.value == KeywordAbilityIds.ENLIST) {
                        events
                            .filterIsInstance<GameEvent.SpellResolved>()
                            .lastOrNull { resolved ->
                                resolved.isTrigger &&
                                    resolved.abilityGrpId == KeywordAbilityIds.ENLIST &&
                                    frameIds.cardIid(resolved.cardId).value == effect.cardInstanceId
                            }?.let { resolved ->
                                InstanceId(stackAbilityIidFor(resolved.abilityForgeId, resolved.cardId, frameIds))
                            }
                    } else {
                        null
                    }
                },
                uniqueAbilityIdAllocator = { bridge.effects.nextEffectId() },
            )
        annotations.addAll(effectTransient)

        // Qualification pAnn for adventure-exiled cards (cast-from-exile eligibility marker)
        val qualificationPersistent = persistentFeeds.qualification

        // TemporaryPermanent pAnn for any token with EOT-sacrifice (copy or otherwise)
        val temporaryPermanentPersistent = persistentFeeds.temporaryPermanent

        // DelayedTriggerAffectees groups EOT-sacrifice tokens that share a
        // delayed trigger (Mobilize, EOT-sacrifice copies). One annotation per
        // group, persistent until the trigger resolves.
        val delayedTriggerAffecteesPersistent = persistentFeeds.delayedTriggerAffectees

        // TargetSpec pAnn for each targeted spell/ability on the stack
        val targetSpecPersistent = buildTargetSpecAnnotations(bridge, frameIds, snap)
        val (mutateMergeTransient, mutateMergePersistent) = buildMutateMergeAnnotations(snap, bridge, frameIds)
        annotations.addAll(mutateMergeTransient)

        val (crewedThisTurnPersistent, crewTypeChangePersistent, crewExpiredAnnotations) =
            computeCrewAnnotations(bridge)
        val saddledThisTurnPersistent = computeSaddleAnnotations(bridge)
        annotations.addAll(crewExpiredAnnotations)

        val enrichedMechanicResult =
            mechanicResult.copy(
                perKindPersistent =
                    buildMap<PersistentAnnotationKind, List<AnnotationInfo>> {
                        put(AbilityWordActiveKind, abilityWordPersistent)
                        put(
                            QualificationKind,
                            qualificationPersistent +
                                mechanicResult.perKindPersistent[QualificationKind].orEmpty(),
                        )
                        put(CrewedThisTurnKind, crewedThisTurnPersistent)
                        put(SaddledThisTurnKind, saddledThisTurnPersistent)
                        put(ModifiedTypeForCrewKind, crewTypeChangePersistent)
                        put(TemporaryPermanentKind, temporaryPermanentPersistent)
                        put(DelayedTriggerAffecteesKind, delayedTriggerAffecteesPersistent)
                        put(TargetSpecKind, targetSpecPersistent)
                        put(MutateLayeredEffectKind, mutateMergePersistent)
                        put(PreparedDesignationKind, persistentFeeds.preparedDesignation)
                        put(PlottedDesignationKind, persistentFeeds.plottedDesignation)
                        put(CommanderDesignationKind, persistentFeeds.commanderDesignation)
                        put(SaddledDesignationKind, persistentFeeds.saddledDesignation)
                        put(LeftUnlockedDesignationKind, persistentFeeds.leftUnlockedDesignation)
                        put(RightUnlockedDesignationKind, persistentFeeds.rightUnlockedDesignation)
                        put(DayNightDesignationKind, persistentFeeds.dayNightDesignation)
                        put(FaceDownDisguiseKind, persistentFeeds.faceDownDisguise)
                        put(ColorProductionKind, persistentFeeds.colorProduction)
                        put(LinkInfoChoiceKind, persistentFeeds.linkInfo)
                    },
            )
        val batch =
            PersistentAnnotationStore.Companion.computeBatch(
                currentActive = persistSnapshot,
                startPersistentId = startPersistentId,
                frame = frameContext,
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

    private fun buildStaticChoiceResultAnnotations(
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        bridge.allSeatIds().sorted().flatMap { seatValue ->
            bridge
                .promptBridge(SeatId(seatValue))
                .journal
                .drainStaticChoiceResults()
                .map { result ->
                    AnnotationBuilder.choiceResult(
                        sourceInstanceId = frameIds.cardIid(result.sourceForgeCardId),
                        chooserSeatId = result.chooserSeatId,
                        choiceValue = result.choiceValue,
                        choiceDomain = result.choiceDomain,
                    )
                }
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
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    internal fun assembleTransferAndCombatAnnotations(
        events: List<GameEvent>,
        transferResult: TransferResult,
        actingSeat: Int,
        combatResult: CombatAnnotationResult,
        bridge: GameBridge? = null,
        snap: GsmSnapshot? = null,
        frameIds: FrameIdResolver? = null,
    ): Pair<MutableList<AnnotationInfo>, MutableList<AnnotationInfo>> {
        val annotations = mutableListOf<AnnotationInfo>()
        val transferPersistent = mutableListOf<AnnotationInfo>()
        val lethalDamageVictims =
            events
                .filterIsInstance<GameEvent.DamageDealtToCard>()
                .map { it.targetCardId }
                .toSet()
        // Activated-ability affector map: forgeCardId → queue of AB iids. Stamps
        // affectorId on cost-paid transfers (cycling discard, channel discard,
        // unearth GY→BF return) so the resulting annotation ties the transfer
        // back to the ability instance that caused it.
        //
        // Restricted to categories that are plausibly the "ability put X here"
        // outcome — Discard (cycling/channel cost), Resolve (unearth GY→BF) —
        // so an unrelated transfer for the same card later in the same frame
        // (e.g., a bounce trigger BF→Hand) doesn't get the AB iid stamped.
        //
        // Backed by a mutable per-card queue so chained activations on the
        // same card in one frame burn distinct AB iids in order; the same
        // forgeCardId stamping the same iid twice would conflate transfers.
        val activatedAbilityAffectors: MutableMap<ForgeCardId, ArrayDeque<Int>> =
            if (frameIds != null) {
                events
                    .filterIsInstance<GameEvent.SpellCast>()
                    .filter { it.isAbility && !it.isTrigger }
                    .map { it.cardId to stackAbilityIidFor(it.abilityForgeId, it.cardId, frameIds) }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, iids) -> ArrayDeque(iids) }
                    .toMutableMap()
            } else {
                mutableMapOf()
            }
        val patchedTransfers =
            if (activatedAbilityAffectors.isEmpty()) {
                transferResult.transfers
            } else {
                transferResult.transfers.map { transfer ->
                    val canCarryAffector =
                        transfer.affectorId == 0 &&
                            (
                                transfer.category == TransferCategory.Discard ||
                                    transfer.category == TransferCategory.Resolve
                            )
                    if (!canCarryAffector) return@map transfer
                    val abIid = transfer.forgeCardId?.let { activatedAbilityAffectors[it]?.removeFirstOrNull() }
                    if (abIid != null) transfer.copy(affectorId = abIid) else transfer
                }
            }
        val (deferredTransfers, immediateTransfers) =
            patchedTransfers.partition { transfer ->
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
        // Snapshot-derived appearances (cast spells visible on the stack at snapshot time).
        val snapshotSourceIids = transferResult.stackAbilityAppearances.map { it.sourceCardInstanceId }.toSet()
        val snapshotAppearanceIids = transferResult.stackAbilityAppearances.map { it.abilityInstanceId }.toSet()
        val abilityLineage = bridge?.abilityLineage
        val eventAbilityGrpIdsByIid =
            if (bridge != null && snap != null && frameIds != null) {
                events
                    .filterIsInstance<GameEvent.SpellCast>()
                    .associate { cast ->
                        val abilityIid = stackAbilityIidFor(cast.abilityForgeId, cast.cardId, frameIds)
                        val grpId =
                            cast.abilityGrpId.takeIf { it != 0 }
                                ?: abilityGrpIdForSource(cast.cardId, cast.abilityForgeId, bridge, snap)
                        abilityIid to grpId
                    }
            } else {
                emptyMap()
            }
        for (a in transferResult.stackAbilityAppearances) {
            // Snapshot-derived sourceZoneId reads `previousZones[sourceCardIid]`
            // which is 0 when the source card wasn't tracked through the diff
            // (puzzle-injected starting state). For activated abilities the
            // SpellCast event carries an explicit activationZoneId — prefer it
            // when non-zero so cycling=Hand and unearth=Graveyard both land.
            val sourceZone = if (a.activationZoneId != 0) a.activationZoneId else a.sourceZoneId
            val abilityGrpId = eventAbilityGrpIdsByIid[a.abilityInstanceId] ?: a.grpId
            abilityLineage?.record(
                AbilityWireIdentity(
                    abilityIid = a.abilityInstanceId,
                    sourceIidAtCreate = a.sourceCardInstanceId,
                    sourceZoneAtCreate = sourceZone,
                    abilityGrpId = abilityGrpId,
                ),
            )
            annotations.add(
                AnnotationBuilder.abilityInstanceCreated(
                    InstanceId(a.abilityInstanceId),
                    InstanceId(a.sourceCardInstanceId),
                    sourceZone,
                ),
            )
            // TriggeringObject is trigger-only — activated abilities (cycling,
            // channel, unearth, …) do not carry one in the protocol shape.
            if (!a.isActivatedAbility) {
                transferPersistent.add(
                    AnnotationBuilder.triggeringObject(
                        abilityInstanceId = InstanceId(a.abilityInstanceId),
                        sourceCardInstanceId = InstanceId(a.triggeringObjectInstanceId ?: a.sourceCardInstanceId),
                        sourceZone = sourceZone,
                    ),
                )
            }
        }
        val snapshotDisappearanceIids = transferResult.stackAbilityDisappearances.map { it.abilityInstanceId }.toSet()
        // Event-driven trigger lifecycle. With auto-pass on the local turn the
        // trigger can fire and resolve between two snapshots so the snap-diff
        // misses both halves; with the per-trigger GSM split (GamePlayback)
        // each half lands in its own GSM, so cast and resolve events arrive in
        // separate drains. We emit the cast-side annotations from cast events
        // and the resolve-side from resolve events independently — guarding
        // against double-emission when the snap-diff also caught the
        // appearance/disappearance.
        if (bridge != null && snap != null) {
            emitTriggerLifecycleAnnotations(
                events = events,
                snapshotSourceIids = snapshotSourceIids,
                snapshotAppearanceIids = snapshotAppearanceIids,
                snapshotDisappearanceIids = snapshotDisappearanceIids,
                annotations = annotations,
                transferPersistent = transferPersistent,
                bridge = bridge,
                snap = snap,
                frameIds = frameIds ?: FrameIdResolver(bridge),
            )
        }
        for (d in transferResult.stackAbilityDisappearances) {
            val lineage = abilityLineage?.consume(d.abilityInstanceId)
            val sourceCardInstanceId = lineage?.sourceIidAtCreate ?: d.sourceCardInstanceId
            annotations.add(
                AnnotationBuilder.abilityInstanceDeleted(
                    InstanceId(d.abilityInstanceId),
                    InstanceId(sourceCardInstanceId),
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
     * Emit AbilityInstanceCreated / TriggeringObject / ResolutionStart-Complete /
     * AbilityInstanceDeleted for triggered abilities that surfaced via the event
     * stream but were missed by snapshot-diff (auto-resolved between snapshots).
     *
     * The stack ability instanceId comes from
     * [FrameIdResolver.triggerStackAbilityIid] keyed on the event's
     * `abilityForgeId` so back-to-back triggers from one source card mint
     * distinct iids; falls back to source-card-keyed surrogate when the
     * collector didn't surface the SA id (legacy paths, defensive 0).
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun emitTriggerLifecycleAnnotations(
        events: List<GameEvent>,
        snapshotSourceIids: Set<Int>,
        snapshotAppearanceIids: Set<Int>,
        snapshotDisappearanceIids: Set<Int>,
        annotations: MutableList<AnnotationInfo>,
        transferPersistent: MutableList<AnnotationInfo>,
        bridge: GameBridge,
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ) {
        val enlistedIidsByAttacker =
            events
                .filterIsInstance<GameEvent.CardTapped>()
                .mapNotNull { tap -> tap.affectorCardId?.let { attacker -> attacker to frameIds.cardIid(tap.cardId).value } }
                .toMap()

        // Cast half: AbilityInstanceCreated (when snap-diff missed it) + persistent TriggeringObject.
        for (cast in events.filterIsInstance<GameEvent.SpellCast>().filter { it.isTrigger }) {
            val isParadigmTrigger = cast.isParadigmDelayedTrigger()
            val sourceCardIid =
                if (isParadigmTrigger) {
                    bridge.paradigmSourceStackIidFor(cast.cardId) ?: frameIds.cardIid(cast.cardId).value
                } else {
                    frameIds.cardIid(cast.cardId).value
                }
            val abilityIid = stackAbilityIidFor(cast.abilityForgeId, cast.cardId, frameIds)
            val enlistTriggeringObjectIid =
                if (cast.abilityGrpId == KeywordAbilityIds.ENLIST) enlistedIidsByAttacker[cast.cardId] else null
            val triggeringObjectIid: Int =
                cast.triggeringObjectInstanceId?.value
                    ?: enlistTriggeringObjectIid
                    ?: cast.triggeringObjectCardId?.let { frameIds.cardIid(it).value }
                    ?: sourceCardIid
            val sourceZone =
                if (isParadigmTrigger) {
                    ZoneIds.STACK
                } else {
                    cast.activationZoneId.takeIf { it != 0 } ?: currentSourceZoneId(cast.cardId, bridge)
                }

            if (abilityIid in snapshotAppearanceIids || sourceCardIid in snapshotSourceIids) continue
            bridge.abilityLineage.record(
                AbilityWireIdentity(
                    abilityIid = abilityIid,
                    sourceIidAtCreate = sourceCardIid,
                    sourceZoneAtCreate = sourceZone,
                    abilityGrpId =
                        cast.abilityGrpId.takeIf { it != 0 }
                            ?: abilityGrpIdForSource(cast.cardId, cast.abilityForgeId, bridge, snap),
                ),
            )
            annotations.add(
                AnnotationBuilder.abilityInstanceCreated(
                    InstanceId(abilityIid),
                    InstanceId(sourceCardIid),
                    sourceZone,
                ),
            )
            transferPersistent.add(
                AnnotationBuilder.triggeringObject(
                    abilityInstanceId = InstanceId(abilityIid),
                    sourceCardInstanceId = InstanceId(triggeringObjectIid),
                    sourceZone = sourceZone,
                ),
            )
        }

        // Activated-ability cast half: AbilityInstanceCreated keyed off the
        // event's activationZoneId (cycling=Hand, unearth=Graveyard, …).
        // No persistent TriggeringObject — that annotation is specific to
        // triggered abilities.
        for (cast in events.filterIsInstance<GameEvent.SpellCast>().filter { it.isAbility && !it.isTrigger }) {
            val sourceCardIid = frameIds.cardIid(cast.cardId).value
            val abilityIid = stackAbilityIidFor(cast.abilityForgeId, cast.cardId, frameIds)
            val sourceZone =
                if (cast.activationZoneId != 0) cast.activationZoneId else currentSourceZoneId(cast.cardId, bridge)

            if (abilityIid in snapshotAppearanceIids || sourceCardIid in snapshotSourceIids) continue
            bridge.abilityLineage.record(
                AbilityWireIdentity(
                    abilityIid = abilityIid,
                    sourceIidAtCreate = sourceCardIid,
                    sourceZoneAtCreate = sourceZone,
                    abilityGrpId =
                        cast.abilityGrpId.takeIf { it != 0 }
                            ?: abilityGrpIdForSource(cast.cardId, cast.abilityForgeId, bridge, snap),
                ),
            )
            annotations.add(
                AnnotationBuilder.abilityInstanceCreated(
                    InstanceId(abilityIid),
                    InstanceId(sourceCardIid),
                    sourceZone,
                ),
            )
        }

        // Resolve half: ResolutionStart/Complete (always — snap-diff doesn't emit
        // these for stack-only abilities) + AbilityInstanceDeleted (when snap-diff
        // missed it). Same shape applies for triggered and activated abilities.
        for (resolved in events.filterIsInstance<GameEvent.SpellResolved>().filter { it.isTrigger || it.isAbility }) {
            val sourceCardIid =
                if (resolved.isParadigmDelayedTrigger()) {
                    bridge.paradigmSourceStackIidFor(resolved.cardId) ?: frameIds.cardIid(resolved.cardId).value
                } else {
                    frameIds.cardIid(resolved.cardId).value
                }
            val abilityIid = stackAbilityIidFor(resolved.abilityForgeId, resolved.cardId, frameIds)
            val lineage =
                if (abilityIid in snapshotDisappearanceIids) {
                    bridge.abilityLineage.find(abilityIid)
                } else {
                    bridge.abilityLineage.consume(abilityIid)
                }
            val aidSourceIid = lineage?.sourceIidAtCreate ?: sourceCardIid
            val abilityGrpId =
                lineage?.abilityGrpId?.takeIf { it != 0 }
                    ?: resolved.abilityGrpId.takeIf { it != 0 }
                    ?: abilityGrpIdForSource(resolved.cardId, resolved.abilityForgeId, bridge, snap)

            annotations.add(AnnotationBuilder.resolutionStart(InstanceId(abilityIid), GrpId(abilityGrpId)))
            annotations.add(AnnotationBuilder.resolutionComplete(InstanceId(abilityIid), GrpId(abilityGrpId)))
            if (abilityIid !in snapshotDisappearanceIids) {
                annotations.add(
                    AnnotationBuilder.abilityInstanceDeleted(
                        InstanceId(abilityIid),
                        InstanceId(aidSourceIid),
                    ),
                )
            }
        }
    }

    @Suppress("UnusedPrivateMember")
    private fun GameEvent.SpellCast.isParadigmDelayedTrigger(): Boolean =
        isTrigger && abilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER

    @Suppress("UnusedPrivateMember")
    private fun GameEvent.SpellResolved.isParadigmDelayedTrigger(): Boolean =
        isTrigger && abilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER

    /**
     * SA-id-keyed surrogate iid for a stack-resident trigger or activated
     * ability, with source-card fallback when the collector didn't surface
     * the SA id (defensive 0). Both lifecycle paths share this minter so a
     * single AB iid threads through Created → ZoneTransfer affector → Deleted.
     */
    private fun stackAbilityIidFor(
        forgeAbilityId: Int,
        sourceForgeId: ForgeCardId,
        frameIds: FrameIdResolver,
    ): Int =
        if (forgeAbilityId != 0) {
            frameIds.triggerStackAbilityIid(forgeAbilityId).value
        } else {
            frameIds.stackAbilityIid(sourceForgeId).value
        }

    /** Best-effort source-zone lookup for an event-derived trigger. Falls back
     *  to Battlefield (28) — the dominant case for combat / state-change triggers.
     *  ZoneType has many rarely-used values (Sideboard, Ante, Subgame…) that
     *  don't host triggering objects we'd surface to the wire; mapping each
     *  is noise. The else-branch keeps the fallback explicit. */
    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun currentSourceZoneId(
        cardId: ForgeCardId,
        bridge: GameBridge,
    ): Int {
        val card = bridge.findCard(cardId) ?: return ZoneIds.BATTLEFIELD
        val ownerSeat = ownerSeatOf(card, bridge)
        return when (card.zone?.zoneType) {
            ForgeZoneType.Battlefield -> ZoneIds.BATTLEFIELD
            ForgeZoneType.Stack -> ZoneIds.STACK
            ForgeZoneType.Graveyard -> ZoneIds.graveyardOf(ownerSeat)
            ForgeZoneType.Exile -> ZoneIds.EXILE
            ForgeZoneType.Hand -> ZoneIds.handOf(ownerSeat)
            ForgeZoneType.Library -> ZoneIds.libraryOf(ownerSeat)
            ForgeZoneType.Command -> ZoneIds.COMMAND
            else -> ZoneIds.BATTLEFIELD
        }
    }

    /** Look up the outbound ability grpId for a triggered source. For known
     *  keyword triggers (Mobilize, …) this resolves to the per-card keyword
     *  ability grpId — e.g. 188698 for a Mobilize 1 source — so
     *  `ResolutionStart`/`Complete` carry the keyword row id rather than the
     *  source card's grpId. Falls back to the source card's grpId for triggers
     *  whose keyword isn't in [leyline.game.data.KeywordAbilityIds] yet. */
    private fun abilityGrpIdForSource(
        cardId: ForgeCardId,
        abilityForgeId: Int,
        bridge: GameBridge,
        snap: GsmSnapshot,
    ): Int {
        val bound = snap.boundCards[cardId] ?: return 0
        if (abilityForgeId != 0) {
            val card = bridge.findCard(cardId)
            val registry = if (card != null) bridge.abilityRegistryFor(card, bound.data) else null
            registry?.forSpellAbility(abilityForgeId)?.takeIf { it != 0 }?.let { return it }
            registry?.forTrigger(abilityForgeId)?.takeIf { it != 0 }?.let { return it }
        }
        for (keywordId in keywordTriggerIds) {
            bridge.cardRepository.findKeywordAbilityGrpId(bound.snapshot.grpId, keywordId)?.let { return it }
            bound.altCost(keywordId)?.abilityGrpId?.let { return it }
            bridge.cardRepository.findKeywordAbilityGrpId(bound.snapshot.grpId, keywordId)?.let { return it }
        }
        return bound.snapshot.grpId
    }

    private fun TransferResult.withDecayedCleanupAffectors(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): TransferResult {
        val cleanupAbilityIids =
            events
                .filterIsInstance<GameEvent.SpellResolved>()
                .filter { it.isTrigger && it.abilityGrpId != 0 }
                .mapNotNull { ev ->
                    val cleanupGrpId =
                        PersistentFeedBuilder.decayedCleanupGrpIdForSource(ev.cardId, snap, bridge, this) ?: return@mapNotNull null
                    if (ev.abilityGrpId != cleanupGrpId) return@mapNotNull null
                    ev.cardId to stackAbilityIidFor(ev.abilityForgeId, ev.cardId, frameIds)
                }.toMap()
        if (cleanupAbilityIids.isEmpty()) return this
        val patchedTransfers =
            transfers.map { transfer ->
                val affector = transfer.forgeCardId?.let { cleanupAbilityIids[it] } ?: return@map transfer
                if (transfer.affectorId != 0) return@map transfer
                val category =
                    if (transfer.srcZoneId == ZoneIds.BATTLEFIELD && transfer.destZoneId in graveyardZoneIds) {
                        TransferCategory.Sacrifice
                    } else {
                        transfer.category
                    }
                transfer.copy(category = category, affectorId = affector)
            }
        return copy(transfers = patchedTransfers)
    }

    private fun TransferResult.withDelayedTriggerHolders(
        holderBatch: HolderBatch,
        postDiffActiveIids: Set<Int>,
        bridge: GameBridge,
    ): TransferResult {
        if (holderBatch.added.isEmpty() && holderBatch.removed.isEmpty() && postDiffActiveIids.isEmpty()) return this

        val patchedZones = this.patchedZones.toMutableList()
        val patchedObjects = this.patchedObjects.toMutableList()
        val existingLimbo = patchedZones.find { it.zoneId == ZoneIds.LIMBO }
        val limboBuilder =
            existingLimbo?.toBuilder() ?: ZoneInfo.newBuilder().setZoneId(ZoneIds.LIMBO).setType(ZoneType.Limbo)
        if (existingLimbo != null) patchedZones.removeIf { it.zoneId == ZoneIds.LIMBO }

        val limboIids = limboBuilder.objectInstanceIdsList.toMutableSet()
        limboIids.removeAll(holderBatch.removed.toSet())
        limboIids.addAll(postDiffActiveIids)
        limboBuilder.clearObjectInstanceIds()
        for (iid in limboIids) limboBuilder.addObjectInstanceIds(iid)

        for (holder in holderBatch.added) {
            patchedObjects.add(
                ObjectMapper.buildTriggerHolderObject(
                    instanceId = holder.iid,
                    ownerSeatId = holder.ownerSeat,
                    objectSourceGrpId = holder.objectSourceGrpId,
                    parentInstanceId = holder.parentIid,
                    uniqueAbilityGrpId = holder.cleanupGrpId,
                    uniqueAbilityId = bridge.effects.nextEffectId(),
                ),
            )
        }
        patchedZones.add(limboBuilder.build())
        return copy(patchedZones = patchedZones, patchedObjects = patchedObjects)
    }

    private val graveyardZoneIds = setOf(ZoneIds.P1_GRAVEYARD, ZoneIds.P2_GRAVEYARD)

    private fun updateDecayedCleanupSources(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        bridge: GameBridge,
        transferResult: TransferResult,
    ): Set<ForgeCardId> {
        val visibleThisGsm = bridge.activeDecayedCleanupSources().toMutableSet()
        val addedThisGsm = linkedSetOf<ForgeCardId>()
        for (ev in events) {
            if (ev is GameEvent.SpellResolved) {
                val cleanupGrpId = PersistentFeedBuilder.decayedCleanupGrpIdForSource(ev.cardId, snap, bridge, transferResult)
                val abilityGrpId =
                    ev.abilityGrpId.takeIf { it != 0 }
                        ?: abilityGrpIdForSource(ev.cardId, ev.abilityForgeId, bridge, snap)
                if (ev.isTrigger && cleanupGrpId != null && abilityGrpId == KeywordAbilityIds.DECAYED) {
                    bridge.recordDecayedCleanupSource(ev.cardId)
                    visibleThisGsm.add(ev.cardId)
                    addedThisGsm.add(ev.cardId)
                }
            } else if (ev is GameEvent.SpellCast) {
                val cleanupGrpId = PersistentFeedBuilder.decayedCleanupGrpIdForSource(ev.cardId, snap, bridge, transferResult)
                val abilityGrpId =
                    ev.abilityGrpId.takeIf { it != 0 }
                        ?: abilityGrpIdForSource(ev.cardId, ev.abilityForgeId, bridge, snap)
                if (ev.isTrigger && cleanupGrpId != null && abilityGrpId == cleanupGrpId) {
                    bridge.clearDecayedCleanupSource(ev.cardId)
                    if (ev.cardId !in addedThisGsm) visibleThisGsm.remove(ev.cardId)
                }
            } else if (ev is GameEvent.CardSacrificed) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, bridge)
            } else if (ev is GameEvent.CardDestroyed) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, bridge)
            } else if (ev is GameEvent.CardBounced) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, bridge)
            } else if (ev is GameEvent.CardExiled) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, bridge)
            } else if (ev is GameEvent.ZoneChanged) {
                if (ev.from == leyline.game.event.Zone.Battlefield && ev.to != leyline.game.event.Zone.Battlefield) {
                    clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, bridge)
                }
            }
        }
        return visibleThisGsm
    }

    private fun clearDecayedCleanupSource(
        sourceForgeId: ForgeCardId,
        addedThisGsm: Set<ForgeCardId>,
        visibleThisGsm: MutableSet<ForgeCardId>,
        bridge: GameBridge,
    ) {
        bridge.clearDecayedCleanupSource(sourceForgeId)
        if (sourceForgeId !in addedThisGsm) visibleThisGsm.remove(sourceForgeId)
    }

    private fun counterAffectorFor(
        eventIndex: Int,
        ev: GameEvent.CountersChanged,
        events: List<GameEvent>,
        frameIds: FrameIdResolver,
        snap: GsmSnapshot,
        bridge: GameBridge,
    ): InstanceId? {
        if (ev.affectorAbilityForgeId != 0 && ev.affectorCardId != null) {
            return InstanceId(stackAbilityIidFor(ev.affectorAbilityForgeId, ev.affectorCardId, frameIds))
        }
        val resolved =
            keywordCounterResolutionForEvent(eventIndex, ev, events) { resolved ->
                isCounterAffectingKeywordResolution(resolved, snap, bridge)
            } ?: return null
        return InstanceId(stackAbilityIidFor(resolved.abilityForgeId, resolved.cardId, frameIds))
    }

    internal fun keywordCounterResolutionForEvent(
        eventIndex: Int,
        ev: GameEvent.CountersChanged,
        events: List<GameEvent>,
        isCounterAffectingResolution: (GameEvent.SpellResolved) -> Boolean = { resolved ->
            resolved.abilityGrpId in counterAffectingKeywordTriggerIds
        },
    ): GameEvent.SpellResolved? {
        if (ev.counterType != "P1P1" && ev.counterType != "+1/+1") return null
        for (next in events.asSequence().drop(eventIndex + 1)) {
            when {
                next is GameEvent.CountersChanged -> return null
                next is GameEvent.SpellResolved -> {
                    if (next.isTrigger && isCounterAffectingResolution(next)) {
                        return next
                    }
                    return null
                }
            }
        }
        return null
    }

    private fun isCounterAffectingKeywordResolution(
        resolved: GameEvent.SpellResolved,
        snap: GsmSnapshot,
        bridge: GameBridge,
    ): Boolean {
        if (resolved.abilityGrpId in counterAffectingKeywordTriggerIds) return true
        val sourceGrpId = snap.boundCards[resolved.cardId]?.snapshot?.grpId ?: return false
        return bridge.cardRepository.findKeywordAbilityGrpId(sourceGrpId, KeywordAbilityIds.BACKUP) == resolved.abilityGrpId
    }

    private fun playerCounterAffectorFor(
        eventIndex: Int,
        ev: GameEvent.PlayerCountersChanged,
        events: List<GameEvent>,
        frameIds: FrameIdResolver,
    ): InstanceId? {
        if (CounterTypes.counterTypeId(ev.counterType) == 0) return null
        for (next in events.asSequence().drop(eventIndex + 1)) {
            when (next) {
                is GameEvent.SpellResolved -> return InstanceId(stackAbilityIidFor(next.abilityForgeId, next.cardId, frameIds))
                else -> Unit
            }
        }
        return null
    }

    /** Keywords whose triggers we want to surface on the wire as
     *  `ResolutionStart`/`Complete grpid = <keyword ability id>`. Extend as new
     *  combat/ETB/state-trigger keywords ship and need precise grpId fidelity. */
    private val keywordTriggerIds =
        listOf(
            KeywordAbilityIds.BACKUP,
            KeywordAbilityIds.MENTOR,
            KeywordAbilityIds.MOBILIZE,
            KeywordAbilityIds.DECAYED,
            KeywordAbilityIds.ENLIST,
        )

    private val counterAffectingKeywordTriggerIds =
        setOf(KeywordAbilityIds.BACKUP, KeywordAbilityIds.MENTOR, KeywordAbilityIds.TRAINING)

    /** Best-effort owner seat lookup for an event-derived source card. */
    private fun ownerSeatOf(
        card: forge.game.card.Card,
        bridge: GameBridge,
    ): Int {
        val owner = card.owner ?: return 1
        return bridge.seatOf(owner)?.value ?: 1
    }

    /**
     * Scan the stack for spells/abilities with targets and emit TargetSpec pAnns.
     * Each card target gets a separate annotation with 1-based index per target group.
     * Pruned automatically by the registry-driven upsert pass (TargetSpecKind's
     * full-replacement semantics) when the spell resolves and leaves the stack.
     */
    private fun buildTargetSpecAnnotations(
        bridge: GameBridge,
        frameIds: FrameIdResolver,
        snap: GsmSnapshot,
    ): List<AnnotationInfo> {
        // Drain target picks recorded during selectTargetsInteractively.
        // The spell may have already resolved by now (auto-pass), so we can't
        // rely on scanning game.getStack() — the stack is often empty.
        val pending = bridge.drainPendingTargetSpecs()
        if (pending.isEmpty()) return emptyList()

        // promptId still needs per-ability prompt-shape mapping. Fall back to
        // 0 until a local mapping exists for the targeting prompt copy.
        return pending.mapNotNull { spec ->
            // Use the iid recorded at target-pick time for non-triggers (see
            // PendingTarget KDoc for the multi-target-spell rationale).
            // Triggers defer to emission-time resolution via the SA id —
            // TargetingCoordinator always populates spec.forgeAbilityId when
            // spec.isTriggeredAbility=true, so that branch's fallback is
            // structurally unreachable and crashes under DevCheck.strict.
            val affectorIid =
                if (spec.affectorInstanceIdAtRecord != 0) {
                    InstanceId(spec.affectorInstanceIdAtRecord)
                } else if (spec.isTriggeredAbility) {
                    if (spec.forgeAbilityId != 0) {
                        frameIds.triggerStackAbilityIid(spec.forgeAbilityId)
                    } else {
                        DevCheck.fail {
                            "PendingTarget for ${spec.spellName} marked isTriggeredAbility but missing forgeAbilityId; " +
                                "every triggered-ability target spec must carry the SA id since stack-ability iids " +
                                "are SA-id-keyed"
                        }
                        // Emit 0 rather than the source-card-keyed iid — that
                        // would point at a non-existent stack object since
                        // ZoneMapper now mints via the SA-id-keyed surrogate.
                        // 0 surfaces visibly in invariant checks rather than
                        // routing the TargetSpec to a stale iid.
                        InstanceId(0)
                    }
                } else {
                    frameIds.cardIid(ForgeCardId(spec.spellForgeCardId))
                }
            val targetIid =
                when {
                    spec.targetForgeCardId != null ->
                        frameIds.cardIid(ForgeCardId(spec.targetForgeCardId))
                    // Player target: Arena uses seatId (1 or 2) as the iid for player entities.
                    spec.targetSeatId != null -> InstanceId(spec.targetSeatId)
                    else -> return@mapNotNull null
                }
            val abilityGrpId = targetSpecAbilityGrpId(spec, bridge, snap)
            AnnotationBuilder.targetSpec(
                instanceId = targetIid,
                affectorId = affectorIid,
                abilityGrpId = GrpId(abilityGrpId),
                index = spec.index,
                promptId = spec.promptId ?: 0,
                promptParameters = affectorIid.value,
            )
        }
    }

    private fun targetSpecAbilityGrpId(
        spec: leyline.bridge.handoff.InteractivePromptBridge.PendingTarget,
        bridge: GameBridge,
        snap: GsmSnapshot,
    ): Int {
        spec.abilityGrpId?.let { return it }
        if (spec.forgeAbilityId != 0) {
            val resolved = abilityGrpIdForSource(ForgeCardId(spec.spellForgeCardId), spec.forgeAbilityId, bridge, snap)
            if (resolved != 0) return resolved
        }
        return bridge.cardRepository.findGrpIdByName(spec.spellName) ?: 0
    }

    private fun buildMutateMergeAnnotations(
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        val transient = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()
        val currentKeys = mutableSetOf<Pair<Int, Int>>()

        for (bound in snap.boundCards.values) {
            val targetIid = bound.snapshot.mergedToInstanceId ?: continue
            val componentIid = frameIds.cardIid(bound.forgeCardId).value
            val key = componentIid to targetIid
            currentKeys.add(key)

            val allocation = bridge.getOrAllocMutateMergeEffectId(componentIid, targetIid)
            if (allocation.created) {
                transient.add(
                    AnnotationBuilder.layeredEffectCreated(
                        effectId = EffectId(allocation.effectId),
                        affectorId = InstanceId(componentIid),
                    ),
                )
            }

            val abilityGrpIds =
                bound.data
                    ?.abilityIds
                    ?.map { it.first }
                    .orEmpty()
            persistent.add(
                AnnotationBuilder.mutateLayeredEffect(
                    componentId = InstanceId(componentIid),
                    targetId = InstanceId(targetIid),
                    effectId = EffectId(allocation.effectId),
                    abilityGrpIds = abilityGrpIds,
                    isTop = bound.snapshot.isTopMergedComponent,
                    abilityGrpId = GrpId(KeywordAbilityIds.MUTATE),
                ),
            )
        }

        for (effectId in bridge.releaseMutateMergeEffects(currentKeys)) {
            transient.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effectId)))
        }

        return transient to persistent
    }

    /** Crew annotation scan: CrewedThisTurn pAnns, ModifiedType pAnns, and expired effect annotations. */
    private fun computeCrewAnnotations(bridge: GameBridge): Triple<List<AnnotationInfo>, List<AnnotationInfo>, List<AnnotationInfo>> {
        val crewSnapshots = bridge.snapshotCrewState()
        val crewedThisTurn =
            crewSnapshots.map { snap ->
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

    /** Saddle annotation scan: SaddledThisTurn pAnns for mounts and helper creatures. */
    private fun computeSaddleAnnotations(bridge: GameBridge): List<AnnotationInfo> =
        bridge.snapshotSaddleState().map { snap ->
            AnnotationBuilder.saddledThisTurn(
                InstanceId(snap.mountInstanceId),
                snap.saddleSourceInstanceIds.map { InstanceId(it) },
            )
        }

    /**
     * Find the active reveal across all seats, or null. Clears stale reveals where
     * proxies were synthesized but the engine skipped the choice method
     * (e.g., Duress vs all-creature hand → DiscardEffect short-circuits at max==0).
     */
    private fun detectActiveReveal(bridge: GameBridge): PromptSideEffect.RevealStarted? =
        bridge.allSeatIds().firstNotNullOfOrNull { seatId ->
            val prompt = bridge.promptBridge(SeatId(seatId))
            val reveal = prompt.journal.activeReveal() ?: return@firstNotNullOfOrNull null
            if (!bridge.revealProxies.isEmpty && prompt.getPendingPrompt() == null) {
                TargetingCoordinator.Companion.endReveal(prompt) // stale — engine skipped choice
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
        snap: GsmSnapshot,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        events: MutableList<GameEvent>,
    ) {
        if (activeReveal != null) {
            val ownerSeat = activeReveal.ownerSeatId.value
            val viewerSeat = SeatId(ownerSeat).opponent.value
            val handZoneId = ZoneIds.handOf(ownerSeat)
            val revealedZoneId = ZoneIds.revealedOf(ownerSeat)

            val revealedZoneIdx = zones.indexOfFirst { it.zoneId == revealedZoneId }
            val revealedZoneBuilder =
                if (revealedZoneIdx >= 0) {
                    zones.removeAt(revealedZoneIdx).toBuilder()
                } else {
                    ZoneMapper.makeZone(revealedZoneId, ZoneType.Revealed, ownerSeat, Visibility.Public).toBuilder()
                }

            // Re-use proxy IDs across diffs during the same reveal (stable instanceIds).
            val needsAlloc = bridge.revealProxies.isEmpty
            for (forgeCardId in activeReveal.allHandCardIds) {
                val cardSnap = snap.objects[forgeCardId] ?: continue
                val proxyId =
                    if (needsAlloc) {
                        val id = bridge.ids.allocSynthetic()
                        bridge.revealProxies.allocate(forgeCardId, id)
                        id
                    } else {
                        bridge.revealProxies.lookup(forgeCardId) ?: continue
                    }
                revealedZoneBuilder.addObjectInstanceIds(proxyId.value)
                gameObjects.add(
                    ObjectMapper.buildRevealedCardProxy(
                        cardSnap,
                        proxyId.value,
                        handZoneId,
                        ownerSeat,
                        viewerSeat,
                        bridge.cardProto,
                        parentLinkage = snap.boundCards[forgeCardId]?.parentLinkage,
                    ),
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
        prev: GsmSnapshot? = null,
        snap: GsmSnapshot? = null,
        frameIds: FrameIdResolver? = null,
    ): AnnotationPipelineResult {
        val combatTransferredIds =
            transferResult.transfers
                .mapNotNull { transfer -> transfer.forgeCardId?.let { it to transfer.origId } }
                .toMap()
        val combatResult =
            CombatAnnotations.combatAnnotations(
                events = events,
                bridge = bridge,
                prev = prev,
                transferredIds = combatTransferredIds,
            )
        // Tests can drive computeAnnotations without a resolver; in that case
        // build a no-realloc instance from the bridge alone — `cardIid` falls
        // through to bridge.getOrAllocInstanceId, matching prior behaviour.
        val resolver = frameIds ?: FrameIdResolver(bridge)
        val (annotations, transferPersistent) =
            assembleTransferAndCombatAnnotations(
                events = events,
                transferResult = transferResult,
                actingSeat = actingSeat,
                combatResult = combatResult,
                bridge = bridge,
                snap = snap,
                frameIds = resolver,
            )
        return AnnotationPipelineResult(annotations, transferPersistent, combatResult)
    }
}
