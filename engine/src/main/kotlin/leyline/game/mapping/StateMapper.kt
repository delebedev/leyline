package leyline.game.mapping

import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.annotations.AnnotationContext
import leyline.game.annotations.AnnotationFrameFinalizer
import leyline.game.annotations.AnnotationPipeline
import leyline.game.annotations.CombatAnnotationResult
import leyline.game.annotations.ConvokeContributor
import leyline.game.annotations.TransferCategory
import leyline.game.annotations.TransferResult
import leyline.game.annotations.ZoneTransferAdapter
import leyline.game.bundle.GsmFrame
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.BridgeMutations
import leyline.game.state.DelayedTriggerAffecteesKind
import leyline.game.state.EarthbendTracker
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.EffectTracker
import leyline.game.state.FrameContext
import leyline.game.state.GameBridge
import leyline.game.state.HolderBatch
import leyline.game.state.HolderRecord
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.ProjectionAnnotationJournal
import leyline.game.state.PromptFactConsumption
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.SyntheticEffectProjection
import leyline.game.state.SyntheticEffectTransition
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Orchestrates the GsmSnapshot → proto state-mapping pipeline.
 *
 * Two core methods:
 * - [buildFromSnapshot]: Full [GameStateMessage] from a captured [leyline.game.snapshot.GsmSnapshot].
 * - [buildDiff]: Diff GSM by snap-vs-snap field comparison; returns [leyline.game.state.BridgeMutations]
 *   for the caller to apply via [leyline.game.state.GameBridge.applyMutations]. Transaction-managed
 *   projection state is tentative, with its writes isolated in the returned mutations; retained
 *   live reads and lazy caches remain.
 *
 * Lifecycle GSM factories (deal-hand, mulligan, transitions) live in [leyline.game.bundle.GsmBuilder].
 * Interactive request builders (targeting, combat) live in [leyline.game.bundle.RequestBuilder].
 * Pure Forge→proto projection lives in the `mapper/` subpackage.
 *
 * ## Tentative compute boundary
 *
 * Single contract: both [buildFromSnapshot] and [buildDiff] return an annotation
 * frame draft plus [leyline.game.state.BridgeMutations]. Callers finalize the
 * complete transient list, then apply via [leyline.game.state.GameBridge.applyMutations].
 * Transaction-managed projection state has no committed writes during compute.
 * Its ordering-sensitive changes
 * (id reallocations, limbo retires, zone recordings, persistent annotation
 * batch and delayed-trigger holder lifecycle) flow through the returned
 * mutations. The finalizer supplies `nextAnnotationId` before application.
 *
 * [StateFrameInput] carries snapshot, event, [PromptProjectionFacts],
 * [EffectProjectionFacts], [MechanicSourceFacts], and [AbilityExhaustionFacts] values for scoped
 * projection inputs. [buildDiff]
 * also accepts [GameBridge], so this is not a complete pure-function boundary.
 *
 * The acceptance forcing function for this boundary is [PureDiffReplayTest],
 * which replays recorded `(snap, events, diff)` tuples through [buildDiff] on
 * a fresh bridge and asserts byte-equal Diff GSMs across scenarios. A
 * regression there signals newly-introduced impurity.
 *
 * ## Retained bridge dependencies
 *
 * These remain inside the pipeline for bounded reasons. The examples below are
 * not a complete dependency catalog; the replay test proves only its covered
 * scenarios.
 *
 * Reads of effectively-immutable / card-DB state:
 * - [leyline.game.state.GameBridge.getOrAllocInstanceId] resolves through the
 *   private [leyline.game.state.InstanceIdRegistry] planner during this scope.
 * - `bridge.cardRepository.findGrpIdByName` / `findByGrpId`. Read-only card DB.
 * - `bridge.ids`, `getForgeCardId`, and [FrameIdResolver] retain identity and
 *   reverse-reference reads; allocations remain tentative.
 *
 * Ordinary state and shared-zone semantics read only [GsmSnapshot] plus the
 * match-scoped [StateProjectionEnvironment]. Stable card-proto data and match
 * configuration are frozen by the shell before projection. Retained shell
 * reads outside that layer include limbo and reveal-state tracking.
 *
 * Explicit retained-state reads:
 * - `bridge.opponentKnowledge`, `delayedTriggerHolders`, and
 *   `pendingTransientLinkedFaceFamilyIds`.
 *
 * Scoped synthetic-effect reads arrive in [EffectProjectionFacts]. Mechanic
 * source zones, token-creator fallback, and basic-land mana identity arrive in
 * [MechanicSourceFacts] and [leyline.game.snapshot.CardSnapshot]. Other
 * annotation/mechanic reference data remains bridge-attached in this slice.
 *
 * Tentative planners over bridge-backed state (not yet lifted onto frame values):
 * - `bridge.revealProxies` — RevealedCard proxy tracker, tied to transactional
 *   reveal-choose effects that span bundles.
 * - prompt-derived projection data arrives in [PromptProjectionFacts].
 * - synthetic lifecycle reduction runs in the private
 *   [SyntheticEffectProjection.Planner] and returns through [BridgeMutations].
 *
 * Identity and synthetic-effect allocations are tentative and returned in
 * [BridgeMutations]; committed state remains unchanged until the shell
 * validates and installs the complete plan.
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

    private fun withTentativeProjectionState(
        bridge: GameBridge,
        block: (ProjectionAnnotationJournal.Planner) -> BuildResult,
    ): BuildResult =
        bridge.withTentativeAnnotationJournal { journal ->
            bridge.withTentativeEffectProjection {
                bridge.ids.withTentativeState {
                    bridge.revealProxies.withTentativeState {
                        block(journal).withAnnotationJournalTransition(journal.transition())
                    }
                }
            }
        }

    private fun BuildResult.withAnnotationJournalTransition(transition: ProjectionAnnotationJournal.Transition): BuildResult =
        copy(mutations = mutations.copy(annotationJournalTransition = transition))

    data class AnnotationFrameDraft(
        val firstAnnotationId: Int,
        val idResolver: FrameIdResolver,
    )

    /** Result of [buildFromSnapshot] / [buildDiff] — GSM draft plus deferred mutations. */
    data class BuildResult(
        val gsm: GameStateMessage,
        /** Snapshot including projection-only card fields; becomes the next diff baseline. */
        val projectionSnapshot: GsmSnapshot,
        /** Ordering-sensitive bridge mutations computed during the build. Caller applies via [leyline.game.state.GameBridge.applyMutations]. */
        val mutations: BridgeMutations,
        val annotationFrameDraft: AnnotationFrameDraft?,
        /** Existing objects that must be re-emitted for state projected outside [CardSnapshot]. */
        val objectRefreshInstanceIds: Set<Int>,
    ) {
        fun finalizeAnnotations(riders: List<AnnotationInfo> = emptyList()): BuildResult {
            val draft = checkNotNull(annotationFrameDraft) { "Annotation frame is already finalized" }
            val finalized =
                AnnotationFrameFinalizer.finalize(
                    gsm.annotationsList + riders,
                    draft.firstAnnotationId,
                )
            val frozenGsm =
                gsm
                    .toBuilder()
                    .clearAnnotations()
                    .addAllAnnotations(finalized.annotations)
                    .build()
            return copy(
                gsm = frozenGsm,
                mutations = mutations.copy(nextAnnotationId = finalized.nextId),
                annotationFrameDraft = null,
            )
        }
    }

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
        environment: StateProjectionEnvironment = StateProjectionEnvironmentCapture.from(bridge),
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        prev: GsmSnapshot? = null,
        events: FrameEventLog = FrameEventLog.EMPTY,
        promptFacts: PromptProjectionFacts = PromptProjectionFacts(),
        effectFacts: EffectProjectionFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
    ): BuildResult =
        buildFromSnapshot(
            snap = snap,
            gameStateId = gameStateId,
            matchId = matchId,
            bridge = bridge,
            environment = environment,
            actions = actions,
            updateType = updateType,
            viewingSeatId = viewingSeatId,
            revealForSeat = revealForSeat,
            prev = prev,
            events = events,
            promptFacts = promptFacts,
            effectFacts = effectFacts,
            mechanicSourceFacts = emptyMechanicSourceFactsFor(events),
            abilityExhaustionFacts = abilityExhaustionFacts,
        )

    @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
    fun buildFromSnapshot(
        snap: GsmSnapshot,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        environment: StateProjectionEnvironment = StateProjectionEnvironmentCapture.from(bridge),
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
        events: FrameEventLog = FrameEventLog.EMPTY,
        promptFacts: PromptProjectionFacts = PromptProjectionFacts(),
        effectFacts: EffectProjectionFacts,
        mechanicSourceFacts: MechanicSourceFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
    ): BuildResult =
        withTentativeProjectionState(bridge) { journal ->
            buildFromSnapshotInternal(
                rawSnap = snap,
                gameStateId = gameStateId,
                matchId = matchId,
                bridge = bridge,
                environment = environment,
                actions = actions,
                updateType = updateType,
                viewingSeatId = viewingSeatId,
                revealForSeat = revealForSeat,
                prev = prev,
                events = events,
                promptFacts = promptFacts,
                effectFacts = effectFacts,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
                annotationJournal = journal,
            )
        }

    @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
    private fun buildFromSnapshotInternal(
        rawSnap: GsmSnapshot,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        environment: StateProjectionEnvironment,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        prev: GsmSnapshot? = null,
        events: FrameEventLog,
        promptFacts: PromptProjectionFacts,
        effectFacts: EffectProjectionFacts,
        mechanicSourceFacts: MechanicSourceFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
        annotationJournal: ProjectionAnnotationJournal.Planner,
    ): BuildResult {
        val effectPlanner = bridge.activeEffectPlanner()
        val earthbendResolutions = effectFacts.pendingEarthbendResolutions
        val earthbendSignatures = effectFacts.battlefieldEarthbendSignatures
        for (resolution in earthbendResolutions) {
            if (rawSnap.boundCards[resolution.sourceCardId] == null) continue
            val sourceIid = bridge.getOrAllocInstanceId(resolution.sourceCardId)
            val resolvingIid =
                if (resolution.abilityForgeId != 0) {
                    bridge.getOrAllocInstanceId(FrameIdResolver.triggerStackAbilityForgeId(resolution.abilityForgeId))
                } else {
                    sourceIid
                }
            effectPlanner.earthbend.recordResolution(
                resolution = resolution,
                sourceCardGrpId = rawSnap.boundCards[resolution.sourceCardId]?.snapshot?.grpId ?: 0,
                sourceInstanceId = sourceIid.value,
                resolvingInstanceId = resolvingIid.value,
                battlefieldSignatures = earthbendSignatures,
                targetInstanceId = { forgeCardId -> bridge.getOrAllocInstanceId(forgeCardId).value },
                nextEffectId = effectPlanner.effects::nextEffectId,
            )
        }
        val snap =
            rawSnap.withEarthbendProjection { forgeCardId ->
                effectPlanner.earthbend.projectionFor(
                    forgeCardId,
                    earthbendSignatures.firstOrNull { it.forgeCardId == forgeCardId }?.signature,
                )
            }
        val frame = GsmFrame.Companion.from(snap)

        // ═══ GATHER: snapshot mutable state (events arrive from caller) ═══
        // applyRevealProxies may append RevealProxiesDeleted on reveal end; keep local mutable copy.
        val eventsMutable = events.events.toMutableList()
        val initEffectDiff = effectPlanner.effects.emitInitEffectsOnce()
        val boostSnapshot = boostEntries(effectFacts, bridge)
        val effectDiff = effectPlanner.effects.diffBoosts(boostSnapshot)
        val keywordSnapshot = keywordEntries(effectFacts, effectPlanner.earthbend, bridge)
        val keywordDiff = effectPlanner.effects.diffKeywords(keywordSnapshot)
        // Persistent annotation baseline is carried on the snapshot (captured
        // at snap time in SnapshotCapture). computeBatch is pure over this value.
        // See PersistentAnnotationStore class KDoc for lifecycle and ordering invariants.
        val persistentState = snap.persistentAnnotationState
        val persistSnapshot = persistentState.activeAnnotations
        val startPersistentId = persistentState.nextPersistentId
        val startAnnotationId = persistentState.nextAnnotationId

        // ═══ MAP: engine state → proto objects ═══
        val gameInfo = StateZoneProjection.buildGameInfo(matchId, environment.matchConfig)

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

        // Classify a stale reveal from materialized prompt facts. Its compare-and-clear
        // intent returns with this draft and runs only after an accepted commit.
        val (activeReveal, staleReveals) = detectActiveReveal(promptFacts, bridge.revealProxies.isEmpty)
        val revealedHandSeat = activeReveal?.ownerSeatId?.value

        // Player 1 zones
        if (StateZoneProjection.hasSeat(snap, SeatId(1))) {
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
        if (StateZoneProjection.hasSeat(snap, SeatId(2))) {
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
        projectSharedZone(snap, ZoneIds.BATTLEFIELD, environment, bridge, zones, gameObjects, keywordSnapshot)
        projectSharedZone(snap, ZoneIds.STACK, environment, bridge, zones, gameObjects)
        projectSharedZone(snap, ZoneIds.SUPPRESSED, environment, bridge, zones, gameObjects)
        projectSharedZone(snap, ZoneIds.EXILE, environment, bridge, zones, gameObjects)
        projectSharedZone(snap, ZoneIds.COMMAND, environment, bridge, zones, gameObjects)

        // Stack abilities (triggers, activated abilities not represented as zone cards)
        val stateZoneFacts = StateZoneProjection.zoneTransferFacts(snap)
        ZoneMapper.addStackAbilitiesFromSnapshot(
            snap = snap,
            bridge = bridge,
            paradigmSourceStackIidLookup = { forgeCardId ->
                StateZoneProjection.paradigmSourceStackIid(
                    facts = stateZoneFacts,
                    forgeCardId = forgeCardId,
                    stackIidLookup = annotationJournal::paradigmSourceStackIidFor,
                )
            },
            zones = zones,
            gameObjects = gameObjects,
        )

        // RevealedCard proxy synthesis / cleanup (may append RevealProxiesDeleted to eventsMutable)
        applyRevealProxies(activeReveal, snap, bridge, zones, gameObjects, eventsMutable)

        log.debug(
            "buildFromSnapshot: phase={} turn={} hand={} objects={} zones={}",
            snap.phase.phase,
            snap.phase.turn,
            snap.zones[ZoneIds.P1_HAND]?.contents?.size ?: 0,
            gameObjects.size,
            zones.size,
        )

        // ═══ COMPUTE: annotation pipeline (stages 1-5) ═══
        var transferResult =
            ZoneTransferAdapter.detectZoneTransfers(
                gameObjects,
                zones,
                bridge,
                snap,
                eventsMutable,
                annotationJournal,
                zoneMoves = events.zoneMoves,
            )
        recordParadigmSourceStackIids(transferResult, snap, annotationJournal)
        // Frame-scoped id resolver — uses the planned-realloc map so any consumer
        // asking "what iid will the client see for this card?" gets the
        // post-realloc answer even before applyMutations runs.
        val frameIds = FrameIdResolver(bridge, FrameIdResolver.postReallocIids(transferResult))
        val (opponentKnowledge, opponentKnowledgeTransition) =
            bridge.opponentKnowledge.plan(snap, frameIds, eventsMutable)
        val resolvedStackAbilityIids =
            eventsMutable
                .filterIsInstance<GameEvent.SpellResolved>()
                .filter { it.isTrigger || it.isAbility }
                .mapTo(linkedSetOf()) { AnnotationContext.stackAbilityIid(it.abilityForgeId, it.cardId, frameIds) }
        transferResult = transferResult.withoutStackAbilities(resolvedStackAbilityIids)
        transferResult = transferResult.withDecayedCleanupAffectors(eventsMutable, snap, bridge, frameIds)
        val actingSeat = snap.phase.priorityPlayer?.value ?: 2
        val (annotations, transferPersistent, combatResult) =
            AnnotationPipeline.computeAnnotations(
                eventsMutable,
                transferResult,
                actingSeat,
                bridge,
                snap = snap,
                frameIds = frameIds,
                annotationJournal = annotationJournal,
                mechanicSourceFacts = mechanicSourceFacts,
            )

        val convokeCtx =
            AnnotationContext(
                bridge = bridge,
                snap = snap,
                frameIds = frameIds,
                events = eventsMutable,
                promptFacts = promptFacts,
                effectFacts = effectFacts,
                opponentKnowledge = opponentKnowledge,
                transferResult = transferResult,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
            )
        val convokePaymentsBySource = convokeCtx.activeConvokePaymentsBySource()
        val convokePlan = ConvokeContributor.plan(convokeCtx)
        annotations.addAll(convokePlan.transient)

        val decayedCleanupSourcesThisGsm =
            updateDecayedCleanupSources(eventsMutable, snap, bridge, transferResult, frameIds, annotationJournal)

        val persistentFeedResult =
            PersistentFeedBuilder.build(
                events = eventsMutable,
                snap = snap,
                prev = prev,
                bridge = bridge,
                frameIds = frameIds,
                decayedCleanupSourcesThisGsm = decayedCleanupSourcesThisGsm,
                transferResult = transferResult,
                promptFacts = promptFacts,
            )
        val carriedHolders =
            delayedTriggerHoldersAwaitingLiveAbility(
                activeHolders = bridge.delayedTriggerHolders.activeRecords(),
                currentHolders = persistentFeedResult.currentHolders,
                activeAnnotations = snap.persistentAnnotationState.activeAnnotations.values,
                snap = snap,
                bridge = bridge,
            )
        val currentHolders = persistentFeedResult.currentHolders + carriedHolders
        val holderBatch = bridge.delayedTriggerHolders.computeBatch(currentHolders)
        val removedHolderRecords = bridge.delayedTriggerHolders.records(holderBatch.removed)
        val delayedTriggerAffectorReplacements =
            delayedTriggerAffectorReplacements(removedHolderRecords, snap, frameIds)
        val postDiffActiveIids =
            (bridge.delayedTriggerHolders.activeIids() + holderBatch.added.map { it.iid }) -
                holderBatch.removed.toSet()
        transferResult = transferResult.withDelayedTriggerHolders(holderBatch, postDiffActiveIids, bridge)
        // Stack contents (cards) plus stack-resident Ability gameObjects — both
        // can own persistent trigger relations.
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
        val persistentFeeds =
            PersistentFeedBuilder.remapDelayedTriggerAffectees(
                feeds =
                    PersistentFeedBuilder.retainDelayedTriggerAffectees(
                        feeds = persistentFeedResult.feeds,
                        activeAnnotations = snap.persistentAnnotationState.activeAnnotations.values,
                        holderIids = carriedHolders.map { it.iid }.toSet() + (stackIids - resolvingStackIids),
                    ),
                activeAnnotations = snap.persistentAnnotationState.activeAnnotations.values,
                affectorReplacements = delayedTriggerAffectorReplacements,
            )
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
                displayCardAffectors = battlefieldIids + postDiffActiveIids + (stackIids - resolvingStackIids),
                delayedTriggerAffectorReplacements = delayedTriggerAffectorReplacements,
            )
        // Stage-4-5 contributors may need the frame's pre-reallocation identities.
        val annCtx =
            AnnotationContext(
                bridge = bridge,
                snap = snap,
                frameIds = frameIds,
                events = eventsMutable,
                promptFacts = promptFacts,
                effectFacts = effectFacts,
                opponentKnowledge = opponentKnowledge,
                transferResult = transferResult,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
            )
        val remaining =
            AnnotationPipeline.computeRemainingAnnotations(
                annCtx,
                annotations,
                transferPersistent,
                initEffectDiff,
                effectDiff,
                persistSnapshot,
                startPersistentId,
                frameContext,
                keywordDiff,
                combatResult,
                persistentFeeds,
                convokePaymentsBySource,
                transferResult = transferResult,
                annotationJournal = annotationJournal,
            )

        transferResult = LinkedFaceCompanionProjector.append(transferResult, snap, bridge, frameIds)

        // ═══ ASSEMBLE: build the GSM proto ═══
        val built =
            assembleGsm(
                gameStateId,
                gameInfo,
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
                instanceIdTransition = bridge.ids.tentativeTransition(),
                effectTransition =
                    SyntheticEffectTransition(
                        expected = bridge.committedEffectProjection(),
                        next = bridge.tentativeEffectProjection(),
                    ),
                opponentKnowledgeTransition = opponentKnowledgeTransition,
                consumedEarthbendResolutions = earthbendResolutions,
                revealTransition = bridge.revealProxies.tentativeTransition(),
                annotationJournalTransition = annotationJournal.transition(),
                idReallocations = transferResult.idReallocations,
                retiredIds = transferResult.retiredIds.map { InstanceId(it) },
                zoneRecordings = transferResult.zoneRecordings.map { (iid, zid) -> InstanceId(iid) to zid },
                persistentBatch = remaining.batch,
                promptFactConsumption =
                    PromptFactConsumption(
                        choiceResults = promptFacts.choiceResults,
                        staleReveals = staleReveals,
                        convokePayments = convokePlan.consumedPayments,
                        collectEvidenceCosts =
                            promptFacts.collectEvidenceCosts.filter { fact ->
                                eventsMutable.any { it is GameEvent.SpellCast && it.cardId == fact.context.sourceForgeCardId }
                            },
                        targetSpecs = remaining.consumedTargetSpecs,
                    ),
                nextAnnotationId = null,
                holderBatch = holderBatch,
                diffDeletedInstanceIds =
                    (stackTransferDeletedIds(transferResult) + resolvedStackAbilityIids)
                        .distinct()
                        .map { InstanceId(it) },
                nextTransientLinkedFaceFamilyIds = transferResult.transientHiddenFamilyIds.mapTo(mutableSetOf(), ::InstanceId),
            )

        return BuildResult(
            gsm = built,
            projectionSnapshot = snap,
            mutations = mutations,
            annotationFrameDraft = AnnotationFrameDraft(startAnnotationId, frameIds),
            objectRefreshInstanceIds =
                (keywordDiff.created + keywordDiff.destroyed)
                    .map { it.cardInstanceId }
                    .toSet(),
        )
    }

    private fun recordParadigmSourceStackIids(
        transferResult: TransferResult,
        snap: GsmSnapshot,
        annotationJournal: ProjectionAnnotationJournal.Planner,
    ) {
        for (transfer in transferResult.transfers) {
            val forgeCardId = transfer.forgeCardId ?: continue
            if (!StateZoneProjection.isParadigm(snap, forgeCardId)) continue
            val isOriginalCast =
                transfer.category == TransferCategory.CastSpell &&
                    (transfer.srcZoneId == ZoneIds.P1_HAND || transfer.srcZoneId == ZoneIds.P2_HAND) &&
                    transfer.destZoneId == ZoneIds.STACK
            val isStackSelfExile =
                transfer.category == TransferCategory.Exile &&
                    transfer.srcZoneId == ZoneIds.STACK &&
                    transfer.destZoneId == ZoneIds.EXILE
            if (isOriginalCast) {
                annotationJournal.recordParadigmSourceStackIid(forgeCardId, transfer.newId)
            } else if (isStackSelfExile) {
                annotationJournal.recordParadigmSourceStackIidIfAbsent(forgeCardId, transfer.origId)
            }
        }
    }

    private fun projectSharedZone(
        snap: GsmSnapshot,
        arenaZoneId: Int,
        environment: StateProjectionEnvironment,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        keywordSnapshot: Map<Int, List<EffectTracker.KeywordEntry>> = emptyMap(),
    ) {
        ZoneMapper.addSharedZoneCardsFromSnapshot(
            snap = snap,
            arenaZoneId = arenaZoneId,
            environment = environment,
            instanceIdLookup = bridge::getOrAllocInstanceId,
            zones = zones,
            gameObjects = gameObjects,
            keywordSnapshot = keywordSnapshot,
        )
    }

    private fun boostEntries(
        facts: EffectProjectionFacts,
        bridge: GameBridge,
    ): Map<Int, List<EffectTracker.BoostEntry>> {
        val instanceIds = linkedMapOf<ForgeCardId, Int>()
        val entriesByInstance = linkedMapOf<Int, MutableList<EffectTracker.BoostEntry>>()
        for (entry in facts.boostEntries) {
            val instanceId = instanceIds.getOrPut(entry.forgeCardId) { bridge.getOrAllocInstanceId(entry.forgeCardId).value }
            entriesByInstance
                .getOrPut(instanceId) { mutableListOf() }
                .add(
                    EffectTracker.BoostEntry(
                        timestamp = entry.timestamp,
                        staticId = entry.staticId,
                        power = entry.power,
                        toughness = entry.toughness,
                        sourceAbilityGrpId = entry.sourceAbilityGrpId,
                    ),
                )
        }
        return entriesByInstance.mapValues { (_, entries) -> entries.toList() }
    }

    private fun keywordEntries(
        facts: EffectProjectionFacts,
        earthbend: EarthbendTracker,
        bridge: GameBridge,
    ): Map<Int, List<EffectTracker.KeywordEntry>> {
        val instanceIds = linkedMapOf<ForgeCardId, Int>()
        val entriesByInstance = linkedMapOf<Int, MutableList<EffectTracker.KeywordEntry>>()
        for (entry in facts.keywordEntries) {
            val instanceId = instanceIds.getOrPut(entry.forgeCardId) { bridge.getOrAllocInstanceId(entry.forgeCardId).value }
            if (
                entry.keyword == "Haste" &&
                earthbend.isEarthbendHasteKeyword(
                    entry.forgeCardId,
                    entry.timestamp,
                    entry.staticId,
                )
            ) {
                continue
            }
            entriesByInstance
                .getOrPut(instanceId) { mutableListOf() }
                .add(
                    EffectTracker.KeywordEntry(
                        timestamp = entry.timestamp,
                        staticId = entry.staticId,
                        keyword = entry.keyword,
                        affectorForgeCardId = entry.affectorForgeCardId,
                    ),
                )
        }
        return entriesByInstance.mapValues { (_, entries) -> entries.toList() }
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
        input: StateFrameInput,
        matchId: String,
        bridge: GameBridge,
        environment: StateProjectionEnvironment = StateProjectionEnvironmentCapture.from(bridge),
        actions: ActionsAvailableReq? = null,
    ): BuildResult =
        withTentativeProjectionState(bridge) { journal ->
            buildDiffInternal(
                prev = input.previousSnapshot,
                cur = input.snapshot,
                events = input.events,
                promptFacts = input.promptFacts,
                effectFacts = input.effectFacts,
                mechanicSourceFacts = input.mechanicSourceFacts,
                abilityExhaustionFacts = input.abilityExhaustionFacts,
                gameStateId = input.gameStateId,
                matchId = matchId,
                bridge = bridge,
                environment = environment,
                actions = actions,
                updateType = input.updateType,
                viewingSeatId = input.viewingSeatId,
                revealForSeat = input.revealForSeat,
                annotationJournal = journal,
            )
        }

    @Suppress("LongParameterList")
    fun buildDiff(
        prev: GsmSnapshot?,
        cur: GsmSnapshot,
        events: FrameEventLog,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        environment: StateProjectionEnvironment = StateProjectionEnvironmentCapture.from(bridge),
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        promptFacts: PromptProjectionFacts = PromptProjectionFacts(),
        effectFacts: EffectProjectionFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
    ): BuildResult =
        buildDiff(
            prev = prev,
            cur = cur,
            events = events,
            gameStateId = gameStateId,
            matchId = matchId,
            bridge = bridge,
            environment = environment,
            actions = actions,
            updateType = updateType,
            viewingSeatId = viewingSeatId,
            revealForSeat = revealForSeat,
            promptFacts = promptFacts,
            effectFacts = effectFacts,
            mechanicSourceFacts = emptyMechanicSourceFactsFor(events),
            abilityExhaustionFacts = abilityExhaustionFacts,
        )

    @Suppress("LongParameterList")
    fun buildDiff(
        prev: GsmSnapshot?,
        cur: GsmSnapshot,
        events: FrameEventLog,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        environment: StateProjectionEnvironment = StateProjectionEnvironmentCapture.from(bridge),
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        promptFacts: PromptProjectionFacts = PromptProjectionFacts(),
        effectFacts: EffectProjectionFacts,
        mechanicSourceFacts: MechanicSourceFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
    ): BuildResult =
        withTentativeProjectionState(bridge) { journal ->
            buildDiffInternal(
                prev = prev,
                cur = cur,
                events = events,
                promptFacts = promptFacts,
                effectFacts = effectFacts,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
                gameStateId = gameStateId,
                matchId = matchId,
                bridge = bridge,
                environment = environment,
                actions = actions,
                updateType = updateType,
                viewingSeatId = viewingSeatId,
                revealForSeat = revealForSeat,
                annotationJournal = journal,
            )
        }

    private fun emptyMechanicSourceFactsFor(events: FrameEventLog): MechanicSourceFacts {
        require(events.events.isEmpty()) {
            "Event-bearing projection requires explicit MechanicSourceFacts"
        }
        return MechanicSourceFacts()
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition", "LongParameterList")
    private fun buildDiffInternal(
        prev: GsmSnapshot?,
        cur: GsmSnapshot,
        events: FrameEventLog,
        promptFacts: PromptProjectionFacts,
        effectFacts: EffectProjectionFacts,
        mechanicSourceFacts: MechanicSourceFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        environment: StateProjectionEnvironment,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        annotationJournal: ProjectionAnnotationJournal.Planner,
    ): BuildResult {
        if (prev == null) {
            // First bundle — Full GSM with mutations returned for caller-apply.
            return buildFromSnapshotInternal(
                rawSnap = cur,
                gameStateId = gameStateId,
                matchId = matchId,
                bridge = bridge,
                environment = environment,
                actions = actions,
                updateType = updateType,
                viewingSeatId = viewingSeatId,
                revealForSeat = revealForSeat,
                annotationJournal = annotationJournal,
                prev = null,
                events = events,
                promptFacts = promptFacts,
                effectFacts = effectFacts,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
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
            return buildFromSnapshotInternal(
                rawSnap = cur,
                gameStateId = gameStateId,
                matchId = matchId,
                bridge = bridge,
                environment = environment,
                actions = actions,
                updateType = updateType,
                viewingSeatId = viewingSeatId,
                revealForSeat = revealForSeat,
                annotationJournal = annotationJournal,
                prev = prev,
                events = events,
                promptFacts = promptFacts,
                effectFacts = effectFacts,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
            )
        }

        // Build current full GSM (viewingSeatId=0 to include all objects for accurate diff).
        val fullResult =
            buildFromSnapshotInternal(
                rawSnap = cur,
                gameStateId = gameStateId,
                matchId = matchId,
                bridge = bridge,
                environment = environment,
                revealForSeat = revealForSeat,
                annotationJournal = annotationJournal,
                prev = prev,
                events = events,
                promptFacts = promptFacts,
                effectFacts = effectFacts,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
            )
        val current = fullResult.gsm
        val projectedCur = fullResult.projectionSnapshot
        val currentCompanionIds =
            current.gameObjectsList
                .filter { LinkedFaceCompanionProjector.isCompanionType(it.type) }
                .mapTo(mutableSetOf()) { it.instanceId }
        val committedIdentity =
            bridge.ids
                .committedState()
                .state
                .forgeIdToInstanceId
        val previousCompanionIds =
            LinkedFaceCompanionProjector.instanceIds(
                prev,
                bridge,
                viewingSeatId,
                parentIidLookup = { forgeId -> committedIdentity[forgeId] },
            )
        val previousTransientHiddenFamilyIds = bridge.pendingTransientLinkedFaceFamilyIds().map { it.value }

        // Snap-vs-snap zone delta: any zone whose snapshot field-equality differs.
        val changedZoneIds =
            projectedCur.zones.keys
                .asSequence()
                .filter { id -> prev.zones[id] != projectedCur.zones[id] }
                .toSet()
        val opponentHandZoneId = ZoneMapper.opponentHandZone(viewingSeatId)
        val opponentSideboardZoneId = ZoneMapper.opponentSideboardZone(viewingSeatId)
        val activeReveal = promptFacts.activeReveal
        val hasActiveReveal = activeReveal != null
        // Protocol-only zones not tracked in GsmSnapshot must always be included when non-empty:
        //   - Limbo (id=30): grows monotonically; always send when it has content.
        //   - REVEALED_P1/P2 (id=18/19): synthesized by applyRevealProxies during active reveal.
        //   - Hand zone of revealed seat: visibility flipped to Public by buildFromSnapshot but
        //     ZoneSnapshot still records Private, so snap equality check misses the change.
        val opponentRevealedHandZoneId: Int? =
            when {
                hasActiveReveal ->
                    activeReveal.reveal.ownerSeatId.value
                        .let { ZoneIds.handOf(it) }
                else -> null
            }
        val hasStackRetirement = fullResult.mutations.diffDeletedInstanceIds.isNotEmpty()
        val changedZones =
            current.zonesList
                .filter { zone ->
                    zone.zoneId in changedZoneIds ||
                        (zone.zoneId == ZoneIds.STACK && hasStackRetirement) ||
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
            projectedCur.objects.keys
                .asSequence()
                .filter { fid -> prev.objects[fid] != projectedCur.objects[fid] }
                .toSet()

        // Cards whose zone changed (CardSnapshot doesn't carry zoneId; ZoneSnapshot.contents does).
        val prevZoneOf: Map<ForgeCardId, Int> =
            prev.zones.values
                .flatMap { z -> z.contents.map { it to z.id } }
                .toMap()
        val curZoneOf: Map<ForgeCardId, Int> =
            projectedCur.zones.values
                .flatMap { z -> z.contents.map { it to z.id } }
                .toMap()
        val zoneMovedFids =
            (prevZoneOf.keys + curZoneOf.keys)
                .asSequence()
                .filter { fid -> prevZoneOf[fid] != curZoneOf[fid] }
                .toSet()

        val prevDisturbBackSourceFids = projectedDisturbBackSourceFids(prev)
        val curDisturbBackSourceFids = projectedDisturbBackSourceFids(projectedCur)
        val changedFids = cardSnapshotChangedFids + zoneMovedFids
        val currentParentIds =
            changedFids.mapTo(mutableSetOf()) { fid ->
                checkNotNull(fullResult.annotationFrameDraft).idResolver.cardIid(fid).value
            }
        val changedCompanionIds =
            current.gameObjectsList
                .filter { LinkedFaceCompanionProjector.isCompanionType(it.type) && it.parentId in currentParentIds }
                .mapTo(mutableSetOf()) { it.instanceId }
        val changedDisturbBackIds =
            disturbBackInstanceIds(
                changedFids.filter { it in prevDisturbBackSourceFids || it in curDisturbBackSourceFids },
                bridge,
            )
        val changedInstanceIds =
            changedFids.map { bridge.getOrAllocInstanceId(it).value }.toSet() +
                changedDisturbBackIds +
                changedCompanionIds +
                fullResult.objectRefreshInstanceIds
        // instanceIds tracked in the prev snapshot (to detect truly new objects like RevealedCard proxies)
        val prevInstanceIds =
            prev.objects.keys
                .map { bridge.getOrAllocInstanceId(it).value }
                .toSet() +
                disturbBackInstanceIds(prevDisturbBackSourceFids, bridge) +
                previousCompanionIds
        val changedObjects =
            current.gameObjectsList.filter { obj ->
                // Always include new objects absent from prev (e.g. RevealedCard proxies synthesized mid-diff).
                if (obj.instanceId !in prevInstanceIds) {
                    // Still apply opponent-hand filter unless reveal is active
                    if (opponentHandZoneId != 0 && obj.zoneId == opponentHandZoneId) {
                        return@filter obj.type == GameObjectType.RevealedCard ||
                            (hasActiveReveal && obj.visibility == Visibility.Public)
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
                    if (obj.type == GameObjectType.RevealedCard || (hasActiveReveal && obj.visibility == Visibility.Public)) {
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
        val deletedCompanionIds = previousCompanionIds - currentCompanionIds
        val deletedIds =
            (
                (prev.objects.keys - projectedCur.objects.keys).map { bridge.getOrAllocInstanceId(it).value } +
                    deletedDisturbBackIds +
                    deletedCompanionIds
            ).filter { it !in currentObjIds && it !in currentZoneTrackedIds } +
                previousTransientHiddenFamilyIds

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
                // Emit new and in-place-updated persistent annotations. The client
                // accumulates unchanged rows by id and removes them through the
                // deletion list. Baseline is cur's state at frame entry.
                .addAllPersistentAnnotations(
                    current.persistentAnnotationsList.filter { annotation ->
                        projectedCur.persistentAnnotationState.activeAnnotations[annotation.id] != annotation
                    },
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
        return BuildResult(
            gsm = built,
            projectionSnapshot = projectedCur,
            mutations =
                fullResult.mutations.copy(
                    instanceIdTransition = bridge.ids.tentativeTransition(),
                    revealTransition = bridge.revealProxies.tentativeTransition(),
                ),
            annotationFrameDraft = fullResult.annotationFrameDraft,
            objectRefreshInstanceIds = fullResult.objectRefreshInstanceIds,
        )
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
                .addAllZones(transferResult.patchedZones.sortedBy { it.zoneId }.map(ZoneMapper::clientOrderedZone))
                .addAllGameObjects(transferResult.patchedObjects)
                .addAllAnnotations(remaining.transient)
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

    /**
     * Resolution events are authoritative when Forge's stack snapshot still
     * carries the resolved ability for the current frame. Remove those stale
     * projections before diffing; [BridgeMutations.diffDeletedInstanceIds]
     * retires their client identity without placing abilities in Limbo.
     */
    private fun TransferResult.withoutStackAbilities(resolvedIids: Set<Int>): TransferResult {
        if (resolvedIids.isEmpty()) return this
        val updatedObjects = patchedObjects.filterNot { it.instanceId in resolvedIids }
        if (updatedObjects.size == patchedObjects.size) return this
        val updatedZones =
            patchedZones.map { zone ->
                if (zone.zoneId != ZoneIds.STACK) {
                    zone
                } else {
                    zone
                        .toBuilder()
                        .clearObjectInstanceIds()
                        .addAllObjectInstanceIds(zone.objectInstanceIdsList.filterNot { it in resolvedIids })
                        .build()
                }
            }
        return copy(patchedObjects = updatedObjects, patchedZones = updatedZones)
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
                    uniqueAbilityId = bridge.activeEffectPlanner().effects.nextEffectId(),
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
        annotationJournal: ProjectionAnnotationJournal.Planner,
    ): Set<ForgeCardId> {
        val ctx =
            AnnotationContext(
                bridge = bridge,
                snap = snap,
                frameIds = frameIds,
                events = events,
                abilityExhaustionFacts = AbilityExhaustionFacts(),
            )
        val visibleThisGsm = annotationJournal.activeDecayedCleanupSources().toMutableSet()
        val addedThisGsm = linkedSetOf<ForgeCardId>()
        for (ev in events) {
            if (ev is GameEvent.SpellResolved) {
                val cleanupGrpId = PersistentFeedBuilder.decayedCleanupGrpIdForSource(ev.cardId, snap, bridge, transferResult)
                val abilityGrpId =
                    ev.abilityGrpId.takeIf { it != 0 }
                        ?: ctx.abilityGrpIdForSource(ev.cardId)
                if (ev.isTrigger && cleanupGrpId != null && abilityGrpId == KeywordAbilityIds.DECAYED) {
                    annotationJournal.recordDecayedCleanupSource(ev.cardId)
                    visibleThisGsm.add(ev.cardId)
                    addedThisGsm.add(ev.cardId)
                }
            } else if (ev is GameEvent.SpellCast) {
                val cleanupGrpId = PersistentFeedBuilder.decayedCleanupGrpIdForSource(ev.cardId, snap, bridge, transferResult)
                val abilityGrpId =
                    ev.abilityGrpId.takeIf { it != 0 }
                        ?: ctx.abilityGrpIdForSource(ev.cardId)
                if (ev.isTrigger && cleanupGrpId != null && abilityGrpId == cleanupGrpId) {
                    annotationJournal.clearDecayedCleanupSource(ev.cardId)
                    if (ev.cardId !in addedThisGsm) visibleThisGsm.remove(ev.cardId)
                }
            } else if (ev is GameEvent.CardSacrificed) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, annotationJournal)
            } else if (ev is GameEvent.CardDestroyed) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, annotationJournal)
            } else if (ev is GameEvent.CardBounced) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, annotationJournal)
            } else if (ev is GameEvent.CardExiled) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, annotationJournal)
            } else if (ev is GameEvent.ZoneChanged) {
                if (ev.from == leyline.game.event.Zone.Battlefield && ev.to != leyline.game.event.Zone.Battlefield) {
                    clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, annotationJournal)
                }
            }
        }
        return visibleThisGsm
    }

    private fun clearDecayedCleanupSource(
        sourceForgeId: ForgeCardId,
        addedThisGsm: Set<ForgeCardId>,
        visibleThisGsm: MutableSet<ForgeCardId>,
        annotationJournal: ProjectionAnnotationJournal.Planner,
    ) {
        annotationJournal.clearDecayedCleanupSource(sourceForgeId)
        if (sourceForgeId !in addedThisGsm) visibleThisGsm.remove(sourceForgeId)
    }

    private fun delayedTriggerAffectorReplacements(
        removedHolders: List<HolderRecord>,
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): Map<Int, Int> {
        val available =
            snap.stack.entries
                .filterNot { it.isSpell }
                .toMutableList()
        return buildMap {
            for (holder in removedHolders) {
                val matchIndex =
                    available.indexOfFirst { entry ->
                        if (holder.runtimeTriggerId != null) {
                            entry.runtimeTriggerId == holder.runtimeTriggerId
                        } else {
                            holder.sourceForgeCardId != null &&
                                entry.forgeCardId == holder.sourceForgeCardId &&
                                entry.grpId == holder.cleanupGrpId
                        }
                    }
                if (matchIndex < 0) continue
                val entry = available.removeAt(matchIndex)
                val abilityIid =
                    if (entry.forgeAbilityId != 0) {
                        frameIds.triggerStackAbilityIid(entry.forgeAbilityId)
                    } else {
                        frameIds.stackAbilityIid(entry.forgeCardId)
                    }
                put(holder.iid, abilityIid.value)
            }
        }
    }

    private fun delayedTriggerHoldersAwaitingLiveAbility(
        activeHolders: List<HolderRecord>,
        currentHolders: List<HolderRecord>,
        activeAnnotations: Collection<AnnotationInfo>,
        snap: GsmSnapshot,
        bridge: GameBridge,
    ): List<HolderRecord> {
        val currentIids = currentHolders.map { it.iid }.toSet()
        val battlefieldCards =
            snap.zones[ZoneIds.BATTLEFIELD]
                ?.contents
                .orEmpty()
                .toSet()
        return activeHolders.filter { holder ->
            if (holder.iid in currentIids || holder.runtimeTriggerId == null) return@filter false
            val liveAbilityPresent = snap.stack.entries.any { it.runtimeTriggerId == holder.runtimeTriggerId }
            if (liveAbilityPresent) return@filter false
            activeAnnotations
                .firstOrNull {
                    DelayedTriggerAffecteesKind.matches(it) && it.affectorId == holder.iid
                }?.affectedIdsList
                ?.mapNotNull { iid -> bridge.getForgeCardId(InstanceId(iid)) }
                ?.any { it !in battlefieldCards } == true
        }
    }

    /** Classify stale reveal state without touching the prompt journal. */
    private fun detectActiveReveal(
        promptFacts: PromptProjectionFacts,
        revealProxiesEmpty: Boolean,
    ): Pair<PromptSideEffect.RevealStarted?, List<PromptProjectionFacts.RevealFact>> {
        val reveal = promptFacts.activeReveal ?: return null to emptyList()
        return if (!revealProxiesEmpty && !reveal.hasPendingPrompt) {
            null to listOf(reveal)
        } else {
            reveal.reveal to emptyList()
        }
    }

    /** Synthesize short-lived RevealedCard views for semantic reveal events and
     * keep them alive while a reveal-choose prompt remains active. */
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
        val eventReveals =
            events.filterIsInstance<GameEvent.CardsRevealed>().filter { it.viewerSeatId != it.ownerSeatId }
        val revealFacts =
            buildList {
                eventReveals.forEach { reveal ->
                    reveal.cardIds.forEach {
                        add(
                            Triple(
                                it,
                                reveal.ownerSeatId.value,
                                reveal.sourceZone?.let { zone ->
                                    ZoneIds.revealZone(zone, reveal.ownerSeatId)
                                },
                            ),
                        )
                    }
                }
                activeReveal?.allHandCardIds?.forEach { cardId ->
                    add(Triple(cardId, activeReveal.ownerSeatId.value, ZoneIds.handOf(activeReveal.ownerSeatId)))
                }
            }.distinctBy { it.first }

        if (revealFacts.isNotEmpty()) {
            val retiredViews = bridge.revealProxies.retain(revealFacts.mapTo(mutableSetOf()) { it.first })
            if (retiredViews.isNotEmpty()) events.add(GameEvent.RevealProxiesDeleted(retiredViews))
            for ((ownerSeat, ownerFacts) in revealFacts.groupBy { it.second }) {
                val viewerSeat = SeatId(ownerSeat).opponent.value
                val revealedZoneId = ZoneIds.revealedOf(ownerSeat)
                val revealedZoneIdx = zones.indexOfFirst { it.zoneId == revealedZoneId }
                val revealedZoneBuilder =
                    if (revealedZoneIdx >= 0) {
                        zones.removeAt(revealedZoneIdx).toBuilder()
                    } else {
                        ZoneMapper.makeZone(revealedZoneId, ZoneType.Revealed, ownerSeat, Visibility.Public).toBuilder()
                    }

                for ((forgeCardId, _, sourceZoneId) in ownerFacts) {
                    val cardSnap = snap.objects[forgeCardId] ?: continue
                    val proxyId =
                        bridge.revealProxies.lookup(forgeCardId) ?: run {
                            val id = bridge.ids.allocSynthetic()
                            bridge.revealProxies.allocate(forgeCardId, id)
                            id
                        }
                    revealedZoneBuilder.addObjectInstanceIds(proxyId.value)
                    gameObjects.add(
                        ObjectMapper.buildRevealedCardProxy(
                            cardSnap,
                            proxyId.value,
                            sourceZoneId
                                ?: snap.zones.values
                                    .firstOrNull { forgeCardId in it.contents }
                                    ?.id
                                ?: ZoneIds.handOf(ownerSeat),
                            ownerSeat,
                            viewerSeat,
                            bridge.cardProto,
                            parentLinkage = snap.boundCards[forgeCardId]?.parentLinkage,
                        ),
                    )
                }
                zones.add(revealedZoneBuilder.build())
            }
        } else if (!bridge.revealProxies.isEmpty) {
            // Reveal ended — emit cleanup annotations and clear tracking.
            // Diff naturally detects missing proxy objects via snapshot-compare.
            val deletedProxies = bridge.revealProxies.drain()
            events.add(GameEvent.RevealProxiesDeleted(deletedProxies))
        }
    }
}
