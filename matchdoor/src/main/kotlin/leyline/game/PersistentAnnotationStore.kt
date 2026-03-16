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

    companion object {
        /** Transient annotation IDs start at 50 to avoid collision with persistent IDs. */
        const val INITIAL_ANNOTATION_ID = 50

        /** Persistent annotation IDs start at 1. */
        const val INITIAL_PERSISTENT_ANNOTATION_ID = 1
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

    // --- Typed queries ---

    /** Find persistent Attachment annotation by aura instanceId in affectedIds. */
    fun findByAura(auraIid: Int): Int? =
        active.entries.firstOrNull { (_, ann) ->
            ann.typeList.any { it == AnnotationType.Attachment } &&
                ann.affectedIdsList.contains(auraIid)
        }?.key

    /** Find persistent Counter annotation for the same instanceId and counter_type. */
    fun findCounter(instanceId: Int, counterType: Int): Int? =
        active.entries.firstOrNull { (_, ann) ->
            ann.typeList.any { it == AnnotationType.Counter_803b } &&
                ann.affectedIdsList.contains(instanceId) &&
                ann.detailsList.any { it.key == "counter_type" && it.valueInt32Count > 0 && it.getValueInt32(0) == counterType }
        }?.key

    /** Find persistent LayeredEffect annotation by effect_id detail key. */
    fun findByEffectId(effectId: Int): Int? =
        active.entries.firstOrNull { (_, ann) ->
            ann.typeList.any { it == AnnotationType.LayeredEffect } &&
                ann.detailsList.any { it.key == "effect_id" && it.valueInt32Count > 0 && it.getValueInt32(0) == effectId }
        }?.key

    /** Clear all state — persistent annotations, pending deletions, and ID counters. */
    fun resetAll() {
        active.clear()
        pendingDeletions.clear()
        nextAnnotationId = INITIAL_ANNOTATION_ID
        nextPersistentAnnotationId = INITIAL_PERSISTENT_ANNOTATION_ID
    }
}
