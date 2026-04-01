package leyline.game

import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
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
 * and entered-zone-this-turn markers. Each appears in every GSM's
 * `persistentAnnotations` field until explicitly removed.
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
 * is taken *before* the COMPUTE phase in [StateMapper.buildFromGame], so the
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
 * [StateMapper.buildDiffFromGame]. The drain-then-clear pattern means each
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
         * **Ordering invariant:** Steps 1-6 execute in fixed order. Effects (1)
         * before transfers (2) before mechanics (3) because a counter upsert in
         * step 3 must not collide with a LayeredEffect ID allocated in step 1.
         * Cleanup steps (4-6) run last so they see the full set of newly added
         * pAnns — e.g. step 4 (detach) can remove an Attachment just created
         * in step 3 if the aura was simultaneously destroyed.
         *
         * **Snapshot timing:** [currentActive] must be a snapshot taken *before*
         * the annotation pipeline runs. [StateMapper.buildFromGame] captures it
         * in the GATHER phase so the COMPUTE phase (which calls this) is pure.
         *
         * @param resolveForgeCardId reverse-resolves instanceId → forgeCardId.
         *   Used by step 5 to match DisplayCardUnderCard annotations whose
         *   affectorId may have been reallocated by a zone transfer. The registry
         *   retains mappings for retired iids, so this works even after reallocation.
         */
        fun computeBatch(
            currentActive: Map<Int, AnnotationInfo>,
            startPersistentId: Int,
            effectPersistent: List<AnnotationInfo>,
            effectDiff: EffectTracker.DiffResult,
            transferPersistent: List<AnnotationInfo>,
            mechanicResult: AnnotationPipeline.MechanicAnnotationResult,
            resolveInstanceId: (ForgeCardId) -> InstanceId,
            resolveForgeCardId: (InstanceId) -> ForgeCardId? = { null },
        ): BatchResult {
            val active = currentActive.toMutableMap()
            val deletions = mutableListOf<Int>()
            var nextId = startPersistentId

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

            // 2. Transfer-originated
            for (ann in transferPersistent) {
                val numbered = ann.toBuilder().setId(nextId++).build()
                active[numbered.id] = numbered
            }

            // 3. Mechanic-originated (counters with upsert)
            for (ann in mechanicResult.persistent) {
                if (ann.typeList.any { it == AnnotationType.Counter_803b }) {
                    val iid = ann.affectedIdsList.firstOrNull()
                    val ctype = ann.detailsList.firstOrNull { it.key == DetailKeys.COUNTER_TYPE }
                        ?.let { if (it.valueInt32Count > 0) it.getValueInt32(0) else null }
                    if (iid != null && ctype != null) {
                        val oldId = findCounter(active, iid, ctype)
                        if (oldId != null) {
                            active.remove(oldId)
                            deletions.add(oldId)
                        }
                    }
                }
                val numbered = ann.toBuilder().setId(nextId++).build()
                active[numbered.id] = numbered
            }

            // 3b. AbilityWordActive — full-replacement upsert
            nextId = upsertAbilityWords(
                active,
                deletions,
                nextId,
                mechanicResult.abilityWordPersistent,
            )

            // 3c. Qualification — full-replacement for adventure-exiled cards
            nextId = upsertQualifications(active, deletions, nextId, mechanicResult.qualificationPersistent)

            // 3d. CrewedThisTurn — full-replacement upsert (keyed by vehicle affectorId)
            nextId = upsertByType(
                active,
                deletions,
                nextId,
                AnnotationType.CrewedThisTurn,
                mechanicResult.crewedThisTurnPersistent,
                { it.affectorId },
                detectChanges = true,
            )

            // 3e. ModifiedType+LayeredEffect for crew type changes — full-replacement upsert
            nextId = upsertByType(
                active,
                deletions,
                nextId,
                AnnotationType.ModifiedType,
                mechanicResult.crewTypeChangePersistent,
                { it.affectedIdsList.firstOrNull() ?: 0 },
            )

            // 3f. TemporaryPermanent — full-replacement upsert (keyed by token affectorId)
            nextId = upsertByType(
                active,
                deletions,
                nextId,
                AnnotationType.TemporaryPermanent,
                mechanicResult.temporaryPermanentPersistent,
                { it.affectorId },
            )

            // 4-6. Cleanup: detached auras, exile sources, controller reverts
            val cleanupReverts = cleanupDetachedAndReverted(
                active,
                deletions,
                mechanicResult,
                resolveInstanceId,
                resolveForgeCardId,
            )

            return BatchResult(active.values.toList(), deletions, nextId, cleanupReverts)
        }

        /** Steps 4-6: remove pAnns for detached auras, exile sources leaving play, controller reverts. */
        private fun cleanupDetachedAndReverted(
            active: MutableMap<Int, AnnotationInfo>,
            deletions: MutableList<Int>,
            mechanicResult: AnnotationPipeline.MechanicAnnotationResult,
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
                    val effectId = ann?.detailsList
                        ?.firstOrNull { it.key == DetailKeys.EFFECT_ID && it.valueInt32Count > 0 }
                        ?.getValueInt32(0)
                    if (effectId != null) revertedEffectIds.add(effectId)
                }
            }

            return revertedEffectIds
        }

        private fun findByEffectId(active: Map<Int, AnnotationInfo>, effectId: Int): Int? =
            active.entries.firstOrNull { (_, ann) ->
                ann.typeList.any { it == AnnotationType.LayeredEffect } &&
                    ann.detailsList.any {
                        it.key == DetailKeys.EFFECT_ID && it.valueInt32Count > 0 && it.getValueInt32(0) == effectId
                    }
            }?.key

        private fun findCounter(active: Map<Int, AnnotationInfo>, instanceId: Int, counterType: Int): Int? =
            active.entries.firstOrNull { (_, ann) ->
                ann.typeList.any { it == AnnotationType.Counter_803b } &&
                    ann.affectedIdsList.contains(instanceId) &&
                    ann.detailsList.any {
                        it.key == DetailKeys.COUNTER_TYPE && it.valueInt32Count > 0 && it.getValueInt32(0) == counterType
                    }
            }?.key

        private fun findControllerChanged(active: Map<Int, AnnotationInfo>, cardIid: Int): Int? =
            active.entries.firstOrNull { (_, ann) ->
                ann.typeList.any { it == AnnotationType.ControllerChanged } &&
                    ann.typeList.any { it == AnnotationType.LayeredEffect } &&
                    ann.affectedIdsList.contains(cardIid)
            }?.key

        private fun abilityWordKey(ann: AnnotationInfo): Pair<Int, String> {
            val iid = ann.affectedIdsList.firstOrNull() ?: 0
            val name = ann.detailsList.firstOrNull { it.key == DetailKeys.ABILITY_WORD_NAME }
                ?.let { if (it.valueStringCount > 0) it.getValueString(0) else null } ?: ""
            return iid to name
        }

        /**
         * Generic full-replacement upsert for snapshot-based persistent annotations.
         *
         * Removes stale annotations of [type] not in [newAnnotations], adds new ones.
         * When [detectChanges] is true, replaces existing annotations whose details differ
         * (e.g. AbilityWordActive value updates). When false, skips if key already exists
         * (e.g. Qualification with constant details).
         *
         * Perf: O(N×M) where N = new annotations, M = total active pAnns. Fine for
         * typical battlefield sizes (~20 permanents).
         *
         * @param keyFn extracts the dedup key from an annotation
         */
        private fun <K> upsertByType(
            active: MutableMap<Int, AnnotationInfo>,
            deletions: MutableList<Int>,
            startId: Int,
            type: AnnotationType,
            newAnnotations: List<AnnotationInfo>,
            keyFn: (AnnotationInfo) -> K,
            detectChanges: Boolean = false,
        ): Int {
            var nextId = startId
            val newByKey = newAnnotations.associateBy { keyFn(it) }
            // Remove stale
            val staleIds = active.entries
                .filter { (_, ann) ->
                    ann.typeList.any { it == type } && keyFn(ann) !in newByKey
                }
                .map { it.key }
            for (id in staleIds) {
                active.remove(id)
                deletions.add(id)
            }
            // Upsert new/changed
            for ((key, ann) in newByKey) {
                val existing = active.entries.firstOrNull { (_, e) ->
                    e.typeList.any { it == type } && keyFn(e) == key
                }
                if (existing != null) {
                    if (detectChanges && existing.value.detailsList != ann.detailsList) {
                        active.remove(existing.key)
                        deletions.add(existing.key)
                        val numbered = ann.toBuilder().setId(nextId++).build()
                        active[numbered.id] = numbered
                    }
                } else {
                    val numbered = ann.toBuilder().setId(nextId++).build()
                    active[numbered.id] = numbered
                }
            }
            return nextId
        }

        private fun upsertAbilityWords(
            active: MutableMap<Int, AnnotationInfo>,
            deletions: MutableList<Int>,
            startId: Int,
            newAnnotations: List<AnnotationInfo>,
        ): Int = upsertByType(active, deletions, startId, AnnotationType.AbilityWordActive, newAnnotations, ::abilityWordKey, detectChanges = true)

        private fun upsertQualifications(
            active: MutableMap<Int, AnnotationInfo>,
            deletions: MutableList<Int>,
            startId: Int,
            newAnnotations: List<AnnotationInfo>,
        ): Int = upsertByType(active, deletions, startId, AnnotationType.Qualification, newAnnotations, { it.affectedIdsList.firstOrNull() ?: 0 })

        private fun findByAura(active: Map<Int, AnnotationInfo>, auraIid: Int): Int? =
            active.entries.firstOrNull { (_, ann) ->
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
                }
                .map { it.key }
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
     * Called once per Diff GSM in [StateMapper.buildDiffFromGame]. Each deletion
     * ID appears in exactly one GSM — calling twice without intervening mutations
     * returns empty. For Full GSMs, deletions are embedded via [computeBatch]'s
     * [BatchResult.deletedIds] instead.
     */
    fun drainDeletions(): List<Int> =
        pendingDeletions.toList().also { pendingDeletions.clear() }

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
     * before assembly would double-count. [StateMapper.buildFromGame] enforces
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
