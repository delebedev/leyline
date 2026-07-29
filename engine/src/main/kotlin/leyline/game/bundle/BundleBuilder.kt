package leyline.game.bundle

import forge.game.Game
import forge.game.phase.PhaseType
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.SelectNPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.CombatDeclarationFacts
import leyline.game.EngineObservation
import leyline.game.NaiveGsmAction
import leyline.game.PlaybackYield
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationLossReason
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.PlayerMapper
import leyline.game.mapping.PriorityActionProjector
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ShouldStopEvaluator
import leyline.game.mapping.StateMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.PersistentAnnotationState
import leyline.game.state.BridgeMutations
import leyline.game.state.GameBridge
import leyline.game.state.HolderBatch
import leyline.game.state.HolderRecord
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Compiles and commits GRE message bundles for each flow milestone.
 *
 * Frame computation reads one snapshot and returns an immutable [FramePlan].
 * [commit] is the only consumer that applies ordering-sensitive bridge
 * mutations and advances the shared projection baseline and message counter.
 *
 * Captures a [GsmSnapshot] at entry; every stage reads from the snapshot.
 *
 * **Update types** (what the client does with each GSM):
 * - [GameStateUpdate.SendAndRecord] — checkpoint; client persists state.
 *   Always precedes [ActionsAvailableReq] at human decision points.
 * - [GameStateUpdate.SendHiFi] — animation-quality intermediate. AI actions,
 *   phase echoes, combat toggles. Client animates but doesn't save.
 * - [GameStateUpdate.Send] — speculative/transient. Targeting, selection
 *   prompts. Client may discard on undo/cancel.
 *
 * **pendingMessageCount:** when 1, tells the client another message follows
 * in the same logical batch (GSM + request pair). Client defers processing
 * until both arrive. Omit for standalone GSMs (AI actions, echoes).
 *
 * Naming: `xxxBundle` → [BundleResult] (multi-message). Standalone helpers
 * ([queuedGameState], [edictalPass]) return single [GREToClientMessage].
 */
@Suppress("LargeClass") // coherent unit; split assessed 2026-04-05, marginal leverage
class BundleBuilder(
    private val bridge: GameBridge,
    private val matchId: String,
    val seatId: Int,
    /**
     * Cursor this builder updates after each bundle. Defaults to
     * [GameBridge.bundleCursor] so the session builder and the
     * [leyline.game.GamePlayback] builder share one baseline; tests can inject an
     * isolated cursor if they don't want the bridge state affected.
     */
    val cursor: BundleCursor = bridge.bundleCursor,
) {
    private val log = LoggerFactory.getLogger(BundleBuilder::class.java)

    fun stateOnlyDiff(counter: MessageCounter): BundleResult = stateOnlyDiff(bridge.requireGame(), counter)

    internal fun echoAttackersBundle(
        counter: MessageCounter,
        selectedAttackerIds: List<Int>,
        allLegalAttackerIds: List<Int>,
        selectedAttackAlternatives: Map<Int, Int> = emptyMap(),
        selectedDamageRecipients: Map<Int, DamageRecipient> = emptyMap(),
    ): BundleResult =
        echoAttackersBundle(
            bridge.requireGame(),
            counter,
            selectedAttackerIds,
            allLegalAttackerIds,
            selectedAttackAlternatives,
            selectedDamageRecipients,
        )

    fun declareAttackersBundle(
        counter: MessageCounter,
        prebuiltReq: DeclareAttackersReq? = null,
    ): BundleResult = declareAttackersBundle(bridge.requireGame(), counter, prebuiltReq)

    fun echoBlockersBundle(
        counter: MessageCounter,
        blockAssignments: Map<Int, Int>,
    ): BundleResult = echoBlockersBundle(bridge.requireGame(), counter, blockAssignments)

    fun buildDeclareBlockersReq(): DeclareBlockersReq = RequestBuilder.buildDeclareBlockersReq(bridge.requireGame(), SeatId(seatId), bridge)

    fun buildDeclareAttackersReq(facts: CombatDeclarationFacts): DeclareAttackersReq =
        RequestBuilder.buildDeclareAttackersReq(SeatId(seatId), facts, bridge)

    fun buildDeclareBlockersReq(facts: CombatDeclarationFacts): DeclareBlockersReq = RequestBuilder.buildDeclareBlockersReq(facts, bridge)

    fun declareBlockersBundle(counter: MessageCounter): BundleResult = declareBlockersBundle(bridge.requireGame(), counter)

    fun selectTargetsBundle(
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
    ): BundleResult = selectTargetsBundle(bridge.requireGame(), counter, prompt)

    internal fun selectTargetsBundle(
        snapshot: GsmSnapshot,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
    ): BundleResult = selectTargetsBundle(null, snapshot, counter, prompt)

    fun selectNBundle(
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
        route: SelectNPromptRoute,
        envelopeForReq: (SelectNReq) -> SelectNEnvelope,
    ): BundleResult = selectNBundle(bridge.requireGame(), counter, prompt, route, envelopeForReq)

    internal fun selectNBundle(
        snapshot: GsmSnapshot,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
        route: SelectNPromptRoute,
        envelopeForReq: (SelectNReq) -> SelectNEnvelope,
    ): BundleResult = selectNBundle(null, snapshot, counter, prompt, route, envelopeForReq)

    fun selectNBundle(
        counter: MessageCounter,
        envelope: SelectNEnvelope,
    ): BundleResult = selectNBundle(bridge.requireGame(), counter, envelope)

    internal fun selectNBundle(
        snapshot: GsmSnapshot,
        counter: MessageCounter,
        envelope: SelectNEnvelope,
    ): BundleResult = selectNBundle(null, snapshot, counter, envelope)

    fun orderBundle(
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
        kind: OrderRouteKind,
    ): BundleResult = orderBundle(bridge.requireGame(), counter, prompt, kind)

    internal fun orderBundle(
        snapshot: GsmSnapshot,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
        kind: OrderRouteKind,
    ): BundleResult = orderBundle(null, snapshot, counter, prompt, kind)

    fun castingTimeOptionsBundle(
        counter: MessageCounter,
        req: CastingTimeOptionsReq,
        sourceCardInstanceId: Int? = null,
        sourceCardGrpId: Int? = null,
    ): BundleResult =
        castingTimeOptionsBundle(
            bridge.requireGame(),
            counter,
            req,
            sourceCardInstanceId,
            sourceCardGrpId,
        )

    internal fun castingTimeOptionsBundle(
        snapshot: GsmSnapshot,
        counter: MessageCounter,
        req: CastingTimeOptionsReq,
        sourceCardInstanceId: Int? = null,
        sourceCardGrpId: Int? = null,
    ): BundleResult =
        castingTimeOptionsBundle(
            null,
            snapshot,
            counter,
            req,
            sourceCardInstanceId,
            sourceCardGrpId,
        )

    fun payCostsBundle(
        counter: MessageCounter,
        req: PayCostsReq,
        prompt: Prompt? = null,
        promptPersistentAnnotations: List<AnnotationInfo> = emptyList(),
    ): BundleResult =
        payCostsBundle(
            bridge.requireGame(),
            counter,
            req,
            prompt,
            promptPersistentAnnotations,
        )

    internal fun payCostsBundle(
        snapshot: GsmSnapshot,
        counter: MessageCounter,
        req: PayCostsReq,
        prompt: Prompt? = null,
        promptPersistentAnnotations: List<AnnotationInfo> = emptyList(),
    ): BundleResult =
        payCostsBundle(
            null,
            snapshot,
            counter,
            req,
            prompt,
            promptPersistentAnnotations,
        )

    data class BundleResult(
        val messages: List<GREToClientMessage>,
        val actionCatalog: ActionCatalogPlan? = null,
    )

    private data class FrameDiff(
        val gameStateId: Int,
        val snap: GsmSnapshot,
        val result: StateMapper.BuildResult,
        val events: FrameEventLog,
        val previousSnap: GsmSnapshot?,
        val idResolver: FrameIdResolver,
        val bundleFrameReservation: GameBridge.BundleFrameReservation?,
        val nextProjectionBaseline: GsmSnapshot = snap,
        val pendingSubmittedTargets: BundleCursor.PSuTPending? = null,
    )

    private data class FramePlanDraft(
        val delivery: BundleResult,
        val nextBaseline: GsmSnapshot,
        val mutations: BridgeMutations?,
        val nextAnnotationId: Int? = null,
        val pendingSubmittedTargets: BundleCursor.PSuTPending? = null,
        val pendingOrderZoneMove: InteractivePromptBridge.PendingOrderZoneMove? = null,
        val bundleFrameReservation: GameBridge.BundleFrameReservation? = null,
        val observation: FramePlan.DiffObservation? = null,
    )

    private data class FrameProjectionBaseline(
        val previousSnap: GsmSnapshot?,
        val snapTemplate: GsmSnapshot? = null,
        val delayedTriggerHolders: List<HolderRecord>? = null,
        val transientLinkedFaceFamilyIds: Set<InstanceId>? = null,
    )

    private fun observedFrameBaseline(snapshot: GsmSnapshot?): FrameProjectionBaseline =
        FrameProjectionBaseline(
            previousSnap = cursor.lastSent,
            snapTemplate = snapshot,
        )

    private fun buildFrameDraft(
        @Suppress("CanBeNonNullable")
        game: Game?,
        counter: MessageCounter,
        revealForSeat: Int? = null,
        eventsOverride: FrameEventLog? = null,
        bundleFrameReservationOverride: GameBridge.BundleFrameReservation? = null,
        projectionBaseline: FrameProjectionBaseline = FrameProjectionBaseline(cursor.lastSent),
        updateType: (GsmSnapshot, FrameEventLog) -> GameStateUpdate,
    ): FrameDiff {
        val nextGs = counter.nextGsId()
        val snap =
            projectionBaseline.snapTemplate?.withGameStateId(nextGs)
                ?: GsmSnapshot.capture(checkNotNull(game), bridge, matchId, nextGs)
        val bundleFrameReservation =
            when {
                bundleFrameReservationOverride != null -> bundleFrameReservationOverride
                eventsOverride == null -> bridge.reserveBundleFrame(seatId)
                else -> null
            }
        val events = eventsOverride ?: checkNotNull(bundleFrameReservation).events
        val previousSnap = projectionBaseline.previousSnap
        val draft =
            StateMapper.buildDiff(
                previousSnap,
                snap,
                events,
                nextGs,
                matchId,
                bridge,
                updateType = updateType(snap, events),
                viewingSeatId = seatId,
                revealForSeat = revealForSeat,
                delayedTriggerHolderBaseline = projectionBaseline.delayedTriggerHolders,
                transientLinkedFaceFamilyBaseline = projectionBaseline.transientLinkedFaceFamilyIds,
            )
        val plannedReallocationIids =
            draft.mutations.idReallocations
                .mapNotNull { reallocation ->
                    bridge.getForgeCardId(reallocation.old)?.let { forgeCardId ->
                        forgeCardId to reallocation.new
                    }
                }.toMap()
        return FrameDiff(
            gameStateId = nextGs,
            snap = snap,
            result = draft,
            events = events,
            previousSnap = previousSnap,
            idResolver =
                checkNotNull(draft.annotationFrameDraft)
                    .idResolver
                    .withPostReallocIids(plannedReallocationIids),
            bundleFrameReservation = bundleFrameReservation,
        )
    }

    private fun buildFrameDiff(
        game: Game?,
        counter: MessageCounter,
        revealForSeat: Int? = null,
        eventsOverride: FrameEventLog? = null,
        bundleFrameReservationOverride: GameBridge.BundleFrameReservation? = null,
        projectionBaseline: FrameProjectionBaseline = FrameProjectionBaseline(cursor.lastSent),
        includePendingPlayerSubmittedTargets: Boolean = false,
        annotationRiders: (GsmSnapshot, FrameIdResolver) -> List<AnnotationInfo> = { _, _ -> emptyList() },
        updateType: (GsmSnapshot, FrameEventLog) -> GameStateUpdate,
    ): FrameDiff {
        val frame =
            buildFrameDraft(
                game,
                counter,
                revealForSeat,
                eventsOverride,
                bundleFrameReservationOverride,
                projectionBaseline,
                updateType,
            )
        val pendingSubmittedTargets =
            if (includePendingPlayerSubmittedTargets) {
                cursor.pendingPSuT()
            } else {
                null
            }
        val frameDraft = checkNotNull(frame.result.annotationFrameDraft)
        val riders = annotationRiders(frame.snap, frameDraft.idResolver).toMutableList()
        pendingSubmittedTargets?.let { pending ->
            riders += AnnotationBuilder.playerSubmittedTargets(pending.spellInstanceId, pending.casterSeatId)
        }
        return finalizeFrameDiff(frame, riders, pendingSubmittedTargets)
    }

    private fun finalizeFrameDiff(
        frame: FrameDiff,
        riders: List<AnnotationInfo>,
        pendingSubmittedTargets: BundleCursor.PSuTPending? = null,
        cursorSnap: GsmSnapshot = frame.snap,
    ): FrameDiff {
        val result = finalizeStateFrame(frame.result, riders, pendingSubmittedTargets)
        return frame.copy(
            result = result,
            nextProjectionBaseline = cursorSnap,
            pendingSubmittedTargets = pendingSubmittedTargets,
        )
    }

    internal fun finalizeStateFrame(
        draft: StateMapper.BuildResult,
        riders: List<AnnotationInfo>,
        pendingSubmittedTargets: BundleCursor.PSuTPending? = null,
    ): StateMapper.BuildResult {
        if (pendingSubmittedTargets == null) return draft.finalizeAnnotations(riders)

        return synchronized(cursor) {
            check(cursor.pendingPSuT() == pendingSubmittedTargets) {
                "Pending PlayerSubmittedTargets changed during frame assembly"
            }
            draft.finalizeAnnotations(riders)
        }
    }

    private fun compilePlan(
        counter: MessageCounter,
        compile: (MessageCounter) -> FramePlanDraft,
    ): FramePlan {
        val plannedCounter = MessageCounter.fork(counter.snapshot())
        return compilePlanOn(plannedCounter, compile)
    }

    private fun compilePlanOn(
        plannedCounter: MessageCounter,
        compile: (MessageCounter) -> FramePlanDraft,
    ): FramePlan {
        val counterBefore = plannedCounter.snapshot()
        val draft = compile(plannedCounter)
        return FramePlan(
            messages = draft.delivery.messages.toList(),
            actionCatalog = draft.delivery.actionCatalog,
            projection =
                FramePlan.ProjectionCommit(
                    counterBefore = counterBefore,
                    counterAfter = plannedCounter.snapshot(),
                    nextBaseline = draft.nextBaseline,
                    mutations = draft.mutations,
                    nextAnnotationId = draft.nextAnnotationId,
                    pendingSubmittedTargets = draft.pendingSubmittedTargets,
                    pendingOrderZoneMove = draft.pendingOrderZoneMove,
                    bundleFrameReservation = draft.bundleFrameReservation,
                    observation = draft.observation,
                ),
        )
    }

    private fun compileAndCommit(
        counter: MessageCounter,
        releaseReservationOnFailure: Boolean = true,
        projectBaselineOnCommit: Boolean = false,
        compile: () -> FramePlan,
    ): BundleResult =
        counter.withAllocationLock {
            commit(compile(), counter, releaseReservationOnFailure, projectBaselineOnCommit)
        }

    private fun FrameDiff.planDraft(
        delivery: BundleResult,
        mutations: BridgeMutations? = result.mutations,
        pendingOrderZoneMove: InteractivePromptBridge.PendingOrderZoneMove? = null,
    ): FramePlanDraft =
        FramePlanDraft(
            delivery = delivery,
            nextBaseline = nextProjectionBaseline,
            mutations = mutations,
            pendingSubmittedTargets = pendingSubmittedTargets,
            pendingOrderZoneMove = pendingOrderZoneMove,
            bundleFrameReservation = bundleFrameReservation,
            observation =
                FramePlan.DiffObservation(
                    previous = previousSnap,
                    current = snap,
                    events = events,
                    gameStateId = gameStateId,
                    message = result.gsm,
                ),
        )

    /**
     * Atomically commits one successfully compiled frame plan.
     *
     * Lifecycle baseline seeds outside normal bundle construction remain named
     * exceptions in the session, connection, and debug setup paths.
     */
    internal fun commit(
        plan: FramePlan,
        counter: MessageCounter,
        releaseReservationOnFailure: Boolean = true,
        projectBaselineOnCommit: Boolean = false,
    ): BundleResult {
        val projection = plan.projection
        val reservation = projection.bundleFrameReservation
        try {
            counter.commitAllocation(projection.counterBefore, projection.counterAfter) {
                val commitProjection: () -> Unit = {
                    synchronized(cursor) {
                        projection.pendingSubmittedTargets?.let { pending ->
                            val currentPending = cursor.pendingPSuT()
                            check(currentPending == pending) {
                                "Pending PlayerSubmittedTargets changed before projection commit: " +
                                    "expected=$pending, actual=$currentPending"
                            }
                        }
                        projection.pendingOrderZoneMove?.let { pending ->
                            check(
                                bridge.promptBridge(SeatId(seatId)).pendingOrderZoneMove(
                                    pending.seatId,
                                    pending.forgeCardIds,
                                ) == pending,
                            ) {
                                "Pending order zone move changed before projection commit"
                            }
                        }
                        projection.observation?.let { observation ->
                            bridge.diffListener?.invoke(
                                observation.previous,
                                observation.current,
                                observation.events,
                                observation.gameStateId,
                                observation.message,
                            )
                        }
                        projection.mutations?.let(bridge::applyMutations)
                        if (projection.mutations == null) {
                            projection.nextAnnotationId?.let(bridge.annotations::setAnnotationId)
                        }
                        cursor.lastSent =
                            if (projectBaselineOnCommit) {
                                projection.projectedSnapshot()
                            } else {
                                projection.nextBaseline
                            }
                        projection.pendingSubmittedTargets?.let(cursor::consumePSuT)
                        projection.pendingOrderZoneMove?.let {
                            bridge.promptBridge(SeatId(seatId)).consumePendingOrderZoneMove(it)
                        }
                    }
                }
                val commitFrame = {
                    val catalog = plan.actionCatalog
                    if (catalog == null) {
                        commitProjection()
                    } else {
                        commitActionCatalog(catalog, commitProjection)
                    }
                }
                if (reservation != null) {
                    bridge.commitBundleFrame(reservation, commitFrame)
                } else {
                    commitFrame()
                }
            }
        } catch (failure: Throwable) {
            if (releaseReservationOnFailure) {
                reservation?.let(bridge::releaseBundleFrame)
            }
            throw failure
        }
        return plan.delivery()
    }

    private fun commitComposite(
        plans: List<FramePlan>,
        counter: MessageCounter,
        reservation: GameBridge.BundleFrameReservation,
        releaseReservationOnFailure: Boolean = true,
        projectBaselineOnCommit: Boolean = false,
    ): List<BundleResult> {
        check(plans.isNotEmpty()) { "Composite frame requires at least one plan" }
        plans.zipWithNext().forEach { (current, next) ->
            check(current.projection.counterAfter == next.projection.counterBefore) {
                "Composite frame counter allocations are not contiguous"
            }
        }
        val first = plans.first().projection
        val last = plans.last().projection
        try {
            counter.commitAllocation(first.counterBefore, last.counterAfter) {
                val commitProjection: () -> Unit = {
                    synchronized(cursor) {
                        plans.forEach { plan ->
                            val projection = plan.projection
                            projection.pendingSubmittedTargets?.let { pending ->
                                check(cursor.pendingPSuT() == pending) {
                                    "Pending PlayerSubmittedTargets changed before composite projection commit"
                                }
                            }
                            projection.pendingOrderZoneMove?.let { pending ->
                                check(
                                    bridge.promptBridge(SeatId(seatId)).pendingOrderZoneMove(
                                        pending.seatId,
                                        pending.forgeCardIds,
                                    ) == pending,
                                ) {
                                    "Pending order zone move changed before composite projection commit"
                                }
                            }
                        }
                        plans.forEach { plan ->
                            plan.projection.observation?.let { observation ->
                                bridge.diffListener?.invoke(
                                    observation.previous,
                                    observation.current,
                                    observation.events,
                                    observation.gameStateId,
                                    observation.message,
                                )
                            }
                        }
                        plans.forEach { plan ->
                            val projection = plan.projection
                            projection.mutations?.let(bridge::applyMutations)
                            if (projection.mutations == null) {
                                projection.nextAnnotationId?.let(bridge.annotations::setAnnotationId)
                            }
                            cursor.lastSent =
                                if (projectBaselineOnCommit) {
                                    projection.projectedSnapshot()
                                } else {
                                    projection.nextBaseline
                                }
                            projection.pendingSubmittedTargets?.let(cursor::consumePSuT)
                            projection.pendingOrderZoneMove?.let {
                                bridge.promptBridge(SeatId(seatId)).consumePendingOrderZoneMove(it)
                            }
                        }
                    }
                }
                val catalog = plans.asReversed().mapNotNull(FramePlan::actionCatalog).firstOrNull()
                val commitFrame = {
                    if (catalog == null) {
                        commitProjection()
                    } else {
                        commitActionCatalog(catalog, commitProjection)
                    }
                }
                bridge.commitBundleFrame(reservation, commitFrame)
            }
        } catch (failure: Throwable) {
            if (releaseReservationOnFailure) {
                bridge.releaseBundleFrame(reservation)
            }
            throw failure
        }
        return plans.map(FramePlan::delivery)
    }

    private fun commitActionCatalog(
        plan: ActionCatalogPlan,
        commit: () -> Unit,
    ) {
        check(plan.offers.isNotEmpty()) { "Cannot expose priority actions without bound engine tokens" }
        val actionBridge = bridge.seat(SeatId(seatId)).action
        check(actionBridge.commitActionCatalog(plan.actionId, plan.gameStateId, plan.offers, commit)) {
            val current = actionBridge.getPending()
            "Cannot bind priority actions to pending window ${plan.actionId.take(8)} " +
                "(current=${current?.actionId?.take(8)}, completed=${!actionBridge.isPendingActive(plan.actionId)}, " +
                "offers=${plan.offers.size})"
        }
    }

    private fun FramePlan.ProjectionCommit.projectedSnapshot(): GsmSnapshot {
        val currentState = nextBaseline.persistentAnnotationState
        val projectedState =
            mutations?.let { batch ->
                PersistentAnnotationState(
                    activeAnnotations = batch.persistentBatch.allAnnotations.associateBy { it.id },
                    nextAnnotationId = checkNotNull(batch.nextAnnotationId),
                    nextPersistentId = batch.persistentBatch.nextPersistentId,
                )
            } ?: nextAnnotationId?.let { currentState.copy(nextAnnotationId = it) }
                ?: currentState
        return nextBaseline.withPersistentAnnotationState(projectedState)
    }

    private fun List<HolderRecord>.apply(batch: HolderBatch): List<HolderRecord> {
        val projected = associateByTo(linkedMapOf(), HolderRecord::iid)
        batch.removed.forEach(projected::remove)
        batch.added.forEach { projected[it.iid] = it }
        return projected.values.toList()
    }

    private fun FrameProjectionBaseline.advance(projection: FramePlan.ProjectionCommit): FrameProjectionBaseline {
        val mutations = projection.mutations
        return FrameProjectionBaseline(
            previousSnap = projection.nextBaseline,
            snapTemplate = projection.projectedSnapshot(),
            delayedTriggerHolders =
                mutations?.let { delayedTriggerHolders?.apply(it.holderBatch) }
                    ?: delayedTriggerHolders,
            transientLinkedFaceFamilyIds =
                mutations?.nextTransientLinkedFaceFamilyIds
                    ?: transientLinkedFaceFamilyIds,
        )
    }

    private fun BridgeMutations.requiresCommittedBridgeState(): Boolean =
        idReallocations.isNotEmpty() ||
            retiredIds.isNotEmpty() ||
            zoneRecordings.any { (instanceId, zoneId) ->
                bridge.getPreviousZone(instanceId)?.let { it != zoneId } == true
            } ||
            consumedTargetSpecs.isNotEmpty()

    private fun GsmSnapshot.hasCardZoneDelta(next: GsmSnapshot): Boolean {
        val previousZonesByCard =
            zones.values
                .flatMap { zone -> zone.contents.map { forgeCardId -> forgeCardId to zone.id } }
                .toMap()
        val nextZonesByCard =
            next.zones.values
                .flatMap { zone -> zone.contents.map { forgeCardId -> forgeCardId to zone.id } }
                .toMap()
        return (previousZonesByCard.keys + nextZonesByCard.keys).any { forgeCardId ->
            previousZonesByCard[forgeCardId] != nextZonesByCard[forgeCardId]
        }
    }

    private fun GsmSnapshot.withGameStateId(gameStateId: Int): GsmSnapshot =
        copyProjection(gameStateId = gameStateId, persistentAnnotationState = persistentAnnotationState)

    private fun GsmSnapshot.withPersistentAnnotationState(state: PersistentAnnotationState): GsmSnapshot =
        copyProjection(gameStateId = gameStateId, persistentAnnotationState = state)

    private fun GsmSnapshot.withOwnerPersistentAnnotationState(): GsmSnapshot {
        val state =
            PersistentAnnotationState(
                activeAnnotations = bridge.annotations.snapshot(),
                nextAnnotationId = bridge.annotations.currentAnnotationId(),
                nextPersistentId = bridge.annotations.currentPersistentId(),
            )
        return if (state == persistentAnnotationState) this else withPersistentAnnotationState(state)
    }

    private fun List<GREToClientMessage>.withLifeTotals(lifeTotals: Map<Int, Int>): List<GREToClientMessage> {
        if (lifeTotals.isEmpty()) return this
        return mapIndexed { index, message ->
            if (index != 0 || !message.hasGameStateMessage()) return@mapIndexed message
            val gsm = message.gameStateMessage
            val players =
                gsm.playersList.map { player ->
                    lifeTotals[player.systemSeatNumber]
                        ?.let { life -> player.toBuilder().setLifeTotal(life).build() }
                        ?: player
                }
            message
                .toBuilder()
                .setGameStateMessage(
                    gsm
                        .toBuilder()
                        .clearPlayers()
                        .addAllPlayers(players),
                ).build()
        }
    }

    private fun GsmSnapshot.copyProjection(
        gameStateId: Int,
        persistentAnnotationState: PersistentAnnotationState,
    ): GsmSnapshot =
        GsmSnapshot(
            matchId = matchId,
            gameStateId = gameStateId,
            seats = seats,
            zones = zones,
            boundCards = boundCards,
            stack = stack,
            phase = phase,
            combat = combat,
            abilityWordEntries = abilityWordEntries,
            pendingTriggers = pendingTriggers,
            combatQualifications = combatQualifications,
            persistentAnnotationState = persistentAnnotationState,
            capturedAt = capturedAt,
            dayTime = dayTime,
            activePlayerSpellsCastThisTurn = activePlayerSpellsCastThisTurn,
        )

    /**
     * Post-action state bundle:
     *   GRE 1: Diff GameStateMessage with embedded actions (only changed zones/objects)
     *   GRE 2: ActionsAvailableReq
     */
    internal fun postAction(
        game: Game,
        counter: MessageCounter,
        revealForSeat: Int? = null,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePostAction(bridge.materializeEngineObservation(game), counter, revealForSeat)
        }

    internal fun postAction(
        observation: EngineObservation,
        counter: MessageCounter,
        revealForSeat: Int? = null,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePostAction(observation, counter, revealForSeat)
        }

    internal fun compilePostAction(
        game: Game,
        counter: MessageCounter,
        revealForSeat: Int? = null,
    ): FramePlan = compilePostAction(bridge.materializeEngineObservation(game), counter, revealForSeat)

    private fun compilePostAction(
        observation: EngineObservation,
        counter: MessageCounter,
        revealForSeat: Int?,
    ): FramePlan =
        compilePlan(counter) { plannedCounter ->
            val diff =
                buildFrameDiff(
                    game = null,
                    counter = plannedCounter,
                    revealForSeat = revealForSeat,
                    projectionBaseline = FrameProjectionBaseline(cursor.lastSent, snapTemplate = observation.snapshot),
                    includePendingPlayerSubmittedTargets = true,
                ) { snap, events ->
                    if (isTurnOrTriggerDraw(events.events, snap, snap.phase.activePlayer)) {
                        GameStateUpdate.SendHiFi
                    } else {
                        StateMapper.resolveUpdateType(snap, seatId)
                    }
                }
            val nextGs = diff.gameStateId
            val frame = GsmFrame.from(diff.snap)
            val window = observation.preparedPriorityWindows[SeatId(seatId)]
            val projection = buildPriorityProjection(observation, idResolver = diff.idResolver::cardIid)
            val gs = GsmBuilder.embedActions(diff.result.gsm, projection.actions, frame, recipientSeatId = seatId)
            val messages =
                listOf(
                    makeGRE(GREMessageType.GameStateMessage_695e, nextGs, plannedCounter.nextMsgId()) {
                        it.gameStateMessage = gs
                    },
                ) + coinFlipPromptMessages(diff.events.events, nextGs, plannedCounter) +
                    listOf(
                        makeGRE(GREMessageType.ActionsAvailableReq_695e, nextGs, plannedCounter.nextMsgId()) {
                            it.actionsAvailableReq = projection.actions
                            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                        },
                    )

            diff.planDraft(
                BundleResult(
                    messages,
                    window?.let { ActionCatalogPlan(it.actionId, nextGs, projection.offers) },
                ),
            )
        }

    /**
     * State-only diff: Diff GameStateMessage without ActionsAvailableReq.
     * Used to show intermediate state (e.g. spell on stack) without
     * prompting the client for a response.
     */
    fun stateOnlyDiff(
        game: Game,
        counter: MessageCounter,
    ): BundleResult =
        compileAndCommit(counter) {
            compileStateOnlyDiff(game, counter)
        }

    fun stateOnlyDiff(
        snapshot: GsmSnapshot,
        counter: MessageCounter,
    ): BundleResult =
        compileAndCommit(counter) {
            compileStateOnlyDiff(snapshot, counter)
        }

    internal fun compileStateOnlyDiff(
        game: Game,
        counter: MessageCounter,
    ): FramePlan =
        compilePlan(counter) { plannedCounter ->
            val diff =
                buildFrameDiff(game, plannedCounter, includePendingPlayerSubmittedTargets = true) { snap, _ ->
                    StateMapper.resolveUpdateType(snap, seatId)
                }
            val nextGs = diff.gameStateId
            val gs = diff.result.gsm
            val messages =
                listOf(
                    makeGRE(GREMessageType.GameStateMessage_695e, nextGs, plannedCounter.nextMsgId()) {
                        it.gameStateMessage = gs
                    },
                ) + coinFlipPromptMessages(diff.events.events, nextGs, plannedCounter) +
                    listOf(
                        buildEchoDiffGsm(plannedCounter, gs.update, previousGsId = gs.gameStateId),
                    )

            diff.planDraft(BundleResult(messages))
        }

    private fun compileStateOnlyDiff(
        snapshot: GsmSnapshot,
        counter: MessageCounter,
    ): FramePlan =
        compilePlan(counter) { plannedCounter ->
            val diff =
                buildFrameDiff(
                    game = null,
                    counter = plannedCounter,
                    projectionBaseline = FrameProjectionBaseline(cursor.lastSent, snapTemplate = snapshot),
                    includePendingPlayerSubmittedTargets = true,
                ) { snap, _ ->
                    StateMapper.resolveUpdateType(snap, seatId)
                }
            val nextGs = diff.gameStateId
            val gs = diff.result.gsm
            val messages =
                listOf(
                    makeGRE(GREMessageType.GameStateMessage_695e, nextGs, plannedCounter.nextMsgId()) {
                        it.gameStateMessage = gs
                    },
                ) + coinFlipPromptMessages(diff.events.events, nextGs, plannedCounter) +
                    listOf(
                        buildEchoDiffGsm(plannedCounter, gs.update, previousGsId = gs.gameStateId),
                    )

            diff.planDraft(BundleResult(messages))
        }

    /**
     * Remote action diff: content GS Diff with SendHiFi, then a bare SendHiFi echo.
     *
     * Client expects a commit-frame echo after remote-seat content GSMs.
     * Both messages are standalone (no pendingMessageCount). The first carries
     * the state delta + naive actions; the second is a bare diff (empty
     * anns/pAnns/objects/zones with prevGsId chained to the content frame)
     * used for animation pacing.
     */
    fun remoteActionDiff(
        game: Game,
        counter: MessageCounter,
        turnStarted: Boolean = false,
        eventsOverride: FrameEventLog? = null,
    ): BundleResult =
        compileAndCommit(counter) {
            compileRemoteActionDiff(game, counter, turnStarted, eventsOverride)
        }

    internal fun remoteActionDiff(
        game: Game,
        counter: MessageCounter,
        turnStarted: Boolean,
        eventsOverride: FrameEventLog,
        bundleFrameReservation: GameBridge.BundleFrameReservation,
    ): BundleResult =
        compileAndCommit(counter) {
            compileRemoteActionDiff(
                game,
                counter,
                turnStarted,
                eventsOverride,
                bundleFrameReservation,
            )
        }

    internal fun remoteActionDiffSequence(
        game: Game,
        counter: MessageCounter,
        eventFrames: List<FrameEventLog>,
        bundleFrameReservation: GameBridge.BundleFrameReservation,
    ): List<BundleResult> =
        counter.withAllocationLock {
            fun compileUnsplit(): List<BundleResult> {
                val fallbackCounter = MessageCounter.fork(counter.snapshot())
                val plan =
                    compilePlanOn(fallbackCounter) { sequenceCounter ->
                        buildRemoteActionDraft(
                            game = game,
                            plannedCounter = sequenceCounter,
                            eventsOverride = bundleFrameReservation.events,
                        )
                    }
                return commitComposite(listOf(plan), counter, bundleFrameReservation)
            }

            if (
                eventFrames.size > 1 &&
                (
                    bundleFrameReservation.events.zoneMoves.isNotEmpty() ||
                        bridge.snapshotPendingTargetSpecs().isNotEmpty() ||
                        cursor.lastSent?.hasCardZoneDelta(
                            GsmSnapshot.capture(game, bridge, matchId, counter.currentGsId()),
                        ) == true
                )
            ) {
                return@withAllocationLock compileUnsplit()
            }

            val plannedCounter = MessageCounter.fork(counter.snapshot())
            var projectionBaseline =
                FrameProjectionBaseline(
                    previousSnap = cursor.lastSent,
                    delayedTriggerHolders = bridge.delayedTriggerHolders.activeRecords(),
                    transientLinkedFaceFamilyIds = bridge.pendingTransientLinkedFaceFamilyIds(),
                )
            val plans = mutableListOf<FramePlan>()
            eventFrames.forEachIndexed { index, events ->
                val plan =
                    compilePlanOn(plannedCounter) { sequenceCounter ->
                        buildRemoteActionDraft(
                            game = game,
                            plannedCounter = sequenceCounter,
                            eventsOverride = events,
                            projectionBaseline = projectionBaseline,
                            includePendingSubmittedTargets = index == 0,
                        )
                    }
                if (
                    index < eventFrames.lastIndex &&
                    plan.projection.mutations?.requiresCommittedBridgeState() == true
                ) {
                    return@withAllocationLock compileUnsplit()
                }
                projectionBaseline = projectionBaseline.advance(plan.projection)
                plans += plan
            }
            commitComposite(plans, counter, bundleFrameReservation)
        }

    internal fun playbackYield(
        input: PlaybackYield,
        counter: MessageCounter,
    ): List<BundleResult> {
        if (input.combatFrames.isEmpty()) {
            return listOf(
                compileAndCommit(
                    counter,
                    releaseReservationOnFailure = false,
                    projectBaselineOnCommit = true,
                ) {
                    compileRemoteActionDiff(input, counter)
                },
            )
        }
        return counter.withAllocationLock {
            val sourceSnapshot = input.snapshot.withOwnerPersistentAnnotationState()

            fun compileUnsplit(): List<BundleResult> {
                val fallbackCounter = MessageCounter.fork(counter.snapshot())
                val plan =
                    compilePlanOn(fallbackCounter) { sequenceCounter ->
                        buildRemoteActionDraft(
                            game = null,
                            plannedCounter = sequenceCounter,
                            eventsOverride = input.events,
                            bundleFrameReservationOverride = input.reservation,
                            naiveActionsOverride = input.naiveActions,
                            projectionBaseline =
                                FrameProjectionBaseline(
                                    previousSnap = cursor.lastSent,
                                    snapTemplate = sourceSnapshot,
                                    delayedTriggerHolders = bridge.delayedTriggerHolders.activeRecords(),
                                    transientLinkedFaceFamilyIds = bridge.pendingTransientLinkedFaceFamilyIds(),
                                ),
                        )
                    }
                return@compileUnsplit commitComposite(
                    listOf(plan),
                    counter,
                    input.reservation,
                    releaseReservationOnFailure = false,
                    projectBaselineOnCommit = true,
                )
            }

            if (
                input.events.zoneMoves.isNotEmpty() ||
                bridge.snapshotPendingTargetSpecs().isNotEmpty() ||
                cursor.lastSent?.hasCardZoneDelta(sourceSnapshot) == true
            ) {
                return@withAllocationLock compileUnsplit()
            }

            val plannedCounter = MessageCounter.fork(counter.snapshot())
            var projectionBaseline =
                FrameProjectionBaseline(
                    previousSnap = cursor.lastSent,
                    snapTemplate = sourceSnapshot,
                    delayedTriggerHolders = bridge.delayedTriggerHolders.activeRecords(),
                    transientLinkedFaceFamilyIds = bridge.pendingTransientLinkedFaceFamilyIds(),
                )
            val plans = mutableListOf<FramePlan>()
            input.combatFrames.forEachIndexed { index, frame ->
                val plan =
                    compilePlanOn(plannedCounter) { sequenceCounter ->
                        buildRemoteActionDraft(
                            game = null,
                            plannedCounter = sequenceCounter,
                            eventsOverride = frame.events,
                            naiveActionsOverride = input.naiveActions,
                            projectionBaseline = projectionBaseline,
                            includePendingSubmittedTargets = index == 0,
                        )
                    }
                if (
                    index < input.combatFrames.lastIndex &&
                    plan.projection.mutations?.requiresCommittedBridgeState() == true
                ) {
                    return@withAllocationLock compileUnsplit()
                }
                projectionBaseline = projectionBaseline.advance(plan.projection)
                plans += plan
            }
            val results =
                commitComposite(
                    plans,
                    counter,
                    input.reservation,
                    releaseReservationOnFailure = false,
                    projectBaselineOnCommit = true,
                )
            if (results.size != input.combatFrames.size) return@withAllocationLock results
            results.zip(input.combatFrames) { result, frame ->
                result.copy(messages = result.messages.withLifeTotals(frame.lifeTotals))
            }
        }
    }

    internal fun compileRemoteActionDiff(
        game: Game,
        counter: MessageCounter,
        turnStarted: Boolean = false,
        eventsOverride: FrameEventLog? = null,
        bundleFrameReservationOverride: GameBridge.BundleFrameReservation? = null,
    ): FramePlan =
        compilePlan(counter) { plannedCounter ->
            buildRemoteActionDraft(
                game = game,
                plannedCounter = plannedCounter,
                turnStarted = turnStarted,
                eventsOverride = eventsOverride,
                bundleFrameReservationOverride = bundleFrameReservationOverride,
            )
        }

    internal fun compileRemoteActionDiff(
        input: PlaybackYield,
        counter: MessageCounter,
    ): FramePlan =
        compilePlan(counter) { plannedCounter ->
            val sourceSnapshot = input.snapshot.withOwnerPersistentAnnotationState()
            buildRemoteActionDraft(
                game = null,
                plannedCounter = plannedCounter,
                turnStarted = input.turnStarted,
                eventsOverride = input.events,
                bundleFrameReservationOverride = input.reservation,
                naiveActionsOverride = input.naiveActions,
                projectionBaseline =
                    FrameProjectionBaseline(
                        previousSnap = cursor.lastSent,
                        snapTemplate = sourceSnapshot,
                        delayedTriggerHolders = bridge.delayedTriggerHolders.activeRecords(),
                        transientLinkedFaceFamilyIds = bridge.pendingTransientLinkedFaceFamilyIds(),
                    ),
            )
        }

    private fun buildRemoteActionDraft(
        game: Game?,
        plannedCounter: MessageCounter,
        turnStarted: Boolean = false,
        eventsOverride: FrameEventLog? = null,
        bundleFrameReservationOverride: GameBridge.BundleFrameReservation? = null,
        projectionBaseline: FrameProjectionBaseline = FrameProjectionBaseline(cursor.lastSent),
        includePendingSubmittedTargets: Boolean = true,
        naiveActionsOverride: List<NaiveGsmAction>? = null,
    ): FramePlanDraft {
        val diff =
            buildFrameDiff(
                game,
                plannedCounter,
                eventsOverride = eventsOverride,
                bundleFrameReservationOverride = bundleFrameReservationOverride,
                projectionBaseline = projectionBaseline,
                includePendingPlayerSubmittedTargets = includePendingSubmittedTargets,
                annotationRiders = { snap, _ ->
                    if (turnStarted) {
                        listOf(AnnotationBuilder.newTurnStarted(snap.phase.activePlayer))
                    } else {
                        emptyList()
                    }
                },
            ) { _, _ -> GameStateUpdate.SendHiFi }
        val nextGs = diff.gameStateId
        val actions =
            naiveActionsOverride?.map { ActionMapper.buildNaiveGsmAction(it, diff.idResolver::cardIid) }
                ?: ActionMapper
                    .buildNaiveActions(seatId, bridge, diff.idResolver::cardIid)
                    .actionsList
        val gsBuilder = diff.result.gsm.toBuilder()
        for (action in actions) {
            gsBuilder.addActions(
                ActionInfo
                    .newBuilder()
                    .setSeatId(seatId)
                    .setAction(
                        if (naiveActionsOverride == null) {
                            ActionMapper.stripActionForGsm(action)
                        } else {
                            action
                        },
                    ),
            )
        }
        val gs = gsBuilder.build()
        val content =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, plannedCounter.nextMsgId()) {
                it.gameStateMessage = gs
            }
        val echo = buildEchoDiffGsm(plannedCounter, GameStateUpdate.SendHiFi, previousGsId = nextGs)
        return diff.planDraft(
            BundleResult(
                listOf(content) + coinFlipPromptMessages(diff.events.events, nextGs, plannedCounter) + listOf(echo),
            ),
        )
    }

    /**
     * True when the only action available is Pass (no Cast, Play, Activate).
     * Used by [AutoPassEngine] on the session thread to skip empty priority
     * points — mainly on the opponent's turn.
     *
     * This is the **session-side** layer of a two-layer auto-pass system:
     *
     * 1. **Engine-side** — priority candidate facts are materialized
     *    inside [PlayerController.chooseSpellAbilityToPlay] on the engine
     *    thread, own-turn only. When false, the engine auto-passes before the
     *    bridge round-trip even happens. The session thread never sees it.
     *
     * 2. **Session-side** (this) — checks the proto action list we already
     *    built. Covers opponent-turn priority and any case the engine-side
     *    skip didn't fire. No redundant Game queries needed.
     *
     * Stateless — lives in [Companion] so callers don't need an instance.
     */

    // --- Request builders (delegate to RequestBuilder) ---
    // MatchSession uses these instead of calling RequestBuilder directly,
    // keeping RequestBuilder as an internal dependency of the bundle layer.

    /** Build a [SelectNReq] from a pending "choose cards" prompt. */
    fun buildSelectNReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        route: SelectNPromptRoute,
    ): SelectNReq = RequestBuilder.buildSelectNReq(prompt, bridge, route)

    /** Build an [OrderReq] from a pending ordering prompt. */
    fun buildOrderReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        kind: OrderRouteKind,
    ): Pair<OrderReq, Prompt> = RequestBuilder.buildOrderReq(prompt, bridge, kind)

    /** Build a [DeclareAttackersReq] listing legal attackers. */
    fun buildDeclareAttackersReq(): DeclareAttackersReq = RequestBuilder.buildDeclareAttackersReq(SeatId(seatId), bridge)

    /**
     * Phase transition bundle matching expected client-facing message pattern (5 messages):
     *   1. GS Diff SendHiFi (2x PhaseOrStepModified, gameInfo, players, actions)
     *   2. GS Diff SendHiFi echo (turnInfo + actions only)
     *   3. GS Diff SendAndRecord (1x PhaseOrStepModified, actions)
     *   4. PromptReq (promptId=37)
     *   5. ActionsAvailableReq (promptId=2)
     */
    internal fun phaseTransitionDiff(
        game: Game,
        counter: MessageCounter,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePhaseTransitionDiff(bridge.materializeEngineObservation(game), counter)
        }

    internal fun phaseTransitionDiff(
        observation: EngineObservation,
        counter: MessageCounter,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePhaseTransitionDiff(observation, counter)
        }

    internal fun compilePhaseTransitionDiff(
        game: Game,
        counter: MessageCounter,
    ): FramePlan = compilePhaseTransitionDiff(bridge.materializeEngineObservation(game), counter)

    private fun compilePhaseTransitionDiff(
        observation: EngineObservation,
        counter: MessageCounter,
    ): FramePlan =
        compilePlan(counter) { plannedCounter ->
            val prevGs = plannedCounter.currentGsId()
            val nextGs = plannedCounter.nextGsId()
            val snap = observation.snapshot.withFrameIdentity(matchId, nextGs)

            val frame = GsmFrame.from(snap)
            val actions = buildNaiveActions(observation)
            val window = observation.preparedPriorityWindows[SeatId(seatId)]
            val priorityProjection = buildPriorityProjection(observation)

            val gs1 =
                GsmBuilder.buildTransitionState(
                    nextGs,
                    prevGameStateId = prevGs,
                    matchId,
                    bridge,
                    frame,
                    snap = snap,
                    isStageTransition = true,
                    actions = actions,
                    actionSeatId = seatId,
                )
            val msg1 =
                makeGRE(GREMessageType.GameStateMessage_695e, nextGs, plannedCounter.nextMsgId()) {
                    it.gameStateMessage = gs1
                }

            val echoGs = plannedCounter.nextGsId()
            val echoBuilder =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(echoGs)
                    .setPrevGameStateId(nextGs)
                    .setTurnInfo(frame.turnInfo())
                    .setUpdate(GameStateUpdate.SendHiFi)
            embedActions(echoBuilder, actions, seatId, pending = false)
            val msg2 =
                makeGRE(GREMessageType.GameStateMessage_695e, echoGs, plannedCounter.nextMsgId()) {
                    it.gameStateMessage = echoBuilder.build()
                }

            val commitGs = plannedCounter.nextGsId()
            val annotationId = bridge.annotations.currentAnnotationId()
            val commitBuilder =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(commitGs)
                    .setPrevGameStateId(echoGs)
                    .setTurnInfo(frame.turnInfo())
                    .addAnnotations(frame.phaseAnnotation { annotationId })
                    .addAllTimers(PlayerMapper.buildTimers())
                    .setUpdate(GameStateUpdate.SendAndRecord)
            embedActions(commitBuilder, actions, seatId)
            val msg3 =
                makeGRE(GREMessageType.GameStateMessage_695e, commitGs, plannedCounter.nextMsgId()) {
                    it.gameStateMessage = commitBuilder.build()
                }

            val msg4 =
                makeGRE(GREMessageType.PromptReq, commitGs, plannedCounter.nextMsgId()) {
                    it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.STARTING_PLAYER).build())
                }
            val msg5 =
                makeGRE(GREMessageType.ActionsAvailableReq_695e, commitGs, plannedCounter.nextMsgId()) {
                    it.actionsAvailableReq = priorityProjection.actions
                    it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                }

            FramePlanDraft(
                delivery =
                    BundleResult(
                        listOf(msg1, msg2, msg3, msg4, msg5),
                        window?.let { ActionCatalogPlan(it.actionId, commitGs, priorityProjection.offers) },
                    ),
                nextBaseline = snap,
                mutations = null,
                nextAnnotationId = annotationId + 1,
            )
        }

    private fun buildPriorityProjection(
        observation: EngineObservation,
        idResolver: (ForgeCardId) -> InstanceId = bridge::getOrAllocInstanceId,
    ): ActionMapper.ActionProjection =
        observation.preparedPriorityWindows[SeatId(seatId)]
            ?.let { PriorityActionProjector.project(it, idResolver) }
            ?: ActionMapper.ActionProjection(buildNaiveActions(observation, idResolver), emptyList())

    internal fun projectObservedActions(observation: EngineObservation): ActionMapper.ActionProjection =
        buildPriorityProjection(observation)

    private fun buildNaiveActions(
        observation: EngineObservation,
        idResolver: (ForgeCardId) -> InstanceId = bridge::getOrAllocInstanceId,
    ): ActionsAvailableReq =
        ActionsAvailableReq
            .newBuilder()
            .addAllActions(
                observation
                    .runtimeFor(SeatId(seatId))
                    .naiveActions
                    .map { ActionMapper.buildNaiveGsmAction(it, idResolver) },
            ).build()

    /** Embed stripped-down actions from ActionsAvailableReq into a GSM builder. */
    private fun embedActions(
        builder: GameStateMessage.Builder,
        actions: ActionsAvailableReq,
        seatId: Int,
        pending: Boolean = true,
    ) {
        if (pending) builder.setPendingMessageCount(1)
        for (action in actions.actionsList) {
            builder.addActions(
                ActionInfo
                    .newBuilder()
                    .setSeatId(seatId)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }
    }

    /**
     * Echo-back bundle for iterative attacker toggle: thin Diff with base creature
     * objects + fresh DeclareAttackersReq.
     *
     * Echo objects carry no combat state; the refreshed DeclareAttackersReq carries
     * selectedDamageRecipient on currently selected attacker options.
     *
     * @param selectedAttackerIds instanceIds currently selected as attackers
     * @param allLegalAttackerIds all instanceIds eligible to attack (for deselect detection)
     */
    @Suppress("UnusedParameter")
    fun echoAttackersBundle(
        game: Game,
        counter: MessageCounter,
        selectedAttackerIds: List<Int>,
        allLegalAttackerIds: List<Int>,
        selectedAttackAlternatives: Map<Int, Int> = emptyMap(),
        selectedDamageRecipients: Map<Int, DamageRecipient> = emptyMap(),
    ): BundleResult =
        combatEchoBundle(game, counter, allLegalAttackerIds, GREMessageType.DeclareAttackersReq_695e) {
            val req =
                RequestBuilder.buildDeclareAttackersReq(
                    SeatId(seatId),
                    bridge,
                    committedAttackerIds = selectedAttackerIds.toSet(),
                    committedAttackAlternatives = selectedAttackAlternatives,
                    committedDamageRecipients = selectedDamageRecipients,
                )
            val configureRequest: (GREToClientMessage.Builder) -> Unit = {
                it.declareAttackersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
            }
            configureRequest
        }

    internal fun echoAttackersBundle(
        snapshot: GsmSnapshot,
        facts: CombatDeclarationFacts,
        naiveActions: List<NaiveGsmAction>,
        counter: MessageCounter,
        selectedAttackerIds: List<Int>,
        allLegalAttackerIds: List<Int>,
        selectedAttackAlternatives: Map<Int, Int> = emptyMap(),
        selectedDamageRecipients: Map<Int, DamageRecipient> = emptyMap(),
    ): BundleResult =
        combatEchoBundle(snapshot, naiveActions, counter, allLegalAttackerIds, GREMessageType.DeclareAttackersReq_695e) {
            val req =
                RequestBuilder.buildDeclareAttackersReq(
                    SeatId(seatId),
                    facts,
                    bridge,
                    committedAttackerIds = selectedAttackerIds.toSet(),
                    committedAttackAlternatives = selectedAttackAlternatives,
                    committedDamageRecipients = selectedDamageRecipients,
                )
            val configure: (GREToClientMessage.Builder) -> Unit = {
                it.declareAttackersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
            }
            configure
        }

    /**
     * Declare-attackers bundle: Diff (DeclareAttack step) + DeclareAttackersReq (prompt id=6).
     */
    fun declareAttackersBundle(
        game: Game,
        counter: MessageCounter,
        prebuiltReq: DeclareAttackersReq? = null,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePlan(counter) { plannedCounter ->
                val diff = buildFrameDiff(game, plannedCounter) { snap, _ -> StateMapper.resolveUpdateType(snap, seatId) }
                val req =
                    prebuiltReq
                        ?: RequestBuilder.buildDeclareAttackersReq(
                            SeatId(seatId),
                            bridge,
                            idResolver = diff.idResolver::cardIid,
                        )
                diff.planDraft(
                    promptRequestBundle(diff, plannedCounter, diff.result.gsm, GREMessageType.DeclareAttackersReq_695e) {
                        it.declareAttackersReq = req
                        it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
                    },
                )
            }
        }

    fun declareAttackersBundle(
        snapshot: GsmSnapshot,
        facts: CombatDeclarationFacts,
        counter: MessageCounter,
        prebuiltReq: DeclareAttackersReq? = null,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePlan(counter) { plannedCounter ->
                val diff =
                    buildFrameDiff(
                        game = null,
                        counter = plannedCounter,
                        projectionBaseline = FrameProjectionBaseline(cursor.lastSent, snapTemplate = snapshot),
                    ) { snap, _ ->
                        StateMapper.resolveUpdateType(snap, seatId)
                    }
                val req =
                    prebuiltReq
                        ?: RequestBuilder.buildDeclareAttackersReq(
                            SeatId(seatId),
                            facts,
                            bridge,
                            idResolver = diff.idResolver::cardIid,
                        )
                diff.planDraft(
                    promptRequestBundle(diff, plannedCounter, diff.result.gsm, GREMessageType.DeclareAttackersReq_695e) {
                        it.declareAttackersReq = req
                        it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
                    },
                )
            }
        }

    /**
     * Echo-back for iterative blocker toggle: thin Diff GSM with provisional
     * blocker state on toggled creatures + fresh DeclareBlockersReq.
     *
     * Same pattern as [echoAttackersBundle] — engine's combat object doesn't
     * track provisional blocker selections during iterative declaration.
     */
    internal fun echoBlockersBundle(
        game: Game,
        counter: MessageCounter,
        blockAssignments: Map<Int, Int>, // blockerInstanceId → attackerInstanceId
    ): BundleResult =
        combatEchoBundle(game, counter, blockAssignments.keys, GREMessageType.DeclareBlockersReq_695e) {
            // Re-prompt with assigned blockers' attackerInstanceIds cleared
            val req =
                RequestBuilder.buildDeclareBlockersReq(
                    game,
                    SeatId(seatId),
                    bridge,
                    blockerAssignments = blockAssignments,
                )
            val configureRequest: (GREToClientMessage.Builder) -> Unit = {
                it.declareBlockersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
            }
            configureRequest
        }

    internal fun echoBlockersBundle(
        snapshot: GsmSnapshot,
        facts: CombatDeclarationFacts,
        naiveActions: List<NaiveGsmAction>,
        counter: MessageCounter,
        blockAssignments: Map<Int, Int>,
    ): BundleResult =
        combatEchoBundle(snapshot, naiveActions, counter, blockAssignments.keys, GREMessageType.DeclareBlockersReq_695e) {
            val req =
                RequestBuilder.buildDeclareBlockersReq(
                    facts,
                    bridge,
                    blockerAssignments = blockAssignments,
                )
            val configure: (GREToClientMessage.Builder) -> Unit = {
                it.declareBlockersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
            }
            configure
        }

    private fun combatEchoBundle(
        game: Game,
        counter: MessageCounter,
        includedInstanceIds: Collection<Int>,
        requestType: GREMessageType,
        buildRequestConfig: () -> (GREToClientMessage.Builder) -> Unit,
    ): BundleResult {
        val nextGs = counter.nextGsId()
        val player = bridge.getPlayer(SeatId(seatId)) ?: return BundleResult(emptyList())
        val snap = GsmSnapshot.capture(game, bridge, matchId, nextGs)

        // Echo objects carry no combat state; selection lives in the re-prompt.
        val objects = mutableListOf<GameObjectInfo>()
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            val fid = ForgeCardId(card.id)
            val iid = bridge.getOrAllocInstanceId(fid).value
            if (iid !in includedInstanceIds) continue
            val cardSnap = snap.objects[fid] ?: continue

            objects.add(
                ObjectMapper.buildProvisionalCombatObject(
                    cardSnap,
                    iid,
                    ZoneIds.BATTLEFIELD,
                    ownerSeatId = seatId,
                    cardProto = bridge.cardProto,
                    parentLinkage = snap.boundCards[fid]?.parentLinkage,
                ),
            )
        }

        // Cumulative turn-level actions (Cast, Play, ActivateMana, Activate).
        // Client expects echo GSMs to include this running log.
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)

        val gsmBuilder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(nextGs)
                .addAllGameObjects(objects)
                .setPrevGameStateId(nextGs - 1)
                .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(gsmBuilder, actions, seatId, pending = false)

        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gsmBuilder.build()
            }

        val configureRequest = buildRequestConfig()
        val msg2 = makeGRE(requestType, nextGs, counter.nextMsgId(), configureRequest)

        return BundleResult(listOf(msg1, msg2))
    }

    private fun combatEchoBundle(
        snapshot: GsmSnapshot,
        naiveActions: List<NaiveGsmAction>,
        counter: MessageCounter,
        includedInstanceIds: Collection<Int>,
        requestType: GREMessageType,
        buildRequestConfig: () -> (GREToClientMessage.Builder) -> Unit,
    ): BundleResult {
        val nextGs = counter.nextGsId()
        val snap = snapshot.withFrameIdentity(matchId, nextGs)
        val objects =
            snap.objects.mapNotNull { (fid, cardSnap) ->
                val iid = bridge.getOrAllocInstanceId(fid).value
                if (iid !in includedInstanceIds) return@mapNotNull null
                ObjectMapper.buildProvisionalCombatObject(
                    cardSnap,
                    iid,
                    ZoneIds.BATTLEFIELD,
                    ownerSeatId = seatId,
                    cardProto = bridge.cardProto,
                    parentLinkage = snap.boundCards[fid]?.parentLinkage,
                )
            }
        val actions = naiveActions.map { ActionMapper.buildNaiveGsmAction(it, bridge::getOrAllocInstanceId) }
        val gsm =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(nextGs)
                .addAllGameObjects(objects)
                .setPrevGameStateId(nextGs - 1)
                .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(gsm, ActionsAvailableReq.newBuilder().addAllActions(actions).build(), seatId, pending = false)
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gsm.build()
            }
        val msg2 = makeGRE(requestType, nextGs, counter.nextMsgId(), buildRequestConfig())
        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * Declare-blockers bundle: Diff (DeclareBlock step) + DeclareBlockersReq (prompt id=7).
     */
    fun declareBlockersBundle(
        game: Game,
        counter: MessageCounter,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePlan(counter) { plannedCounter ->
                val diff = buildFrameDiff(game, plannedCounter) { snap, _ -> StateMapper.resolveUpdateType(snap, seatId) }
                val req =
                    RequestBuilder.buildDeclareBlockersReq(
                        game,
                        SeatId(seatId),
                        bridge,
                        idResolver = diff.idResolver::cardIid,
                    )
                diff.planDraft(
                    promptRequestBundle(diff, plannedCounter, diff.result.gsm, GREMessageType.DeclareBlockersReq_695e) {
                        it.declareBlockersReq = req
                        it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
                    },
                )
            }
        }

    fun declareBlockersBundle(
        snapshot: GsmSnapshot,
        facts: CombatDeclarationFacts,
        counter: MessageCounter,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePlan(counter) { plannedCounter ->
                val diff =
                    buildFrameDiff(
                        game = null,
                        counter = plannedCounter,
                        projectionBaseline = FrameProjectionBaseline(cursor.lastSent, snapTemplate = snapshot),
                    ) { snap, _ ->
                        StateMapper.resolveUpdateType(snap, seatId)
                    }
                val req =
                    RequestBuilder.buildDeclareBlockersReq(
                        facts,
                        bridge,
                        idResolver = diff.idResolver::cardIid,
                    )
                diff.planDraft(
                    promptRequestBundle(diff, plannedCounter, diff.result.gsm, GREMessageType.DeclareBlockersReq_695e) {
                        it.declareBlockersReq = req
                        it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
                    },
                )
            }
        }

    /**
     * Select-targets bundle: GameState + SelectTargetsReq.
     *
     * Builds the diff **first** (which triggers instanceId reallocs for zone
     * transfers like Hand→Stack), then builds the SelectTargetsReq so that
     * `sourceId` and target instanceIds reflect the post-realloc state.
     * Without this ordering, `sourceId` would reference a retired instanceId
     * and the client wouldn't draw the targeting arrow.
     *
     * Sets `allowCancel=Abort` and `allowUndo=true` on the GRE wrapper
     * (client shows Cancel button and allows undo during targeting).
     */
    fun selectTargetsBundle(
        game: Game,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
    ): BundleResult = selectTargetsBundle(game, null, counter, prompt)

    private fun selectTargetsBundle(
        game: Game?,
        snapshot: GsmSnapshot?,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePlan(counter) { plannedCounter ->
                val diff =
                    buildFrameDiff(
                        game,
                        plannedCounter,
                        projectionBaseline = observedFrameBaseline(snapshot),
                        annotationRiders = { _, frameIds ->
                            prompt.request.sourceEntityId?.let { sourceEntityId ->
                                listOf(
                                    AnnotationBuilder.playerSelectingTargets(
                                        frameIds.cardIid(ForgeCardId(sourceEntityId)),
                                        SeatId(seatId),
                                    ),
                                )
                            } ?: emptyList()
                        },
                    ) { _, _ -> GameStateUpdate.Send }
                val req =
                    RequestBuilder.buildSelectTargetsReq(
                        prompt,
                        bridge,
                        seatId,
                        diff.idResolver::cardIid,
                    )
                diff.planDraft(
                    promptRequestBundle(diff, plannedCounter, diff.result.gsm, GREMessageType.SelectTargetsReq_695e) {
                        it.selectTargetsReq = req
                        it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_TARGETS).build())
                        it.allowCancel = AllowCancel.Abort
                        it.allowUndo = true
                    },
                )
            }
        }

    /**
     * SelectN bundle: GameState + SelectNReq.
     * Used for "choose N cards" prompts (discard, sacrifice, etc.).
     */
    fun selectNBundle(
        game: Game,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
        route: SelectNPromptRoute,
        envelopeForReq: (SelectNReq) -> SelectNEnvelope,
    ): BundleResult = selectNBundle(game, null, counter, prompt, route, envelopeForReq)

    private fun selectNBundle(
        game: Game?,
        snapshot: GsmSnapshot?,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
        route: SelectNPromptRoute,
        envelopeForReq: (SelectNReq) -> SelectNEnvelope,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePlan(counter) { plannedCounter ->
                val diff =
                    buildFrameDiff(
                        game,
                        plannedCounter,
                        projectionBaseline = observedFrameBaseline(snapshot),
                    ) { _, _ -> GameStateUpdate.Send }
                val req = RequestBuilder.buildSelectNReq(prompt, bridge, route, diff.idResolver::cardIid)
                diff.planDraft(selectNBundleFromDiff(diff, plannedCounter, envelopeForReq(req)))
            }
        }

    fun selectNBundle(
        game: Game,
        counter: MessageCounter,
        envelope: SelectNEnvelope,
    ): BundleResult = selectNBundle(game, null, counter, envelope)

    private fun selectNBundle(
        game: Game?,
        snapshot: GsmSnapshot?,
        counter: MessageCounter,
        envelope: SelectNEnvelope,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePlan(counter) { plannedCounter ->
                val diff =
                    buildFrameDiff(
                        game,
                        plannedCounter,
                        projectionBaseline = observedFrameBaseline(snapshot),
                    ) { _, _ -> GameStateUpdate.Send }
                diff.planDraft(selectNBundleFromDiff(diff, plannedCounter, envelope))
            }
        }

    private fun selectNBundleFromDiff(
        diff: FrameDiff,
        counter: MessageCounter,
        envelope: SelectNEnvelope,
    ): BundleResult {
        val snap = diff.snap
        val baseGs =
            when (envelope.gameStateAugmentation) {
                SelectNEnvelope.GameStateAugmentation.LookAndPick ->
                    attachLookAndPickGameObjects(diff.result.gsm, envelope.req, snap, diff.idResolver)
                SelectNEnvelope.GameStateAugmentation.LearnLesson ->
                    attachLearnLessonGameObjects(diff.result.gsm, envelope.req, snap, diff.idResolver)
                SelectNEnvelope.GameStateAugmentation.None -> diff.result.gsm
            }
        val gs =
            baseGs
                .toBuilder()
                .setPendingMessageCount(1)
                .build()
        return promptRequestBundle(diff, counter, gs, GREMessageType.SelectNreq) {
            it.selectNReq = envelope.req
            it.setPrompt(envelope.prompt)
            if (envelope.allowCancel != AllowCancel.None_a526) {
                it.allowCancel = envelope.allowCancel
            }
        }
    }

    /** Order bundle: GameState + OrderReq. */
    fun orderBundle(
        game: Game,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
        kind: OrderRouteKind,
    ): BundleResult = orderBundle(game, null, counter, prompt, kind)

    private fun orderBundle(
        game: Game?,
        snapshot: GsmSnapshot?,
        counter: MessageCounter,
        prompt: InteractivePromptBridge.PendingPrompt,
        kind: OrderRouteKind,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePlan(counter) { plannedCounter ->
                val frameDraft =
                    buildFrameDraft(
                        game,
                        plannedCounter,
                        projectionBaseline = observedFrameBaseline(snapshot),
                    ) { _, _ -> GameStateUpdate.Send }
                val snap = frameDraft.snap
                val stagedMove = stagePendingOrderZoneMove(frameDraft.result.gsm, snap, prompt, frameDraft.idResolver)
                val augmentedDraft =
                    stagedMove?.let { move ->
                        frameDraft.copy(
                            result = frameDraft.result.copy(gsm = move.gsm),
                            idResolver = move.idResolver,
                        )
                    } ?: frameDraft
                val diff = finalizeFrameDiff(augmentedDraft, emptyList(), cursorSnap = stagedMove?.snap ?: snap)
                val (req, promptProto) =
                    RequestBuilder.buildOrderReq(prompt, bridge, kind, diff.idResolver::cardIid)
                val baseOrderGsm =
                    if (stagedMove != null) {
                        diff.result.gsm
                    } else {
                        attachOrderGameObjects(diff.result.gsm, req, snap, diff.idResolver)
                    }
                val gs =
                    baseOrderGsm
                        .toBuilder()
                        .setPendingMessageCount(1)
                        .build()
                val delivery =
                    promptRequestBundle(
                        diff,
                        plannedCounter,
                        gs,
                        GREMessageType.OrderReq_695e,
                    ) {
                        it.orderReq = req
                        it.setPrompt(promptProto)
                        it.allowCancel = AllowCancel.No_a526
                        if (kind == OrderRouteKind.Top) {
                            it.allowUndo = true
                        }
                    }
                val mutations =
                    stagedMove?.let { move ->
                        diff.result.mutations.copy(
                            idReallocations = diff.result.mutations.idReallocations + move.reallocations,
                            retiredIds = diff.result.mutations.retiredIds + move.moved.map { it.oldId },
                            zoneRecordings =
                                diff.result.mutations.zoneRecordings +
                                    move.moved.map { it.newId to move.destinationZoneId },
                        )
                    } ?: diff.result.mutations
                diff.planDraft(delivery, mutations, stagedMove?.pendingMove)
            }
        }

    private fun promptRequestBundle(
        diff: FrameDiff,
        counter: MessageCounter,
        gameStateMessage: GameStateMessage,
        requestType: GREMessageType,
        configureRequest: (GREToClientMessage.Builder) -> Unit,
    ): BundleResult {
        val nextGs = diff.gameStateId
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gameStateMessage
            }
        val msg2 = makeGRE(requestType, nextGs, counter.nextMsgId(), configureRequest)

        return BundleResult(listOf(msg1, msg2))
    }

    private data class StagedOrderMove(
        val gsm: GameStateMessage,
        val snap: GsmSnapshot,
        val moved: List<StagedMovedCard>,
        val reallocations: List<leyline.game.state.InstanceIdRegistry.IdReallocation>,
        val destinationZoneId: Int,
        val pendingMove: InteractivePromptBridge.PendingOrderZoneMove,
        val idResolver: FrameIdResolver,
    )

    private data class StagedMovedCard(
        val forgeCardId: ForgeCardId,
        val oldId: InstanceId,
        val newId: InstanceId,
    )

    private fun stagePendingOrderZoneMove(
        gsm: GameStateMessage,
        snap: GsmSnapshot,
        prompt: InteractivePromptBridge.PendingPrompt,
        frameIds: FrameIdResolver,
    ): StagedOrderMove? {
        val candidateFids =
            prompt.request.candidateRefs
                .filter { it.isCard() }
                .map { ForgeCardId(it.entityId) }
        val move = bridge.promptBridge(SeatId(seatId)).pendingOrderZoneMove(SeatId(seatId), candidateFids) ?: return null
        if (candidateFids.isEmpty()) return null
        val sourceZoneId = ZoneIds.handOf(move.seatId)
        val destZoneId = ZoneIds.libraryOf(move.seatId)

        val moved =
            candidateFids.map { fid ->
                val realloc = bridge.planInstanceIdReallocation(fid)
                StagedMovedCard(fid, realloc.old, realloc.new)
            }
        val reallocations =
            moved.map {
                leyline.game.state.InstanceIdRegistry
                    .IdReallocation(it.oldId, it.newId)
            }
        val stagedIds = frameIds.withPostReallocIids(moved.associate { it.forgeCardId to it.newId })
        val stagedSnap = stagedOrderSnapshot(snap, move, sourceZoneId, destZoneId)
        val sourceIid = prompt.request.sourceEntityId?.let { stagedIds.cardIid(ForgeCardId(it)) } ?: InstanceId(0)
        return StagedOrderMove(
            gsm = stagedOrderGsm(gsm, snap, stagedSnap, move, moved, sourceIid, sourceZoneId, destZoneId, stagedIds),
            snap = stagedSnap,
            moved = moved,
            reallocations = reallocations,
            destinationZoneId = destZoneId,
            pendingMove = move,
            idResolver = stagedIds,
        )
    }

    private fun stagedOrderSnapshot(
        snap: GsmSnapshot,
        move: InteractivePromptBridge.PendingOrderZoneMove,
        sourceZoneId: Int,
        destZoneId: Int,
    ): GsmSnapshot {
        val movedSet = move.forgeCardIds.toSet()
        val zones = snap.zones.toMutableMap()
        val source = zones[sourceZoneId]
        val dest = zones[destZoneId]
        if (source != null) {
            zones[sourceZoneId] = source.copy(contents = source.contents.filterNot { it in movedSet })
        }
        if (dest != null) {
            val remaining = dest.contents.filterNot { it in movedSet }
            zones[destZoneId] =
                dest.copy(contents = if (move.putOnTop) move.forgeCardIds + remaining else remaining + move.forgeCardIds)
        }
        return GsmSnapshot(
            matchId = snap.matchId,
            gameStateId = snap.gameStateId,
            seats = snap.seats,
            zones = zones,
            boundCards = snap.boundCards,
            stack = snap.stack,
            phase = snap.phase,
            combat = snap.combat,
            abilityWordEntries = snap.abilityWordEntries,
            pendingTriggers = snap.pendingTriggers,
            combatQualifications = snap.combatQualifications,
            persistentAnnotationState = snap.persistentAnnotationState,
            capturedAt = snap.capturedAt,
            dayTime = snap.dayTime,
            activePlayerSpellsCastThisTurn = snap.activePlayerSpellsCastThisTurn,
        )
    }

    private fun stagedOrderGsm(
        gsm: GameStateMessage,
        snap: GsmSnapshot,
        stagedSnap: GsmSnapshot,
        move: InteractivePromptBridge.PendingOrderZoneMove,
        moved: List<StagedMovedCard>,
        sourceIid: InstanceId,
        sourceZoneId: Int,
        destZoneId: Int,
        frameIds: FrameIdResolver,
    ): GameStateMessage {
        val movedOldIds = moved.map { it.oldId.value }.toSet()
        val movedNewIds = moved.map { it.newId.value }.toSet()
        val builder = gsm.toBuilder()
        val replacementZones =
            listOfNotNull(
                stagedSnap.zones[sourceZoneId]?.let { zoneInfoFor(it, frameIds) },
                stagedSnap.zones[destZoneId]?.let { zoneInfoFor(it, frameIds) },
                limboZoneInfo(moved.map { it.oldId }),
            )
        builder.clearZones()
        builder.addAllZones(
            (gsm.zonesList.filterNot { it.zoneId in setOf(sourceZoneId, destZoneId, ZoneIds.LIMBO) } + replacementZones)
                .sortedBy { it.zoneId },
        )
        builder.clearGameObjects()
        builder.addAllGameObjects(gsm.gameObjectsList.filterNot { it.instanceId in movedOldIds || it.instanceId in movedNewIds })
        moved.forEach { staged ->
            val cardSnap = snap.objects[staged.forgeCardId] ?: return@forEach
            builder.addGameObjects(orderMoveObject(cardSnap, staged.oldId, ZoneIds.LIMBO, move.seatId.value))
            builder.addGameObjects(orderMoveObject(cardSnap, staged.newId, destZoneId, move.seatId.value))
            builder.addAnnotations(
                AnnotationBuilder
                    .objectIdChanged(staged.oldId, staged.newId, sourceIid),
            )
            builder.addAnnotations(
                AnnotationBuilder
                    .zoneTransfer(staged.newId, sourceZoneId, destZoneId, "Put", affectorId = sourceIid),
            )
        }
        return builder.build()
    }

    private fun zoneInfoFor(
        zone: leyline.game.snapshot.ZoneSnapshot,
        frameIds: FrameIdResolver,
    ): ZoneInfo {
        val builder =
            ZoneInfo
                .newBuilder()
                .setZoneId(zone.id)
                .setType(zone.type)
                .setVisibility(if (zone.type == ZoneType.Library) Visibility.Hidden else zone.visibility)
        zone.owner?.let { owner ->
            builder.setOwnerSeatId(owner.value)
            if (zone.type == ZoneType.Hand || zone.type == ZoneType.Sideboard) {
                builder.addViewers(owner.value)
            }
        }
        zone.contents.forEach { fid -> builder.addObjectInstanceIds(frameIds.cardIid(fid).value) }
        return builder.build()
    }

    private fun limboZoneInfo(plannedRetirements: List<InstanceId>): ZoneInfo =
        ZoneInfo
            .newBuilder()
            .setZoneId(ZoneIds.LIMBO)
            .setType(ZoneType.Limbo)
            .setVisibility(Visibility.Public)
            .addAllObjectInstanceIds((bridge.getLimboInstanceIds() + plannedRetirements).map { it.value })
            .build()

    private fun orderMoveObject(
        cardSnap: CardSnapshot,
        instanceId: InstanceId,
        zoneId: Int,
        ownerSeatId: Int,
    ): GameObjectInfo =
        ObjectMapper
            .buildFromSnapshot(
                cardSnap = cardSnap,
                instanceId = instanceId.value,
                zoneId = zoneId,
                ownerSeatId = ownerSeatId,
                cardProto = bridge.cardProto,
                visibility = Visibility.Private,
            ).toBuilder()
            .addViewers(ownerSeatId)
            .build()

    private fun attachOrderGameObjects(
        gsm: GameStateMessage,
        req: OrderReq,
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): GameStateMessage {
        if (req.idsList.isEmpty()) return gsm

        val gsBuilder = gsm.toBuilder()
        val existingByIid = gsBuilder.gameObjectsList.withIndex().associate { (idx, obj) -> obj.instanceId to idx }
        for (iid in req.idsList) {
            val forgeCardId = frameIds.forgeCardId(InstanceId(iid)) ?: continue
            val cardSnap = snap.objects[forgeCardId] ?: continue
            val zone = snap.zones.values.firstOrNull { forgeCardId in it.contents } ?: continue
            val obj =
                ObjectMapper
                    .buildFromSnapshot(
                        cardSnap = cardSnap,
                        instanceId = iid,
                        zoneId = zone.id,
                        ownerSeatId = zone.owner?.value ?: seatId,
                        cardProto = bridge.cardProto,
                        visibility = Visibility.Private,
                    ).toBuilder()
                    .addViewers(seatId)
                    .build()
            val existingIdx = existingByIid[iid]
            if (existingIdx != null) {
                gsBuilder.setGameObjects(existingIdx, obj)
            } else {
                gsBuilder.addGameObjects(obj)
            }
        }
        return gsBuilder.build()
    }

    /**
     * Look-and-pick GSM augmentation. Adds full [GameObjectInfo] entries for the
     * SelectN candidate iids with `visibility = Private, viewers = [seatId]`,
     * keeping them in the chooser's library zone.
     *
     * Required because the client renders the SelectN panel from
     * [GameObjectInfo] entries, not from the [SelectNReq.ids] list alone. With
     * the candidates' iids in the library but no per-iid object data sent, the
     * panel comes through blank. Adding the entries with the chooser as the sole
     * `viewer` reveals the cards to the picking player without leaking them to
     * the opponent.
     *
     * Routes through [ObjectMapper.buildFromSnapshot] so the canonical card →
     * GameObjectInfo pipeline stays the single source of truth (P/T,
     * extrinsic keywords, attachment state, etc.). The only override on top
     * is `addViewers(seatId)`.
     */
    private fun attachLookAndPickGameObjects(
        gsm: GameStateMessage,
        req: SelectNReq,
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): GameStateMessage {
        if (req.idsList.isEmpty()) return gsm
        val gsBuilder = gsm.toBuilder()
        val libraryZoneId = ZoneIds.libraryOf(seatId)
        val existingByIid = gsBuilder.gameObjectsList.withIndex().associate { (idx, obj) -> obj.instanceId to idx }
        for (iid in req.idsList) {
            val forgeCardId =
                frameIds.forgeCardId(InstanceId(iid)) ?: run {
                    log.warn("attachLookAndPickGameObjects: no ForgeCardId for iid={}", iid)
                    continue
                }
            val cardSnap =
                snap.objects[forgeCardId] ?: run {
                    log.warn(
                        "attachLookAndPickGameObjects: no CardSnapshot for forgeCardId={} iid={}",
                        forgeCardId.value,
                        iid,
                    )
                    continue
                }
            val obj =
                ObjectMapper
                    .buildFromSnapshot(
                        cardSnap = cardSnap,
                        instanceId = iid,
                        zoneId = libraryZoneId,
                        ownerSeatId = seatId,
                        cardProto = bridge.cardProto,
                        visibility = Visibility.Private,
                    ).toBuilder()
                    .addViewers(seatId)
                    .build()
            val existingIdx = existingByIid[iid]
            if (existingIdx != null) {
                gsBuilder.setGameObjects(existingIdx, obj)
            } else {
                gsBuilder.addGameObjects(obj)
            }
        }
        return gsBuilder.build()
    }

    /**
     * Learn choices can include sideboard Lessons whose object data was not part
     * of the latest diff. Attach the selectable cards in their current zones so
     * the SelectN panel has renderable card objects.
     */
    private fun attachLearnLessonGameObjects(
        gsm: GameStateMessage,
        req: SelectNReq,
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): GameStateMessage {
        if (req.idsList.isEmpty()) return gsm
        val gsBuilder = gsm.toBuilder()
        val existingByIid = gsBuilder.gameObjectsList.withIndex().associate { (idx, obj) -> obj.instanceId to idx }
        for (iid in req.idsList) {
            val forgeCardId =
                frameIds.forgeCardId(InstanceId(iid)) ?: run {
                    log.warn("attachLearnLessonGameObjects: no ForgeCardId for iid={}", iid)
                    continue
                }
            val cardSnap =
                snap.objects[forgeCardId] ?: run {
                    log.warn(
                        "attachLearnLessonGameObjects: no CardSnapshot for forgeCardId={} iid={}",
                        forgeCardId.value,
                        iid,
                    )
                    continue
                }
            val zone = snap.zones.values.firstOrNull { forgeCardId in it.contents }
            val obj =
                ObjectMapper
                    .buildFromSnapshot(
                        cardSnap = cardSnap,
                        instanceId = iid,
                        zoneId = zone?.id ?: ZoneIds.sideboardOf(seatId),
                        ownerSeatId = zone?.owner?.value ?: seatId,
                        cardProto = bridge.cardProto,
                        visibility = Visibility.Private,
                    ).toBuilder()
                    .addViewers(seatId)
                    .build()
            val existingIdx = existingByIid[iid]
            if (existingIdx != null) {
                gsBuilder.setGameObjects(existingIdx, obj)
            } else {
                gsBuilder.addGameObjects(obj)
            }
        }
        return gsBuilder.build()
    }

    /**
     * CastingTimeOptions bundle: GameState + CastingTimeOptionsReq.
     * Used for modal ETB/cast prompts (Charming Prince, Goblin Surprise, etc.).
     *
     * Sends a GSM diff first (state may have changed during trigger/resolution),
     * followed by CastingTimeOptionsReq with the ModalReq payload. Sets
     * allowCancel=Abort and allowUndo=true (client shows Cancel button).
     */

    /**
     * @param sourceCardInstanceId instanceId of the source card (for ability parentId).
     *   Null for spell-time modals where the card itself is on the stack.
     * @param sourceCardGrpId grpId of the source card (for ability objectSourceGrpId).
     *   Null for spell-time modals.
     */
    fun castingTimeOptionsBundle(
        game: Game,
        counter: MessageCounter,
        req: CastingTimeOptionsReq,
        sourceCardInstanceId: Int? = null,
        sourceCardGrpId: Int? = null,
    ): BundleResult =
        castingTimeOptionsBundle(
            game,
            null,
            counter,
            req,
            sourceCardInstanceId,
            sourceCardGrpId,
        )

    private fun castingTimeOptionsBundle(
        game: Game?,
        snapshot: GsmSnapshot?,
        counter: MessageCounter,
        req: CastingTimeOptionsReq,
        sourceCardInstanceId: Int? = null,
        sourceCardGrpId: Int? = null,
    ): BundleResult =
        compileAndCommit(counter) {
            compilePlan(counter) { plannedCounter ->
                val diff =
                    buildFrameDiff(
                        game,
                        plannedCounter,
                        projectionBaseline = observedFrameBaseline(snapshot),
                    ) { _, _ -> GameStateUpdate.Send }
                val gsBuilder =
                    diff.result.gsm
                        .toBuilder()
                        .setPendingMessageCount(1)

                if (sourceCardInstanceId != null && req.castingTimeOptionReqCount > 0) {
                    val cto = req.getCastingTimeOptionReq(0)
                    val abilityIid = cto.affectedId
                    val abilityGrpId = cto.grpId
                    if (abilityIid > 0 && abilityGrpId > 0) {
                        val alreadyPresent = gsBuilder.gameObjectsList.any { it.instanceId == abilityIid }
                        if (!alreadyPresent) {
                            val abilityBuilder =
                                GameObjectInfo
                                    .newBuilder()
                                    .setInstanceId(abilityIid)
                                    .setGrpId(abilityGrpId)
                                    .setType(GameObjectType.Ability)
                                    .setZoneId(ZoneIds.STACK)
                                    .setVisibility(Visibility.Public)
                                    .setOwnerSeatId(seatId)
                                    .setControllerSeatId(seatId)
                            abilityBuilder.setObjectSourceGrpId(sourceCardGrpId ?: abilityGrpId)
                            abilityBuilder.setParentId(sourceCardInstanceId)
                            gsBuilder.addGameObjects(abilityBuilder.build())

                            val stackIdx = gsBuilder.zonesList.indexOfFirst { it.type == ZoneType.Stack }
                            if (stackIdx >= 0) {
                                val updated =
                                    gsBuilder
                                        .getZones(stackIdx)
                                        .toBuilder()
                                        .addObjectInstanceIds(abilityIid)
                                        .build()
                                gsBuilder.setZones(stackIdx, updated)
                            } else {
                                gsBuilder.addZones(
                                    ZoneInfo
                                        .newBuilder()
                                        .setZoneId(ZoneIds.STACK)
                                        .setType(ZoneType.Stack)
                                        .setVisibility(Visibility.Public)
                                        .addObjectInstanceIds(abilityIid)
                                        .build(),
                                )
                            }
                        }
                    }
                }

                diff.planDraft(
                    promptRequestBundle(
                        diff,
                        plannedCounter,
                        gsBuilder.build(),
                        GREMessageType.CastingTimeOptionsReq_695e,
                    ) {
                        it.castingTimeOptionsReq = req
                        it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.CASTING_TIME_OPTIONS).build())
                        it.allowCancel = AllowCancel.Abort
                        it.allowUndo = true
                    },
                )
            }
        }

    /**
     * PayCosts bundle: GameState + PayCostsReq.
     * Tells the client to show its native cost-selection UI (mana source
     * payment, sacrifice, exile-from-graveyard additional costs, convoke).
     *
     * Merges any [promptPersistentAnnotations] not already present in the
     * frame diff's GSM, so the prompt carries pAnns the client needs to
     * render the candidates (e.g. convoke tap counts) even when the diff
     * itself wouldn't have emitted them this tick.
     *
     * The client responds with PerformActionResp (already handled).
     */
    fun payCostsBundle(
        game: Game,
        counter: MessageCounter,
        req: PayCostsReq,
        prompt: Prompt? = null,
        promptPersistentAnnotations: List<AnnotationInfo> = emptyList(),
    ): BundleResult =
        payCostsBundle(
            game,
            null,
            counter,
            req,
            prompt,
            promptPersistentAnnotations,
        )

    private fun payCostsBundle(
        game: Game?,
        snapshot: GsmSnapshot?,
        counter: MessageCounter,
        req: PayCostsReq,
        prompt: Prompt? = null,
        promptPersistentAnnotations: List<AnnotationInfo> = emptyList(),
    ): BundleResult =
        compileAndCommit(counter) {
            compilePlan(counter) { plannedCounter ->
                val diff =
                    buildFrameDiff(
                        game,
                        plannedCounter,
                        projectionBaseline = observedFrameBaseline(snapshot),
                    ) { _, _ -> GameStateUpdate.Send }
                val promptOnlyPersistentAnnotations =
                    promptPersistentAnnotations.filterNot { extra ->
                        diff.result.gsm.persistentAnnotationsList
                            .any { it == extra }
                    }
                val gs =
                    if (promptOnlyPersistentAnnotations.isEmpty()) {
                        diff.result.gsm
                    } else {
                        diff.result.gsm
                            .toBuilder()
                            .addAllPersistentAnnotations(promptOnlyPersistentAnnotations)
                            .build()
                    }
                diff.planDraft(
                    promptRequestBundle(diff, plannedCounter, gs, GREMessageType.PayCostsReq_695e) {
                        it.payCostsReq = req
                        it.setPrompt(prompt ?: Prompt.newBuilder().setPromptId(PromptIds.PAY_COSTS).build())
                        it.allowCancel = AllowCancel.Abort
                        it.allowUndo = true
                    },
                )
            }
        }

    /**
     * Wrap a GameStateMessage as QueuedGameStateMessage (type 51) for opponent during prompts.
     */
    fun queuedGameState(
        gameState: GameStateMessage,
        counter: MessageCounter,
    ): GREToClientMessage =
        makeGRE(GREMessageType.QueuedGameStateMessage, counter.currentGsId(), counter.nextMsgId()) {
            it.gameStateMessage = gameState
        }

    /**
     * Server-forced pass (EdictalMessage). Tells the client "I'm passing priority for seat X".
     * Breaks the client out of autoPassPriority mode so it re-renders action buttons.
     */
    fun edictalPass(counter: MessageCounter): BundleResult {
        val edictal =
            EdictalMessage
                .newBuilder()
                .setEdictMessage(
                    ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.PerformActionResp_097b)
                        .setSystemSeatId(seatId)
                        .setPerformActionResp(
                            PerformActionResp
                                .newBuilder()
                                .addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                        ),
                ).build()
        val msg =
            makeGRE(GREMessageType.EdictalMessage_695e, counter.currentGsId(), counter.nextMsgId()) {
                it.edictalMessage = edictal
            }
        return BundleResult(listOf(msg))
    }

    /**
     * Game-over sequence: 3x GS Diff + IntermissionReq.
     * Pure proto construction — no bridge or game engine access.
     *
     * Protocol pattern:
     * - gs1: GameInfo(stage=GameOver, matchState=GameComplete, 1 result scope=Game),
     *        players with PendingLoss, teams, LossOfGame annotation (if lethal)
     * - gs2: GameInfo(stage=GameOver, matchState=MatchComplete, 2 results Game+Match)
     * - gs3: bare diff with pendingMessageCount=1
     * - IntermissionReq: options, intermissionPrompt(27) with WinningTeamId param
     *
     * @param reason Game_ae0a for natural game end, Concede for concession
     * @param losingPlayerSeatId seat of the losing player (for LossOfGame annotation)
     * @param lossReason wire-level loss reason for the LossOfGame annotation
     */
    fun gameOverBundle(
        winningTeam: Int,
        counter: MessageCounter,
        reason: ResultReason = ResultReason.Game_ae0a,
        losingPlayerSeatId: Int = 0,
        lossReason: AnnotationLossReason = AnnotationLossReason.LifeTotal,
        snapshot: GsmSnapshot? = null,
    ): BundleResult {
        val prevGsId = counter.currentGsId()
        val losingTeam = if (winningTeam == 1) 2 else 1

        // Shared GameInfo fields matching initial bundle (StateMapper.buildFromSnapshot)
        fun baseGameInfo() =
            GameInfo
                .newBuilder()
                .setMatchID(matchId)
                .setGameNumber(1)
                .setStage(GameStage.GameOver)
                .setType(GameType.Duel)
                .setVariant(GameVariant.Normal)
                .setMatchWinCondition(MatchWinCondition.SingleElimination)
                .setSuperFormat(SuperFormat.Constructed)
                .setMulliganType(MulliganType.London)
                .setDeckConstraintInfo(
                    DeckConstraintInfo
                        .newBuilder()
                        .setMinDeckSize(60)
                        .setMaxDeckSize(250)
                        .setMaxSideboardSize(15),
                )

        val gameResult =
            ResultSpec
                .newBuilder()
                .setScope(MatchScope.Game_a146)
                .setResult(ResultType.WinLoss)
                .setWinningTeamId(winningTeam)
                .setReason(reason)

        val matchResult =
            ResultSpec
                .newBuilder()
                .setScope(MatchScope.Match)
                .setResult(ResultType.WinLoss)
                .setWinningTeamId(winningTeam)
                .setReason(reason)

        // gs1: GameComplete with Game result only, PendingLoss players
        val gs1Info =
            baseGameInfo()
                .setMatchState(MatchState.GameComplete)
                .addResults(gameResult)
        val gs1Id = counter.nextGsId()
        val gs1 =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gs1Id)
                .setPrevGameStateId(prevGsId)
                .setGameInfo(gs1Info)
                .setUpdate(GameStateUpdate.SendAndRecord)
        // Teams with PendingLoss for losing team
        gs1.addTeams(
            TeamInfo
                .newBuilder()
                .setId(losingTeam)
                .addPlayerIds(losingPlayerSeatId)
                .setStatus(TeamStatus.PendingLoss_a458),
        )
        // Players: loser with full state (lifeTotal, maxHandSize, etc.) + PendingLoss status
        val gameOverSnap =
            snapshot ?: bridge.getGame()?.let { game ->
                GsmSnapshot.capture(game, bridge, matchId, 0)
            }
        if (gameOverSnap != null) {
            val loserInfo =
                PlayerMapper
                    .buildFromSnapshot(gameOverSnap, losingPlayerSeatId)
                    .toBuilder()
                    .setStatus(PlayerStatus.PendingLoss_a1c6)
            gs1.addPlayers(loserInfo)
        }
        // Timers — inactivity timer on gs1
        gs1.addAllTimers(PlayerMapper.buildTimers())
        // LossOfGame annotation
        if (losingPlayerSeatId != 0) {
            gs1.addAnnotations(AnnotationBuilder.lossOfGame(SeatId(losingPlayerSeatId), lossReason))
        }

        // gs2: MatchComplete with both Game + Match results
        val gs2Info =
            baseGameInfo()
                .setMatchState(MatchState.MatchComplete)
                .addResults(gameResult)
                .addResults(matchResult)
        val gs2Id = counter.nextGsId()
        val gs2 =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gs2Id)
                .setPrevGameStateId(gs1Id)
                .setGameInfo(gs2Info)
                .setUpdate(GameStateUpdate.SendAndRecord)

        // gs3: bare diff with pendingMessageCount=1 (IntermissionReq follows)
        val gs3Id = counter.nextGsId()
        val gs3 =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gs3Id)
                .setPrevGameStateId(gs2Id)
                .setPendingMessageCount(1)
                .setUpdate(GameStateUpdate.SendAndRecord)

        val messages =
            mutableListOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gs1Id, counter.nextMsgId()) { it.gameStateMessage = gs1.build() },
                makeGRE(GREMessageType.GameStateMessage_695e, gs2Id, counter.nextMsgId()) { it.gameStateMessage = gs2.build() },
                makeGRE(GREMessageType.GameStateMessage_695e, gs3Id, counter.nextMsgId()) { it.gameStateMessage = gs3.build() },
            )

        messages.add(
            makeGRE(GREMessageType.IntermissionReq_695e, gs3Id, counter.nextMsgId()) {
                it.intermissionReq =
                    IntermissionReq
                        .newBuilder()
                        .setResult(
                            ResultSpec
                                .newBuilder()
                                .setScope(MatchScope.Match)
                                .setResult(ResultType.WinLoss)
                                .setWinningTeamId(winningTeam)
                                .setReason(reason),
                        ).addOptions(
                            UserOption
                                .newBuilder()
                                .setOptionPrompt(Prompt.newBuilder().setPromptId(PromptIds.DRAW_CARD))
                                .setResponseType(ClientMessageType.DrawCardResp),
                        ).addOptions(
                            UserOption
                                .newBuilder()
                                .setOptionPrompt(Prompt.newBuilder().setPromptId(PromptIds.REVEAL_HAND))
                                .setResponseType(ClientMessageType.RevealHandResp),
                        ).setIntermissionPrompt(
                            Prompt
                                .newBuilder()
                                .setPromptId(PromptIds.MATCH_RESULT_WIN_LOSS)
                                .addParameters(
                                    PromptParameter
                                        .newBuilder()
                                        .setParameterName("WinningTeamId")
                                        .setType(ParameterType.Number)
                                        .setNumberValue(winningTeam),
                                ),
                        ).build()
            },
        )

        return BundleResult(messages)
    }

    /**
     * Timer start: sends [TimerStateMessage] (GRE type 56) with Decision timer running.
     * Sent on priority grant — the client shows a rope countdown.
     */
    fun timerStart(
        counter: MessageCounter,
        durationSec: Int = 30,
    ): BundleResult = buildTimerBundle(counter, running = true, durationSec = durationSec)

    /**
     * Timer stop: sends [TimerStateMessage] with running=false.
     * Sent when client responds to an action (pass/cast/play).
     */
    fun timerStop(
        counter: MessageCounter,
        durationSec: Int = 30,
    ): BundleResult = buildTimerBundle(counter, running = false, durationSec = durationSec)

    private fun buildTimerBundle(
        counter: MessageCounter,
        running: Boolean,
        durationSec: Int,
    ): BundleResult {
        val timer =
            TimerStateMessage
                .newBuilder()
                .setSeatId(seatId)
                .addTimers(
                    TimerInfo
                        .newBuilder()
                        .setTimerId(1)
                        .setType(TimerType.Decision)
                        .setDurationSec(durationSec)
                        .setElapsedSec(0)
                        .setRunning(running)
                        .setBehavior(TimerBehavior.Timeout_a3cd),
                ).build()
        val msg =
            makeGRE(GREMessageType.TimerStateMessage_695e, counter.currentGsId(), counter.nextMsgId()) {
                it.timerStateMessage = timer
            }
        return BundleResult(listOf(msg))
    }

    /**
     * Build a bare echo diff GSM (empty Diff with just gsId chain + update type).
     *
     * **Where echoes fire.** State-only and remote-seat content-bearing
     * emissions append one of these. Same applies to the `selectTargets`
     * re-prompt cycle in `TargetingHandler.onSelectTargets`. The empirical
     * pattern is "one empty echo per content GSM, same updateType."
     *
     * **Where echoes do not fire.** Human-priority [postAction] bundles and
     * prompt-bearing bundles — [selectTargetsBundle], [selectNBundle],
     * [castingTimeOptionsBundle], [payCostsBundle], [declareAttackersBundle],
     * [declareBlockersBundle] — ship `[GSM, Request]` without a trailing echo.
     * Prompt re-entry frames carry their echo through `TargetingHandler`
     * instead of as a tag-along on the request bundle.
     */
    fun buildEchoDiffGsm(
        counter: MessageCounter,
        updateType: GameStateUpdate = GameStateUpdate.Send,
        previousGsId: Int? = null,
    ): GREToClientMessage {
        val link = counter.nextGameStateLink()
        val prev = previousGsId ?: link.prevGsId
        return makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) {
            it.gameStateMessage =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(link.gsId)
                    .setPrevGameStateId(prev)
                    .setUpdate(updateType)
                    .build()
        }
    }

    /** Explicitly remove a modal trigger ability synthesized for CastingTimeOptionsReq. */
    fun modalStackCleanup(
        counter: MessageCounter,
        abilityInstanceId: Int,
    ): GREToClientMessage {
        val link = counter.nextGameStateLink()
        return makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) {
            it.gameStateMessage =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(link.gsId)
                    .setPrevGameStateId(link.prevGsId)
                    .setUpdate(GameStateUpdate.Send)
                    .addDiffDeletedInstanceIds(abilityInstanceId)
                    .addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(ZoneIds.STACK)
                            .setType(ZoneType.Stack)
                            .setVisibility(Visibility.Public)
                            .build(),
                    ).build()
        }
    }

    /**
     * Resolve candidateRefs to Forge cards and build a surveil/scry bundle.
     *
     * Encapsulates card resolution (candidateRefs → Forge Card + instanceId) plus
     * bundle building (reveal diff + GroupReq) so callers don't need to do inline
     * card resolution. Returns null if no cards could be resolved from candidateRefs.
     *
     * @param candidateRefs prompt candidate references from [InteractivePromptBridge]
     * @param context whether this is surveil or scry
     * @param counter message counter for sequencing
     */
    fun resolveSurveilScryBundle(
        snapshot: GsmSnapshot,
        candidateRefs: List<PromptCandidateRefDto>,
        sourceEntityId: Int?,
        context: GroupingContext,
        counter: MessageCounter,
    ): BundleResult? {
        val resolved =
            candidateRefs
                .filter { it.isCard() }
                .mapNotNull { ref ->
                    val cardId = ForgeCardId(ref.entityId)
                    snapshot.objects[cardId]?.let { it to bridge.getOrAllocInstanceId(cardId).value }
                }
        if (resolved.isEmpty()) return null
        val topCardSnaps = resolved.map { it.first }
        val cardInstanceIds = resolved.map { it.second }
        val sourceId = sourceEntityId?.let { bridge.getOrAllocInstanceId(ForgeCardId(it)).value } ?: 0
        return surveilScryBundle(topCardSnaps, cardInstanceIds, sourceId, context, counter)
    }

    /**
     * Surveil/scry bundle: reveal diff (card objects with Private visibility) + GroupReq.
     *
     * Builds a GSM diff that exposes library top card(s) as `visibility=Private, viewers=[seatId]`
     * so the client shows them face-up in the surveil/scry modal, followed by a GroupReq.
     *
     * @param topCardSnaps snapshots for the cards being surveilled/scryed
     * @param cardInstanceIds instanceIds corresponding to [topCardSnaps]
     * @param sourceId instanceId of the triggering spell
     * @param context whether this is surveil or scry
     * @param counter message counter for sequencing
     */
    fun surveilScryBundle(
        topCardSnaps: List<CardSnapshot>,
        cardInstanceIds: List<Int>,
        sourceId: Int,
        context: GroupingContext,
        counter: MessageCounter,
    ): BundleResult {
        val libZoneId = ZoneIds.libraryOf(seatId)
        val revealedObjects =
            topCardSnaps.zip(cardInstanceIds).map { (cardSnap, iid) ->
                ObjectMapper
                    .buildFromSnapshot(cardSnap, iid, libZoneId, seatId, bridge.cardProto, Visibility.Private)
                    .toBuilder()
                    .addViewers(seatId)
                    .build()
            }
        val gsId = counter.nextGsId()
        val revealDiff =
            makeGRE(GREMessageType.GameStateMessage_695e, gsId, counter.nextMsgId()) {
                it.gameStateMessage =
                    GameStateMessage
                        .newBuilder()
                        .setType(GameStateType.Diff)
                        .setGameStateId(gsId)
                        .setPrevGameStateId(gsId - 1)
                        .addAllGameObjects(revealedObjects)
                        .build()
            }

        val groupReq =
            GsmBuilder.buildSurveilScryGroupReq(
                msgId = counter.nextMsgId(),
                gameStateId = gsId,
                seatId = seatId,
                cardInstanceIds = cardInstanceIds,
                context = context,
                sourceInstanceId = sourceId,
            )
        return BundleResult(listOf(revealDiff, groupReq))
    }

    /** Build a single GRE message. */

    private fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val gre =
            GREToClientMessage
                .newBuilder()
                .setType(type)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .addSystemSeatIds(seatId)
        configure(gre)
        return gre.build()
    }

    internal fun coinFlipPromptMessages(
        events: List<GameEvent>,
        gsId: Int,
        counter: MessageCounter,
    ): List<GREToClientMessage> =
        events.filterIsInstance<GameEvent.CoinFlipped>().map { event ->
            makeGRE(GREMessageType.PromptReq, gsId, counter.nextMsgId()) {
                it.setPrompt(
                    Prompt
                        .newBuilder()
                        .setPromptId(PromptIds.COIN_FLIP)
                        .addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("PlayerId")
                                .setType(ParameterType.Reference_a14a)
                                .setReference(
                                    Reference
                                        .newBuilder()
                                        .setType(ReferenceType.PlayerSeatId)
                                        .setId(event.flipperSeatId.value),
                                ),
                        ).addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("CoinFlipResult")
                                .setType(ParameterType.Reference_a14a)
                                .setReference(
                                    Reference
                                        .newBuilder()
                                        .setType(ReferenceType.LocalizationId)
                                        .setId(if (event.result == 1) COIN_FLIP_WIN_LOCALIZATION_ID else COIN_FLIP_LOSS_LOCALIZATION_ID),
                                ),
                        ).build(),
                )
            }
        }

    companion object {
        private const val COIN_FLIP_WIN_LOCALIZATION_ID = 47
        private const val COIN_FLIP_LOSS_LOCALIZATION_ID = 48

        /**
         * Pure function — no instance state needed. Checks if the only action
         * available is Pass (no Cast, Play, Activate).
         */
        fun shouldAutoPass(actions: ActionsAvailableReq): Boolean =
            actions.actionsList.all { !ShouldStopEvaluator.shouldStop(it.actionType) }

        /**
         * True when the drained [events] describe a turn-boundary or trigger-driven
         * draw — one that should be emitted as [GameStateUpdate.SendHiFi] rather
         * than the default [GameStateUpdate.SendAndRecord].
         *
         * The wire contract (bead leyline-pey) marks spell-driven draws in Main1
         * (Divination, Opt, etc.) as `SendAndRecord`, but turn-boundary auto-draws
         * and upkeep-triggered draws as `SendHiFi`. This helper detects the
         * latter by requiring all of:
         *
         * 1. A Library→Hand [GameEvent.ZoneChanged] whose card owner is the
         *    active seat.
         * 2. No [GameEvent.SpellCast] for that seat in the same bundle
         *    (filters out cast-Divination-draw chains).
         * 3. No [GameEvent.SpellResolved] for that seat in the same bundle
         *    (filters out resolve-Divination-draw chains).
         * 4. The snapshot phase is UPKEEP, DRAW, or MAIN1 — the window leyline
         *    bundles the auto-draw into (MAIN1 covers the common case where the
         *    DRAW step's card move lands in the first MAIN1 priority grant's
         *    GSM).
         * 5. A non-null snapshot phase — fall back to the default updateType
         *    when phase is unknown.
         */
        internal fun isTurnOrTriggerDraw(
            events: List<GameEvent>,
            snap: GsmSnapshot,
            activeSeat: SeatId,
        ): Boolean {
            val phase = snap.phase.phase ?: return false
            if (phase != PhaseType.UPKEEP && phase != PhaseType.DRAW && phase != PhaseType.MAIN1) return false

            val hasActiveSeatDraw =
                events.any { ev ->
                    ev is GameEvent.ZoneChanged &&
                        ev.from == Zone.Library &&
                        ev.to == Zone.Hand &&
                        snap.objects[ev.cardId]?.owner == activeSeat
                }
            if (!hasActiveSeatDraw) return false

            val hasActiveSeatSpellCast =
                events.any { ev -> ev is GameEvent.SpellCast && ev.seatId == activeSeat && !ev.isTrigger }
            if (hasActiveSeatSpellCast) return false

            val hasActiveSeatSpellResolved =
                events.any { ev ->
                    ev is GameEvent.SpellResolved && snap.objects[ev.cardId]?.owner == activeSeat
                }
            if (hasActiveSeatSpellResolved) return false

            return true
        }
    }
}
