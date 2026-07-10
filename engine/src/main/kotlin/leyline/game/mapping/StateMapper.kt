package leyline.game.mapping

import leyline.bridge.coord.TargetingCoordinator
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.annotations.AnnotationContext
import leyline.game.annotations.AnnotationPipeline
import leyline.game.annotations.CombatAnnotationResult
import leyline.game.annotations.ConvokeContributor
import leyline.game.annotations.TransferCategory
import leyline.game.annotations.TransferResult
import leyline.game.annotations.ZoneTransferDetector
import leyline.game.bundle.GsmFrame
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.SnapDeltaSynthesizer
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.BridgeMutations
import leyline.game.state.FrameContext
import leyline.game.state.GameBridge
import leyline.game.state.HolderBatch
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
 * - `bridge.drainEarthbendFrame()` — Earthbend layered-effect tracker, drained
 *   once per frame by the annotation pipeline; reads pending created/destroyed
 *   layer ids and clears them as part of the same call.
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
        var transferResult =
            ZoneTransferDetector.detectZoneTransfers(
                gameObjects,
                zones,
                bridge,
                eventsMutable,
                zoneMoves = events.zoneMoves,
                useEventLedger = true,
            )
        recordParadigmSourceStackIids(transferResult, bridge)
        // Frame-scoped id resolver — uses the planned-realloc map so any consumer
        // asking "what iid will the client see for this card?" gets the
        // post-realloc answer even before applyMutations runs.
        val frameIds = FrameIdResolver(bridge, FrameIdResolver.postReallocIids(transferResult))
        transferResult = transferResult.withDecayedCleanupAffectors(eventsMutable, snap, bridge, frameIds)
        val actingSeat = snap.phase.priorityPlayer?.value ?: 2
        val (annotations, transferPersistent, combatResult) =
            AnnotationPipeline.computeAnnotations(
                eventsMutable,
                transferResult,
                actingSeat,
                bridge,
                prev = prev,
                snap = snap,
                frameIds = frameIds,
            )

        val convokeCtx =
            AnnotationContext(bridge = bridge, snap = snap, frameIds = frameIds, events = eventsMutable, transferResult = transferResult)
        val convokePaymentsBySource = convokeCtx.activeConvokePaymentsBySource()
        annotations.addAll(ConvokeContributor.contribute(convokeCtx).transient)

        val decayedCleanupSourcesThisGsm = updateDecayedCleanupSources(eventsMutable, snap, bridge, transferResult, frameIds)

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
            val resolvingAbilityIid =
                eventsMutable
                    .filterIsInstance<GameEvent.SpellResolved>()
                    .filter { it.isTrigger || it.isAbility }
                    .map { resolved ->
                        InstanceId(AnnotationContext.stackAbilityIid(resolved.abilityForgeId, resolved.cardId, frameIds))
                    }.singleOrNull()
            insertStateDesignationTransients(
                annotations = annotations,
                prev = prev,
                cur = snap,
                resolveInstanceId = { fid -> bridge.getOrAllocInstanceId(fid) },
                resolveAffectorId = { spec, _ ->
                    if (spec.kind == DesignationKind.SUSPECTED) resolvingAbilityIid else null
                },
            )
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
                        .map { AnnotationContext.stackAbilityIid(it.abilityForgeId, it.cardId, frameIds) }
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
        // Stage-4-5 context deliberately omits transferResult — only the
        // transfer-stage Convoke emission (convokeCtx above) diffs zone transfers.
        val annCtx = AnnotationContext(bridge = bridge, snap = snap, frameIds = frameIds, events = eventsMutable)
        val remaining =
            AnnotationPipeline.computeRemainingAnnotations(
                annCtx,
                annotations,
                transferPersistent,
                initEffectDiff,
                effectDiff,
                persistSnapshot,
                startPersistentId,
                startAnnotationId,
                frameContext,
                keywordDiff,
                combatResult,
                persistentFeeds,
                convokePaymentsBySource,
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
                consumedTargetSpecs = remaining.consumedTargetSpecs,
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
                bridge.recordParadigmSourceStackIid(forgeCardId, transfer.newId)
            } else if (isStackSelfExile) {
                bridge.recordParadigmSourceStackIidIfAbsent(forgeCardId, transfer.origId)
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
        remaining: AnnotationPipeline.RemainingAnnotationsResult,
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
                    ev.cardId to AnnotationContext.stackAbilityIid(ev.abilityForgeId, ev.cardId, frameIds)
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
        frameIds: FrameIdResolver,
    ): Set<ForgeCardId> {
        val ctx = AnnotationContext(bridge = bridge, snap = snap, frameIds = frameIds, events = events)
        val visibleThisGsm = bridge.activeDecayedCleanupSources().toMutableSet()
        val addedThisGsm = linkedSetOf<ForgeCardId>()
        for (ev in events) {
            if (ev is GameEvent.SpellResolved) {
                val cleanupGrpId = PersistentFeedBuilder.decayedCleanupGrpIdForSource(ev.cardId, snap, bridge, transferResult)
                val abilityGrpId =
                    ev.abilityGrpId.takeIf { it != 0 }
                        ?: ctx.abilityGrpIdForSource(ev.cardId, ev.abilityForgeId)
                if (ev.isTrigger && cleanupGrpId != null && abilityGrpId == KeywordAbilityIds.DECAYED) {
                    bridge.recordDecayedCleanupSource(ev.cardId)
                    visibleThisGsm.add(ev.cardId)
                    addedThisGsm.add(ev.cardId)
                }
            } else if (ev is GameEvent.SpellCast) {
                val cleanupGrpId = PersistentFeedBuilder.decayedCleanupGrpIdForSource(ev.cardId, snap, bridge, transferResult)
                val abilityGrpId =
                    ev.abilityGrpId.takeIf { it != 0 }
                        ?: ctx.abilityGrpIdForSource(ev.cardId, ev.abilityForgeId)
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
}
