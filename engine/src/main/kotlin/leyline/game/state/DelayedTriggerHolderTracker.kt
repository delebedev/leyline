package leyline.game.state

import leyline.bridge.types.ForgeCardId

/**
 * Cross-GSM lifecycle tracker for transient `TriggerHolder` gameObjects (proto
 * `GameObjectType.TriggerHolder`, grpId=5). Each holder represents a
 * registered delayed trigger that's pending resolution — Mobilize EOT-sacrifice
 * is the current driver; exile-and-return mechanics (Charming Prince, Yorion,
 * etc.) plug into the same tracker by feeding their own [HolderRecord]s
 * through [computeBatch].
 *
 * The tracker is bridge-scoped: lifetime tied to the match. Each GSM build
 * supplies the set of holders that *should* be alive for the current snap,
 * and the tracker returns the delta against the previous snap. Wire emission:
 *
 *  - **Added** holders → emit one [wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo]
 *    + Limbo zone entry in this GSM. The client caches them by instanceId.
 *  - **Removed** holders → flow through `diffDeletedInstanceIds` so the client
 *    retires the cached gameObject. The canonical wire emits the deletion
 *    exactly when the cleanup ability actually fires (end-step sacrifice for
 *    Mobilize, or the next-end-step return for exile-and-return).
 *  - **Unchanged** holders → no wire traffic; the client still has them.
 *
 * Detection (which holders should be alive each frame) is per-mechanic and
 * lives in StateMapper. Mobilize derives the set from snap-side
 * `endOfTurnLeavePlay` tokens grouped by source card. Future mechanics add
 * their own feeders without touching this class.
 */
class DelayedTriggerHolderTracker {
    private val active = mutableMapOf<Int, HolderRecord>()

    /**
     * Diff [current] against the live set without mutating state. The mapper emits
     * gameObjects + zone entries for [HolderBatch.added], then returns the batch
     * through [BridgeMutations] so [GameBridge.applyMutations] commits it.
     */
    fun computeBatch(current: List<HolderRecord>): HolderBatch = computeBatch(current, active.values)

    /**
     * Diff [current] against an immutable projected baseline.
     *
     * Composite frame compilation uses this overload to carry holder lifecycle
     * across subframes without mutating the live tracker before commit.
     */
    fun computeBatch(
        current: List<HolderRecord>,
        projectedActive: Collection<HolderRecord>,
    ): HolderBatch {
        val activeIds = projectedActive.mapTo(mutableSetOf()) { it.iid }
        val currentByIid = current.associateBy { it.iid }
        val added = current.filter { it.iid !in activeIds }
        val removed = (activeIds - currentByIid.keys).toList()
        return HolderBatch(added = added, removed = removed)
    }

    /** Commit the batch in [GameBridge.applyMutations] after GSM assembly. */
    fun apply(batch: HolderBatch) {
        for (iid in batch.removed) active.remove(iid)
        for (h in batch.added) active[h.iid] = h
    }

    /** Iids of all currently-alive holders. Used by [leyline.game.mapping.StateMapper]
     *  to keep them listed in the Limbo zone every GSM (the gameObject is cached
     *  client-side after first emission, but the zone listing is rebuilt each
     *  GSM and dropping the iid would orphan the cached object). */
    fun activeIids(): Set<Int> = active.keys.toSet()

    fun activeRecords(): List<HolderRecord> = active.values.toList()

    /** Holder rows for iids leaving the pending state in the current frame. */
    fun records(iids: Collection<Int>): List<HolderRecord> = iids.mapNotNull(active::get)

    /** Wipe all tracked state. Called from [leyline.game.state.GameBridge.resetForPuzzle]
     *  so a hot-swapped puzzle doesn't inherit holders from the previous match. */
    fun resetAll() {
        active.clear()
    }
}

/**
 * Wire-shape state for a single TriggerHolder gameObject.
 *
 * **Cross-class invariant:** [iid] must equal:
 *   - the `instanceId` field on the holder's [wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo],
 *   - the `affectorId` of every per-token `TemporaryPermanent` and
 *     `DelayedTriggerAffectees` annotation referencing this trigger,
 *   - the entry in the Limbo zone's `objectInstanceIds` listing.
 *
 * Divergence breaks the client-side gameObject↔pAnn linkage and the
 * side-panel timed-effect indicator stops rendering. Tests verify the
 * invariant in `MobilizeKeywordTest`'s holder shape + lifecycle cases.
 *
 * @param iid stable instanceId for the holder.
 * @param ownerSeat seat of the source card's controller.
 * @param objectSourceGrpId grpId of the ability that registered the delayed trigger.
 * @param parentIid instanceId of the source card on the battlefield.
 * @param cleanupGrpId grpId of the delayed-trigger ability that fires later.
 */
data class HolderRecord(
    val iid: Int,
    val ownerSeat: Int,
    val objectSourceGrpId: Int,
    val parentIid: Int,
    val cleanupGrpId: Int,
    val sourceForgeCardId: ForgeCardId? = null,
    val runtimeTriggerId: Int? = null,
)

/** Diff result from [DelayedTriggerHolderTracker.computeBatch]. */
data class HolderBatch(
    val added: List<HolderRecord>,
    val removed: List<Int>,
) {
    companion object {
        val EMPTY = HolderBatch(added = emptyList(), removed = emptyList())
    }
}
