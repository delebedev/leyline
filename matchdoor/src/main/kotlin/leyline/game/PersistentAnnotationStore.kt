package leyline.game

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Manages persistent and transient annotation ID sequences, plus the
 * persistent annotation lifecycle (carry-forward across GSMs).
 *
 * Persistent annotations (Attachment, Counter, LayeredEffect, etc.) are
 * added when a mechanic starts and removed when it ends. They appear in
 * every GSM until explicitly deleted via [remove] → [drainDeletions].
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
    )

    companion object {
        /** Transient annotation IDs start at 50 to avoid collision with persistent IDs. */
        const val INITIAL_ANNOTATION_ID = 50

        /** Persistent annotation IDs start at 1. */
        const val INITIAL_PERSISTENT_ANNOTATION_ID = 1

        /**
         * Pure batch computation — same logic as [applyBatch] but operates on
         * an immutable snapshot and returns the result instead of mutating state.
         */
        fun computeBatch(
            currentActive: Map<Int, AnnotationInfo>,
            startPersistentId: Int,
            effectPersistent: List<AnnotationInfo>,
            effectDiff: EffectTracker.DiffResult,
            transferPersistent: List<AnnotationInfo>,
            mechanicResult: AnnotationPipeline.MechanicAnnotationResult,
            resolveInstanceId: (Int) -> Int,
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
                    val ctype = ann.detailsList.firstOrNull { it.key == "counter_type" }
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

            // 4. Detached auras
            for (forgeCardId in mechanicResult.detachedForgeCardIds) {
                val auraIid = resolveInstanceId(forgeCardId)
                val annId = findByAura(active, auraIid)
                if (annId != null) {
                    active.remove(annId)
                    deletions.add(annId)
                }
            }

            return BatchResult(active.values.toList(), deletions, nextId)
        }

        private fun findByEffectId(active: Map<Int, AnnotationInfo>, effectId: Int): Int? =
            active.entries.firstOrNull { (_, ann) ->
                ann.typeList.any { it == AnnotationType.LayeredEffect } &&
                    ann.detailsList.any {
                        it.key == "effect_id" && it.valueInt32Count > 0 && it.getValueInt32(0) == effectId
                    }
            }?.key

        private fun findCounter(active: Map<Int, AnnotationInfo>, instanceId: Int, counterType: Int): Int? =
            active.entries.firstOrNull { (_, ann) ->
                ann.typeList.any { it == AnnotationType.Counter_803b } &&
                    ann.affectedIdsList.contains(instanceId) &&
                    ann.detailsList.any {
                        it.key == "counter_type" && it.valueInt32Count > 0 && it.getValueInt32(0) == counterType
                    }
            }?.key

        private fun findByAura(active: Map<Int, AnnotationInfo>, auraIid: Int): Int? =
            active.entries.firstOrNull { (_, ann) ->
                ann.typeList.any { it == AnnotationType.Attachment } &&
                    ann.affectedIdsList.contains(auraIid)
            }?.key
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

    /** Drain and return IDs deleted since last drain (for diffDeletedPersistentAnnotationIds). */
    fun drainDeletions(): List<Int> =
        pendingDeletions.toList().also { pendingDeletions.clear() }

    /** All currently active persistent annotations. */
    fun getAll(): List<AnnotationInfo> = active.values.toList()

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

    /** Apply a pre-computed batch result to the live store. */
    fun applyBatchResult(result: BatchResult) {
        active.clear()
        active.putAll(result.allAnnotations.associateBy { it.id })
        nextPersistentAnnotationId = result.nextPersistentId
        for (id in result.deletedIds) pendingDeletions.add(id)
    }

    // --- Batch lifecycle ---

    /**
     * Apply all persistent annotation changes for a single GSM build.
     *
     * Handles the four mutation patterns in the correct order:
     * 1. Effect lifecycle — add created, remove destroyed (by effectId)
     * 2. Transfer-originated — zone-change persistent annotations (add)
     * 3. Mechanic-originated — counters (upsert by key), attachments (add)
     * 4. Detached auras — remove attachment annotations for unequipped/detached
     *
     * @param effectPersistent persistent annotations from [AnnotationPipeline.effectAnnotations]
     * @param effectDiff created/destroyed effects for lifecycle management
     * @param transferPersistent persistent annotations from zone transfers (Stage 2)
     * @param mechanicResult mechanic annotations + detached aura IDs (Stage 4)
     * @param resolveInstanceId maps forge card ID → client instanceId (for aura lookup)
     */
    fun applyBatch(
        effectPersistent: List<AnnotationInfo>,
        effectDiff: EffectTracker.DiffResult,
        transferPersistent: List<AnnotationInfo>,
        mechanicResult: AnnotationPipeline.MechanicAnnotationResult,
        resolveInstanceId: (Int) -> Int,
    ) {
        val result = computeBatch(
            currentActive = active.toMap(),
            startPersistentId = nextPersistentAnnotationId,
            effectPersistent = effectPersistent,
            effectDiff = effectDiff,
            transferPersistent = transferPersistent,
            mechanicResult = mechanicResult,
            resolveInstanceId = resolveInstanceId,
        )
        applyBatchResult(result)
    }

    /** Clear all state — persistent annotations, pending deletions, and ID counters. */
    fun resetAll() {
        active.clear()
        pendingDeletions.clear()
        nextAnnotationId = INITIAL_ANNOTATION_ID
        nextPersistentAnnotationId = INITIAL_PERSISTENT_ANNOTATION_ID
    }
}
