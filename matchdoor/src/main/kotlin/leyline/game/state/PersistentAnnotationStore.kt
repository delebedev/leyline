package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.CombatAnnotationResult
import leyline.game.annotations.MechanicAnnotationResult
import leyline.game.codes.DetailKeys
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Manages persistent and transient annotation ID sequences, plus the
 * persistent annotation lifecycle (carry-forward across GSMs).
 *
 * ## Lifecycle
 *
 * Persistent annotations represent ongoing game state visible to the client:
 * attachments, counters, layered effects, controller changes, exile-under-card,
 * and entered-zone-this-turn markers. The server-side store accumulates them
 * across GSMs until explicitly removed; the wire shape is differential —
 * [leyline.game.mapping.StateMapper.buildDiff] emits only newly-added IDs on
 * each Diff GSM, and [leyline.game.mapping.StateMapper.buildFromSnapshot]
 * carries the full list on Full GSMs. Removals flow through
 * `diffDeletedPersistentAnnotationIds` (see [drainDeletions]).
 *
 * **Create:** New persistent annotations originate from four sources, processed
 * in [computeBatch] in this order:
 *   1. **Effect lifecycle** — [EffectTracker] creates/destroys LayeredEffect pAnns
 *   2. **Transfer-originated** — zone transfers produce EnteredZoneThisTurn pAnns
 *   3. **Mechanic-originated** — counters (upsert: old deleted, new created),
 *      attachments, DisplayCardUnderCard, ControllerChanged+LayeredEffect
 *   4. **Cleanup** — detached auras, exile sources leaving play, controller reverts
 *
 * **Carry forward:** [computeBatch] starts from the current [snapshot] — all
 * existing pAnns survive unless a step explicitly removes them. The snapshot
 * is taken *before* the COMPUTE phase in [leyline.game.mapping.StateMapper.buildFromSnapshot], so the
 * batch sees the previous GSM's state.
 *
 * **Replace-on-update:** Counters use upsert semantics — when a counter of
 * the same type on the same instanceId already exists, the old pAnn is deleted
 * and a new one created with a fresh ID and updated value.
 *
 * **Delete:** Removal happens within [computeBatch] for four reasons:
 *   - Effect destroyed (LayeredEffect pAnn matched by effect_id)
 *   - Counter upsert (old counter replaced by new)
 *   - Aura detached (Attachment pAnn matched by affectorId)
 *   - Exile source left play (DisplayCardUnderCard matched by reverse forgeCardId lookup)
 *   - Controller reverted (ControllerChanged+LayeredEffect matched by affectedIds)
 *
 * **Drain:** [drainDeletions] returns IDs deleted since last drain, for the
 * GSM's `diffDeletedPersistentAnnotationIds` field. Called once per GSM in
 * [leyline.game.mapping.StateMapper.buildDiff]. The drain-then-clear pattern means each
 * deletion ID appears in exactly one GSM.
 *
 * ## ID allocation
 *
 * Two independent monotonic counters: transient IDs start at [INITIAL_ANNOTATION_ID]
 * (50), persistent IDs start at [INITIAL_PERSISTENT_ANNOTATION_ID] (1). The gap
 * avoids collisions — transient annotations are numbered after persistent ones
 * are assigned IDs in [computeBatch].
 *
 * ## Threading
 *
 * All access is single-threaded (engine thread via StateMapper). No synchronization.
 *
 * Composed into [GameBridge] alongside [InstanceIdRegistry], [LimboTracker],
 * [DiffSnapshotter], and [EffectTracker].
 */
class PersistentAnnotationStore {
    /** Result of a pure [computeBatch] invocation. */
    data class BatchResult(
        /** All persistent annotations after applying the batch (for GSM embedding). */
        val allAnnotations: List<AnnotationInfo>,
        /** IDs of annotations deleted during this batch (for diffDeletedPersistentAnnotationIds). */
        val deletedIds: List<Int>,
        /** Counter value after numbering all new persistent annotations. */
        val nextPersistentId: Int,
        /** Effect IDs from controller-change reverts — caller emits LayeredEffectDestroyed for each. */
        val revertedEffectIds: List<Int> = emptyList(),
    )

    companion object {
        /** Transient annotation IDs start at 50 to avoid collision with persistent IDs. */
        const val INITIAL_ANNOTATION_ID = 50

        /** Persistent annotation IDs start at 1. */
        const val INITIAL_PERSISTENT_ANNOTATION_ID = 1

        /**
         * Pure batch computation — operates on an immutable snapshot and
         * returns the result. Caller applies via [applyBatchResult].
         *
         * **Ordering invariant:** Steps 0-6 execute in fixed order. Lifecycle
         * expiry (0) runs before transfers (2) so newly arrived rows in the
         * same frame survive. Effects (1) before transfers (2) before mechanic
         * upserts (3) because a counter upsert must not collide with a
         * LayeredEffect ID allocated in step 1. Cleanup (4-6) runs last so it
         * sees the full set of newly added pAnns — e.g. step 4 (detach) can
         * remove an Attachment just created in step 3 if the aura was
         * simultaneously destroyed.
         *
         * Per-kind upsert behavior (identity, stale-prune, collision strategy,
         * lifecycle expiry) lives on [PersistentAnnotationKind] rows in
         * [PersistentAnnotationKinds.upsertable] / `lifecycleOnly`. Adding a
         * new kind is now one row, not a parallel branch through the body
         * here.
         *
         * **Snapshot timing:** [currentActive] must be a value-snapshot taken
         * *before* the annotation pipeline runs.
         * [leyline.game.mapping.StateMapper.buildFromSnapshot] reads it in the
         * GATHER phase so the COMPUTE phase (which calls this) is pure.
         *
         * @param frame phase / active-player / battlefield-iids; drives
         *   [PersistentAnnotationKind.shouldExpire] (EZTT clears at Upkeep,
         *   ColorProduction clears when source iid leaves the battlefield).
         * @param resolveForgeCardId reverse-resolves instanceId → forgeCardId.
         *   Used by step 5 to match DisplayCardUnderCard annotations whose
         *   affectorId may have been reallocated by a zone transfer. The
         *   registry retains mappings for retired iids, so this works even
         *   after reallocation.
         */
        @Suppress("LongParameterList", "LongMethod")
        fun computeBatch(
            currentActive: Map<Int, AnnotationInfo>,
            startPersistentId: Int,
            frame: FrameContext = FrameContext.INERT,
            effectPersistent: List<AnnotationInfo>,
            effectDiff: EffectTracker.DiffResult,
            transferPersistent: List<AnnotationInfo>,
            mechanicResult: MechanicAnnotationResult,
            combatResult: CombatAnnotationResult = CombatAnnotationResult(emptyList()),
            resolveInstanceId: (ForgeCardId) -> InstanceId,
            resolveForgeCardId: (InstanceId) -> ForgeCardId? = { null },
        ): BatchResult {
            val active = currentActive.toMutableMap()
            val deletions = mutableListOf<Int>()
            var nextId = startPersistentId

            // 0. Lifecycle expiry — drop rows whose shouldExpire fires this frame.
            //    Closes leyline-eq9q: EZTT clears at Upkeep, ColorProduction
            //    clears when its source iid leaves the battlefield.
            for (kind in PersistentAnnotationKinds.all) {
                val expiredIds =
                    active.entries
                        .filter { (_, ann) -> kind.matches(ann) && kind.shouldExpire(ann, frame) }
                        .map { it.key }
                for (id in expiredIds) {
                    active.remove(id)
                    deletions.add(id)
                }
            }

            // 1. Effect lifecycle
            for (ann in effectPersistent) {
                val numbered = ann.toBuilder().setId(nextId++).build()
                active[numbered.id] = numbered
            }
            for (effect in effectDiff.destroyed) {
                val annId = findByEffectId(active, effect.syntheticId)
                if (annId != null) {
                    active.remove(annId)
                    deletions.add(annId)
                }
            }

            // 2. Transfer-originated (EZTT, ColorProduction, CastingTimeOption)
            for (ann in transferPersistent) {
                val numbered = ann.toBuilder().setId(nextId++).build()
                active[numbered.id] = numbered
            }

            // 3a. Mechanic-originated mixed list (Counter + Attachment +
            //     DisplayCardUnderCard + ControllerChangedEffect). Counter
            //     rows go through CounterKind's REPLACE_ALWAYS collision
            //     handling; non-Counter rows pure-append since their lifecycle
            //     is cleanup-driven (steps 4-6).
            for (ann in mechanicResult.persistent) {
                if (CounterKind.matches(ann)) {
                    val key = CounterKind.identityKey(ann)
                    if (key != null) {
                        val existingId =
                            active.entries
                                .firstOrNull { (_, e) -> CounterKind.matches(e) && CounterKind.identityKey(e) == key }
                                ?.key
                        if (existingId != null) {
                            active.remove(existingId)
                            deletions.add(existingId)
                        }
                    }
                }
                val numbered = ann.toBuilder().setId(nextId++).build()
                active[numbered.id] = numbered
            }

            // 3b-3j. Other registry-driven kinds — full upsert dispatch via
            //        identity + collision strategy declared on each row.
            val perKindIncoming: Map<PersistentAnnotationKind, List<AnnotationInfo>> =
                mapOf(
                    AbilityWordActiveKind to mechanicResult.abilityWordPersistent,
                    QualificationKind to mechanicResult.qualificationPersistent,
                    CrewedThisTurnKind to mechanicResult.crewedThisTurnPersistent,
                    ModifiedTypeForCrewKind to mechanicResult.crewTypeChangePersistent,
                    TemporaryPermanentKind to mechanicResult.temporaryPermanentPersistent,
                    DelayedTriggerAffecteesKind to mechanicResult.delayedTriggerAffecteesPersistent,
                    TargetSpecKind to mechanicResult.targetSpecPersistent,
                    PreparedDesignationKind to mechanicResult.preparedDesignationPersistent,
                    PlottedDesignationKind to mechanicResult.plottedDesignationPersistent,
                )
            for (kind in PersistentAnnotationKinds.upsertable) {
                if (kind === CounterKind) continue // Counter handled above with mechanicResult.persistent.
                nextId = upsertByKind(active, deletions, nextId, kind, perKindIncoming[kind] ?: emptyList())
            }

            // 3h. DamagedThisTurn — grow-in-place / clear (single annotation,
            //     not a per-iid upsert; can't fold into the registry).
            nextId =
                updateDamagedThisTurn(
                    active,
                    deletions,
                    nextId,
                    combatResult.damagedThisTurnPersistent,
                    combatResult.clearDamagedThisTurn,
                )

            // 4-6. Cleanup: detached auras, exile sources, controller reverts
            val cleanupReverts =
                cleanupDetachedAndReverted(
                    active,
                    deletions,
                    mechanicResult,
                    resolveInstanceId,
                    resolveForgeCardId,
                )

            return BatchResult(active.values.toList(), deletions, nextId, cleanupReverts)
        }

        /**
         * Generic identity-keyed upsert dispatch driven by a [PersistentAnnotationKind].
         *
         * Stale-prune: when [PersistentAnnotationKind.pruneStale] is true,
         * active rows of this kind whose identity isn't in the incoming set
         * get removed (full-replacement semantics — AbilityWord, Designation
         * rails, etc.).
         *
         * Collision: per [PersistentAnnotationKind.collisionStrategy], decide
         * whether to keep the existing row, replace if details differ, or
         * always replace (Counter — fresh id every collision; Counter is
         * dispatched separately by [computeBatch] to preserve legacy
         * id-allocation order and let non-Counter mechanicResult.persistent
         * rows interleave).
         */
        private fun upsertByKind(
            active: MutableMap<Int, AnnotationInfo>,
            deletions: MutableList<Int>,
            startId: Int,
            kind: PersistentAnnotationKind,
            incoming: List<AnnotationInfo>,
        ): Int {
            var nextId = startId
            val incomingByKey = mutableMapOf<Any, AnnotationInfo>()
            for (ann in incoming) {
                val key = kind.identityKey(ann) ?: continue
                incomingByKey[key] = ann
            }

            if (kind.pruneStale) {
                val staleIds =
                    active.entries
                        .filter { (_, ann) ->
                            kind.matches(ann) &&
                                (kind.identityKey(ann)?.let { it !in incomingByKey.keys } ?: false)
                        }.map { it.key }
                for (id in staleIds) {
                    active.remove(id)
                    deletions.add(id)
                }
            }

            for ((key, ann) in incomingByKey) {
                val existingEntry =
                    active.entries.firstOrNull { (_, e) ->
                        kind.matches(e) && kind.identityKey(e) == key
                    }
                if (existingEntry == null) {
                    val numbered = ann.toBuilder().setId(nextId++).build()
                    active[numbered.id] = numbered
                    continue
                }
                when (kind.collisionStrategy) {
                    CollisionStrategy.KEEP_EXISTING -> Unit
                    CollisionStrategy.REPLACE_IF_CHANGED -> {
                        if (existingEntry.value.detailsList != ann.detailsList) {
                            active.remove(existingEntry.key)
                            deletions.add(existingEntry.key)
                            val numbered = ann.toBuilder().setId(nextId++).build()
                            active[numbered.id] = numbered
                        }
                    }
                    CollisionStrategy.REPLACE_ALWAYS -> {
                        active.remove(existingEntry.key)
                        deletions.add(existingEntry.key)
                        val numbered = ann.toBuilder().setId(nextId++).build()
                        active[numbered.id] = numbered
                    }
                }
            }

            return nextId
        }

        /** Steps 4-6: remove pAnns for detached auras, exile sources leaving play, controller reverts. */
        private fun cleanupDetachedAndReverted(
            active: MutableMap<Int, AnnotationInfo>,
            deletions: MutableList<Int>,
            mechanicResult: MechanicAnnotationResult,
            resolveInstanceId: (ForgeCardId) -> InstanceId,
            resolveForgeCardId: (InstanceId) -> ForgeCardId?,
        ): List<Int> {
            val revertedEffectIds = mutableListOf<Int>()

            // 4. Detached auras
            for (forgeCardId in mechanicResult.detachedForgeCardIds) {
                val auraIid = resolveInstanceId(forgeCardId).value
                val annId = findByAura(active, auraIid)
                if (annId != null) {
                    active.remove(annId)
                    deletions.add(annId)
                }
            }

            // 5. Exile source left play — remove DisplayCardUnderCard
            val leftPlayForgeIds = mechanicResult.exileSourceLeftPlayForgeCardIds.toSet()
            if (leftPlayForgeIds.isNotEmpty()) {
                for (annId in findExileSourcesLeavingPlay(active, leftPlayForgeIds, resolveForgeCardId)) {
                    active.remove(annId)
                    deletions.add(annId)
                }
            }

            // 6. Controller-change revert
            for (forgeCardId in mechanicResult.controllerRevertedForgeCardIds) {
                val cardIid = resolveInstanceId(forgeCardId).value
                val annId = findControllerChanged(active, cardIid)
                if (annId != null) {
                    val ann = active[annId]
                    active.remove(annId)
                    deletions.add(annId)
                    val effectId =
                        ann
                            ?.detailsList
                            ?.firstOrNull { it.key == DetailKeys.EFFECT_ID && it.valueInt32Count > 0 }
                            ?.getValueInt32(0)
                    if (effectId != null) revertedEffectIds.add(effectId)
                }
            }

            return revertedEffectIds
        }

        private fun findDamagedThisTurn(active: Map<Int, AnnotationInfo>): Int? =
            active.entries
                .firstOrNull { (_, ann) ->
                    ann.typeList.any { it == AnnotationType.DamagedThisTurn }
                }?.key

        /**
         * Grow-in-place, or clear, the per-turn `DamagedThisTurn` annotation.
         *
         * Semantics:
         *  - [clear] = true: delete the active annotation (no merge, no re-create).
         *  - New victims + none active: allocate a fresh ID, create.
         *  - New victims + active: merge into existing `affectedIds` (dedup,
         *    stable order), keep the same ID.
         */
        private fun updateDamagedThisTurn(
            active: MutableMap<Int, AnnotationInfo>,
            deletions: MutableList<Int>,
            startId: Int,
            newAnnotations: List<AnnotationInfo>,
            clear: Boolean,
        ): Int {
            var nextId = startId
            if (clear) {
                val existingId = findDamagedThisTurn(active)
                if (existingId != null) {
                    active.remove(existingId)
                    deletions.add(existingId)
                }
                return nextId
            }
            if (newAnnotations.isEmpty()) return nextId
            val incomingIds = newAnnotations.flatMap { it.affectedIdsList }
            val existingId = findDamagedThisTurn(active)
            if (existingId != null) {
                val existing = active.getValue(existingId)
                val merged = (existing.affectedIdsList + incomingIds).distinct()
                active[existingId] =
                    existing
                        .toBuilder()
                        .clearAffectedIds()
                        .addAllAffectedIds(merged)
                        .build()
            } else {
                val template = newAnnotations.first()
                val merged = incomingIds.distinct()
                val numbered =
                    template
                        .toBuilder()
                        .setId(nextId++)
                        .clearAffectedIds()
                        .addAllAffectedIds(merged)
                        .build()
                active[numbered.id] = numbered
            }
            return nextId
        }

        private fun findByEffectId(
            active: Map<Int, AnnotationInfo>,
            effectId: Int,
        ): Int? =
            active.entries
                .firstOrNull { (_, ann) ->
                    ann.typeList.any { it == AnnotationType.LayeredEffect } &&
                        ann.detailsList.any {
                            it.key == DetailKeys.EFFECT_ID && it.valueInt32Count > 0 && it.getValueInt32(0) == effectId
                        }
                }?.key

        private fun findControllerChanged(
            active: Map<Int, AnnotationInfo>,
            cardIid: Int,
        ): Int? =
            active.entries
                .firstOrNull { (_, ann) ->
                    ann.typeList.any { it == AnnotationType.ControllerChanged } &&
                        ann.typeList.any { it == AnnotationType.LayeredEffect } &&
                        ann.affectedIdsList.contains(cardIid)
                }?.key

        private fun findByAura(
            active: Map<Int, AnnotationInfo>,
            auraIid: Int,
        ): Int? =
            active.entries
                .firstOrNull { (_, ann) ->
                    ann.typeList.any { it == AnnotationType.Attachment } &&
                        ann.affectorId == auraIid
                }?.key

        /**
         * Find DisplayCardUnderCard annotations whose exile source (affectorId)
         * maps back to a forgeCardId that left the battlefield.
         */
        private fun findExileSourcesLeavingPlay(
            active: Map<Int, AnnotationInfo>,
            leftPlayForgeIds: Set<ForgeCardId>,
            resolveForgeCardId: (InstanceId) -> ForgeCardId?,
        ): List<Int> =
            active.entries
                .filter { (_, ann) ->
                    ann.typeList.any { it == AnnotationType.DisplayCardUnderCard } &&
                        resolveForgeCardId(InstanceId(ann.affectorId)) in leftPlayForgeIds
                }.map { it.key }
    }

    // --- Monotonic ID counters ---

    private var nextAnnotationId = INITIAL_ANNOTATION_ID
    private var nextPersistentAnnotationId = INITIAL_PERSISTENT_ANNOTATION_ID

    /** Allocate the next sequential transient annotation ID. */
    fun nextAnnotationId(): Int = nextAnnotationId++

    /** Allocate the next sequential persistent annotation ID. */
    fun nextPersistentAnnotationId(): Int = nextPersistentAnnotationId++

    // --- Persistent annotation store ---

    private val active = mutableMapOf<Int, AnnotationInfo>()
    private val pendingDeletions = mutableListOf<Int>()

    /** Add (or replace) a persistent annotation. */
    fun add(ann: AnnotationInfo) {
        active[ann.id] = ann
    }

    /** Remove a persistent annotation — queues its ID for [drainDeletions]. */
    fun remove(id: Int) {
        active.remove(id)
        pendingDeletions.add(id)
    }

    /**
     * Drain and return IDs deleted since last drain (for diffDeletedPersistentAnnotationIds).
     *
     * Called once per Diff GSM in [leyline.game.mapping.StateMapper.buildDiff]. Each deletion
     * ID appears in exactly one GSM — calling twice without intervening mutations
     * returns empty. For Full GSMs, deletions are embedded via [computeBatch]'s
     * [BatchResult.deletedIds] instead.
     */
    fun drainDeletions(): List<Int> = pendingDeletions.toList().also { pendingDeletions.clear() }

    /** All currently active persistent annotations. */
    fun getAll(): List<AnnotationInfo> = active.values.toList()

    /** Forge card IDs of permanents currently under stolen control (have ControllerChanged+LayeredEffect pAnn). */
    private val activeSteals = mutableSetOf<ForgeCardId>()

    /** Set of forge card IDs currently under stolen control. Used by pipeline to detect reverts. */
    fun activeStealForgeCardIds(): Set<ForgeCardId> = activeSteals.toSet()

    /** Record a steal effect for tracking. Called after computeBatch when new steals are created. */
    fun addSteals(forgeCardIds: Collection<ForgeCardId>) {
        activeSteals.addAll(forgeCardIds)
    }

    /** Remove steal tracking for reverted cards. */
    fun removeSteals(forgeCardIds: Collection<ForgeCardId>) {
        activeSteals.removeAll(forgeCardIds.toSet())
    }

    // --- Snapshot / ID accessors ---

    /** Immutable snapshot of current active persistent annotations. */
    fun snapshot(): Map<Int, AnnotationInfo> = active.toMap()

    /** Current persistent annotation ID counter value. */
    fun currentPersistentId(): Int = nextPersistentAnnotationId

    /** Current transient annotation ID counter value. */
    fun currentAnnotationId(): Int = nextAnnotationId

    /** Advance the transient annotation ID counter to a specific value. */
    fun setAnnotationId(value: Int) {
        nextAnnotationId = value
    }

    /**
     * Apply a pre-computed batch result to the live store.
     *
     * **Must be called in the APPLY phase** (after GSM assembly), not during
     * COMPUTE. The GSM embeds [BatchResult.allAnnotations] directly — applying
     * before assembly would double-count. [leyline.game.mapping.StateMapper.buildFromSnapshot] enforces
     * this: GATHER → COMPUTE → ASSEMBLE → APPLY.
     */
    fun applyBatchResult(result: BatchResult) {
        active.clear()
        active.putAll(result.allAnnotations.associateBy { it.id })
        nextPersistentAnnotationId = result.nextPersistentId
        for (id in result.deletedIds) pendingDeletions.add(id)
    }

    /** Clear all state — persistent annotations, pending deletions, and ID counters. */
    fun resetAll() {
        active.clear()
        pendingDeletions.clear()
        activeSteals.clear()
        nextAnnotationId = INITIAL_ANNOTATION_ID
        nextPersistentAnnotationId = INITIAL_PERSISTENT_ANNOTATION_ID
    }
}
