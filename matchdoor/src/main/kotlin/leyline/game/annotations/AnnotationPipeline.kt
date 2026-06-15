package leyline.game.annotations

import leyline.bridge.types.EffectId
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PersistentFeedSet
import leyline.game.mapping.SourceAbilityResolverFactory
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustedKind
import leyline.game.state.AbilityWireIdentity
import leyline.game.state.AbilityWordActiveKind
import leyline.game.state.ColorProductionKind
import leyline.game.state.CommanderDesignationKind
import leyline.game.state.CrewedThisTurnKind
import leyline.game.state.DayNightDesignationKind
import leyline.game.state.DelayedTriggerAffecteesKind
import leyline.game.state.EffectTracker
import leyline.game.state.FaceDownDisguiseKind
import leyline.game.state.FrameContext
import leyline.game.state.GameBridge
import leyline.game.state.LeftUnlockedDesignationKind
import leyline.game.state.LinkInfoChoiceKind
import leyline.game.state.ManaCreatureDesignationKind
import leyline.game.state.ManaDetailsKind
import leyline.game.state.ModifiedTypeForCrewKind
import leyline.game.state.MutateLayeredEffectKind
import leyline.game.state.PendingTargetSpecRecord
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
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Hosts the diff → annotations spine lifted out of StateMapper.
 *
 * Two entry points feed [StateMapper.buildFromSnapshot]:
 * - [computeAnnotations] — stages 1-3: transfer + combat + trigger-lifecycle
 *   assembly into a transient/persistent [AnnotationPipelineResult].
 * - [computeRemainingAnnotations] — stages 4-5: mechanic + effect annotations,
 *   persistent-store batch computation, and final numbering.
 *
 * The shared annotation-time resolvers live on [AnnotationContext]; the
 * per-mechanic emitters live as plain functions in `AnnotationEmitters.kt` and
 * are still called directly by the spine. The [contributors] registry is the
 * extension point those emitters move behind in a later slice — empty for now.
 *
 * Transfer-model patchers (decayed-cleanup, delayed-trigger holders, reveal
 * proxies, redaction) deliberately stay in StateMapper: they reassign the
 * transfer result / mutate GSM zones and flow into GSM assembly, so they belong
 * to the mapper's mutation boundary rather than this annotation host.
 */
// Carries the per-mechanic emitter wiring inline for now; the emitter-port slice
// moves those calls onto the [contributors] registry, which shrinks this host.
@Suppress("LargeClass")
object AnnotationPipeline {
    /**
     * Registered mechanic contributors, ordered by [AnnotationContributor.rank].
     *
     * The spine invokes each contributor at its phase-correct site rather than as
     * one flat rank-sorted block: both the transient ordering and the shared
     * effect-id allocator (`effects.nextEffectId()`, drawn by crew / reconfigure /
     * mutate-merge alike) are load-bearing, so call order pins emitted ids and
     * positions. [rank] documents that canonical order; the registry gives the
     * boundary fitness function a single list to assert over.
     */
    val contributors: List<AnnotationContributor> =
        listOf(
            TargetSpecContributor,
            MutateMergeContributor,
            VehicleAttachContributor,
        )

    /** Result of stages 4-5 + persistent annotation computation. */
    internal data class RemainingAnnotationsResult(
        val numbered: List<AnnotationInfo>,
        val persistent: List<AnnotationInfo>,
        val batch: PersistentAnnotationStore.BatchResult,
        val nextAnnotationId: Int,
        val consumedTargetSpecs: List<PendingTargetSpecRecord>,
    )

    /** Stages 2-3 of the annotation pipeline: transfers → annotations + combat. */
    internal data class AnnotationPipelineResult(
        val annotations: MutableList<AnnotationInfo>,
        val transferPersistent: MutableList<AnnotationInfo>,
        val combatResult: CombatAnnotationResult,
    )

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
                    .map { it.cardId to AnnotationContext.stackAbilityIid(it.abilityForgeId, it.cardId, frameIds) }
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
                val ctx = AnnotationContext(bridge, snap, frameIds, events)
                events
                    .filterIsInstance<GameEvent.SpellCast>()
                    .associate { cast ->
                        val abilityIid = ctx.stackAbilityIid(cast.abilityForgeId, cast.cardId)
                        val grpId =
                            cast.abilityGrpId.takeIf { it != 0 }
                                ?: ctx.abilityGrpIdForSource(cast.cardId, cast.abilityForgeId)
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
            val ctx = AnnotationContext(bridge, snap, frameIds ?: FrameIdResolver(bridge), events)
            emitTriggerLifecycleAnnotations(
                ctx = ctx,
                snapshotSourceIids = snapshotSourceIids,
                snapshotAppearanceIids = snapshotAppearanceIids,
                snapshotDisappearanceIids = snapshotDisappearanceIids,
                annotations = annotations,
                transferPersistent = transferPersistent,
            )
            insertResolutionEventAnnotations(ctx, annotations)
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

    private fun insertResolutionEventAnnotations(
        ctx: AnnotationContext,
        annotations: MutableList<AnnotationInfo>,
    ) {
        val events = ctx.events
        val payloadsByAffector = linkedMapOf<Int, MutableList<AnnotationInfo>>()
        val unmatched = mutableListOf<AnnotationInfo>()

        fun addPayload(
            affectorId: Int,
            annotation: AnnotationInfo,
        ) {
            if (affectorId == 0) {
                unmatched.add(annotation)
            } else {
                payloadsByAffector.getOrPut(affectorId) { mutableListOf() }.add(annotation)
            }
        }

        events.forEachIndexed { index, event ->
            when (event) {
                is GameEvent.CoinFlipped -> {
                    val affector = ctx.stackAbilityIid(event.abilityForgeId, event.sourceCardId)
                    addPayload(
                        affector,
                        AnnotationBuilder.coinFlip(InstanceId(affector), event.flipperSeatId, event.result),
                    )
                }
                is GameEvent.LifeChanged -> {
                    val delta = event.newLife - event.oldLife
                    if (delta == 0 || isCoveredByDamageEvent(event, events)) return@forEachIndexed
                    val resolved = nextResolvedAbility(events, index)
                    val affector =
                        resolved?.let { ctx.stackAbilityIid(it.abilityForgeId, it.cardId) }
                            ?: previousActivatedAbility(events, index)?.let {
                                ctx.stackAbilityIid(it.abilityForgeId, it.cardId)
                            }
                            ?: 0
                    addPayload(
                        affector,
                        AnnotationBuilder.modifiedLife(event.seatId, delta, InstanceId(affector).takeIf { affector != 0 }),
                    )
                }
                else -> Unit
            }
        }

        if (payloadsByAffector.isEmpty() && unmatched.isEmpty()) return
        val ordered = mutableListOf<AnnotationInfo>()
        for (annotation in annotations) {
            ordered.add(annotation)
            if (AnnotationType.ResolutionStart in annotation.typeList) {
                payloadsByAffector.remove(annotation.affectorId)?.let(ordered::addAll)
            }
        }
        payloadsByAffector.values.forEach(ordered::addAll)
        ordered.addAll(unmatched)
        annotations.clear()
        annotations.addAll(ordered)
    }

    private fun nextResolvedAbility(
        events: List<GameEvent>,
        afterIndex: Int,
    ): GameEvent.SpellResolved? =
        events
            .asSequence()
            .drop(afterIndex + 1)
            .filterIsInstance<GameEvent.SpellResolved>()
            .firstOrNull { it.isTrigger || it.isAbility }

    private fun previousActivatedAbility(
        events: List<GameEvent>,
        beforeIndex: Int,
    ): GameEvent.SpellCast? =
        events
            .asSequence()
            .take(beforeIndex)
            .filterIsInstance<GameEvent.SpellCast>()
            .lastOrNull { it.isAbility || it.isTrigger }

    private fun isCoveredByDamageEvent(
        life: GameEvent.LifeChanged,
        events: List<GameEvent>,
    ): Boolean =
        events.any { event ->
            event is GameEvent.DamageDealtToPlayer &&
                event.targetSeatId == life.seatId &&
                life.oldLife - life.newLife == event.amount
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
        ctx: AnnotationContext,
        snapshotSourceIids: Set<Int>,
        snapshotAppearanceIids: Set<Int>,
        snapshotDisappearanceIids: Set<Int>,
        annotations: MutableList<AnnotationInfo>,
        transferPersistent: MutableList<AnnotationInfo>,
    ) {
        val events = ctx.events
        val bridge = ctx.bridge
        val snap = ctx.snap
        val frameIds = ctx.frameIds
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
            val abilityIid = ctx.stackAbilityIid(cast.abilityForgeId, cast.cardId)
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
                    cast.activationZoneId.takeIf { it != 0 } ?: ctx.currentSourceZoneId(cast.cardId)
                }

            if (abilityIid in snapshotAppearanceIids || sourceCardIid in snapshotSourceIids) continue
            bridge.abilityLineage.record(
                AbilityWireIdentity(
                    abilityIid = abilityIid,
                    sourceIidAtCreate = sourceCardIid,
                    sourceZoneAtCreate = sourceZone,
                    abilityGrpId =
                        cast.abilityGrpId.takeIf { it != 0 }
                            ?: ctx.abilityGrpIdForSource(cast.cardId, cast.abilityForgeId),
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
            val abilityIid = ctx.stackAbilityIid(cast.abilityForgeId, cast.cardId)
            val sourceZone =
                if (cast.activationZoneId != 0) cast.activationZoneId else ctx.currentSourceZoneId(cast.cardId)

            if (abilityIid in snapshotAppearanceIids || sourceCardIid in snapshotSourceIids) continue
            bridge.abilityLineage.record(
                AbilityWireIdentity(
                    abilityIid = abilityIid,
                    sourceIidAtCreate = sourceCardIid,
                    sourceZoneAtCreate = sourceZone,
                    abilityGrpId =
                        cast.abilityGrpId.takeIf { it != 0 }
                            ?: ctx.abilityGrpIdForSource(cast.cardId, cast.abilityForgeId),
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
            val abilityIid = ctx.stackAbilityIid(resolved.abilityForgeId, resolved.cardId)
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
                    ?: ctx.abilityGrpIdForSource(resolved.cardId, resolved.abilityForgeId)

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

    private fun buildChoiceResultAnnotations(
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        bridge.allSeatIds().sorted().flatMap { seatValue ->
            bridge
                .promptBridge(SeatId(seatValue))
                .journal
                .drainChoiceResults()
                .map { result ->
                    AnnotationBuilder.choiceResult(
                        sourceInstanceId = frameIds.cardIid(result.sourceForgeCardId),
                        chooserSeatId = result.chooserSeatId,
                        choiceValue = result.choiceValue,
                        choiceDomain = result.choiceDomain,
                        sentiment = result.sentiment,
                    )
                }
        }

    // Local EffectTracker.DiffResult patch consumed only by the persistent-store
    // batch below. Synthesizes destroyed entries for earthbend layers so the
    // store emits LayeredEffectDestroyed for them. Unlike the transfer-model
    // patchers in StateMapper this does not touch the transfer result or GSM
    // zones, so it lives with the spine that consumes it.
    private fun EffectTracker.DiffResult.withDestroyedEarthbendLayers(layerIds: List<Int>): EffectTracker.DiffResult {
        if (layerIds.isEmpty()) return this
        val destroyed =
            layerIds.map { layerId ->
                EffectTracker.TrackedEffect(
                    syntheticId = layerId,
                    fingerprint = EffectTracker.EffectFingerprint(cardInstanceId = 0, timestamp = layerId.toLong(), staticId = 0L),
                    powerDelta = 0,
                    toughnessDelta = 0,
                )
            }
        return copy(destroyed = this.destroyed + destroyed)
    }

    /** Stages 4-5: mechanic + effect annotations, persistent computation, numbering. */
    @Suppress("LongParameterList", "LongMethod")
    internal fun computeRemainingAnnotations(
        ctx: AnnotationContext,
        annotations: MutableList<AnnotationInfo>,
        transferPersistent: List<AnnotationInfo>,
        initEffectDiff: EffectTracker.DiffResult,
        effectDiff: EffectTracker.DiffResult,
        persistSnapshot: Map<Int, AnnotationInfo>,
        startPersistentId: Int,
        startAnnotationId: Int,
        frameContext: FrameContext,
        keywordDiff: EffectTracker.KeywordDiffResult = EffectTracker.KeywordDiffResult(emptyList(), emptyList()),
        combatResult: CombatAnnotationResult = CombatAnnotationResult(emptyList()),
        persistentFeeds: PersistentFeedSet = PersistentFeedSet(),
        convokePaymentsBySource: Map<ForgeCardId, List<TransferAnnotations.ConvokePaymentRecord>> = emptyMap(),
        transferResult: TransferResult,
    ): RemainingAnnotationsResult {
        val events = ctx.events
        val bridge = ctx.bridge
        val snap = ctx.snap
        val frameIds = ctx.frameIds
        val castSpellManaForgeIds =
            events
                .filterIsInstance<GameEvent.SpellCast>()
                .flatMap { it.manaPayments.map { mp -> mp.sourceCardId } }
                .toSet()
        val convokePaymentForgeIds = convokePaymentsBySource.values.flatten().mapTo(mutableSetOf()) { it.paymentForgeCardId }
        val sacrificedManaForgeIds =
            events
                .filterIsInstance<GameEvent.ManaAbilityActivated>()
                .filter { ma -> events.any { it is GameEvent.CardSacrificed && it.cardId == ma.cardId } }
                .map { it.cardId }
                .toSet()
        val manaPaidForgeCardIds = castSpellManaForgeIds + sacrificedManaForgeIds + convokePaymentForgeIds
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
                counterAffectorResolver = { eventIndex, ev -> ctx.counterAffectorFor(eventIndex, ev) },
                playerCounterAffectorResolver = { eventIndex, ev -> ctx.playerCounterAffectorFor(eventIndex, ev) },
                stackInstanceResolver = { ev -> castStackIidsByCard[ev.cardId] },
                castSpellTransferCardIds = castSpellTransferCardIds,
                convokePaymentsBySource = convokePaymentsBySource,
            )
        val earthbendFrame = bridge.drainEarthbendFrame()
        val earthbendDestroyed = earthbendFrame.destroyedLayerIds.map { AnnotationBuilder.layeredEffectDestroyed(EffectId(it)) }
        val earthbendCreated = earthbendCreatedAnnotations(earthbendFrame.created)
        val earthbendPersistent = earthbendPersistentAnnotations(earthbendFrame.created)
        val earthbendDesignations = earthbendDesignationAnnotations(earthbendFrame.active, snap)
        val earthbendPowerToughnessMods = earthbendPowerToughnessMods(earthbendFrame.created, snap)
        annotations.addAll(earthbendDestroyed)
        annotations.addAll(earthbendCreated)
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
        annotations.addAll(earthbendPowerToughnessMods)
        annotations.addAll(buildChoiceResultAnnotations(bridge, frameIds))

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
                                InstanceId(ctx.stackAbilityIid(resolved.abilityForgeId, resolved.cardId))
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
        val pendingTargetSpecs = bridge.snapshotPendingTargetSpecs()
        val targetSpec = TargetSpecContributor.contribute(ctx)
        val mutateMerge = MutateMergeContributor.contribute(ctx)
        val abilityExhaustedPersistent = buildAbilityExhaustedAnnotations(snap, bridge, frameIds)
        annotations.addAll(mutateMerge.transient)

        // Vehicle/Attach (Crew + Saddle + Reconfigure) — invoked here so its
        // crew/reconfigure effect-id allocations follow mutate-merge's on the
        // shared counter, preserving emitted effect ids.
        val vehicleAttach = VehicleAttachContributor.contribute(ctx)
        annotations.addAll(vehicleAttach.transient)

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
                        put(CrewedThisTurnKind, vehicleAttach.persistent[CrewedThisTurnKind].orEmpty())
                        put(SaddledThisTurnKind, vehicleAttach.persistent[SaddledThisTurnKind].orEmpty())
                        put(ModifiedTypeForCrewKind, vehicleAttach.persistent[ModifiedTypeForCrewKind].orEmpty())
                        put(TemporaryPermanentKind, temporaryPermanentPersistent)
                        put(DelayedTriggerAffecteesKind, delayedTriggerAffecteesPersistent)
                        put(TargetSpecKind, targetSpec.persistent[TargetSpecKind].orEmpty())
                        put(MutateLayeredEffectKind, mutateMerge.persistent[MutateLayeredEffectKind].orEmpty())
                        put(PreparedDesignationKind, persistentFeeds.preparedDesignation)
                        put(PlottedDesignationKind, persistentFeeds.plottedDesignation)
                        put(CommanderDesignationKind, persistentFeeds.commanderDesignation)
                        put(SaddledDesignationKind, persistentFeeds.saddledDesignation)
                        put(LeftUnlockedDesignationKind, persistentFeeds.leftUnlockedDesignation)
                        put(RightUnlockedDesignationKind, persistentFeeds.rightUnlockedDesignation)
                        put(ManaCreatureDesignationKind, earthbendDesignations)
                        put(DayNightDesignationKind, persistentFeeds.dayNightDesignation)
                        put(FaceDownDisguiseKind, persistentFeeds.faceDownDisguise)
                        put(ColorProductionKind, persistentFeeds.colorProduction)
                        put(LinkInfoChoiceKind, persistentFeeds.linkInfo)
                        put(ManaDetailsKind, buildManaDetailsAnnotations(snap))
                        put(AbilityExhaustedKind, abilityExhaustedPersistent)
                    },
            )
        val batch =
            PersistentAnnotationStore.Companion.computeBatch(
                currentActive = persistSnapshot,
                startPersistentId = startPersistentId,
                frame = frameContext,
                effectPersistent = effectPersistent + earthbendPersistent,
                effectDiff = effectDiff.withDestroyedEarthbendLayers(earthbendFrame.destroyedLayerIds),
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
        return RemainingAnnotationsResult(
            numbered = numbered,
            persistent = batch.allAnnotations,
            batch = batch,
            nextAnnotationId = annId,
            consumedTargetSpecs = pendingTargetSpecs,
        )
    }
}
