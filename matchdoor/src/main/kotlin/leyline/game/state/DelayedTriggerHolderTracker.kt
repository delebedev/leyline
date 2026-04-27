package leyline.game.state

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
    private val pendingDeletions = mutableListOf<Int>()

    /**
     * Diff [current] against the live set without mutating state. Caller emits
     * gameObjects + zone entries for [HolderBatch.added], then calls [apply] to
     * commit the diff for the next GSM.
     */
    fun computeBatch(current: List<HolderRecord>): HolderBatch {
        val currentByIid = current.associateBy { it.iid }
        val added = current.filter { it.iid !in active }
        val removed = (active.keys - currentByIid.keys).toList()
        return HolderBatch(added = added, removed = removed)
    }

    /** Commit the batch — `removed` queue up for [drainDeletions], `added`
     *  enter the live set.
     *
     *  Currently called inside the COMPUTE phase by
     *  [leyline.game.mapping.StateMapper.buildFromSnapshot], in contrast to
     *  [leyline.game.state.PersistentAnnotationStore.applyBatchResult] which
     *  is APPLY-phase only. The mutation is gated on a single GSM build —
     *  speculative buildDiff calls without GSM emission would still mutate.
     *  Acceptable today because no caller does that; lift through
     *  [BridgeMutations] if a speculative-build path emerges. */
    fun apply(batch: HolderBatch) {
        for (iid in batch.removed) active.remove(iid)
        for (h in batch.added) active[h.iid] = h
        pendingDeletions.addAll(batch.removed)
    }

    /** Drain holder iids that should land in the next GSM's
     *  `diffDeletedInstanceIds`. Single-use per GSM; callers fold into the
     *  buildDiff deletion list alongside snapshot-derived deletions. */
    fun drainDeletions(): List<Int> {
        if (pendingDeletions.isEmpty()) return emptyList()
        val out = pendingDeletions.toList()
        pendingDeletions.clear()
        return out
    }

    /** Live set size — diagnostic / test hook. */
    val activeSize: Int get() = active.size

    /** Iids of all currently-alive holders. Used by [leyline.game.mapping.StateMapper]
     *  to keep them listed in the Limbo zone every GSM (the gameObject is cached
     *  client-side after first emission, but the zone listing is rebuilt each
     *  GSM and dropping the iid would orphan the cached object). */
    fun activeIids(): Set<Int> = active.keys.toSet()

    /** Wipe all tracked state. Called from [leyline.game.state.GameBridge.resetForPuzzle]
     *  so a hot-swapped puzzle doesn't inherit holders from the previous match. */
    fun resetAll() {
        active.clear()
        pendingDeletions.clear()
    }
}

/**
 * Wire-shape state for a single TriggerHolder gameObject.
 *
 * @param iid stable instanceId for the holder. Same iid is the affector for
 *   the holder's `DelayedTriggerAffectees` and per-affected `TemporaryPermanent`
 *   annotations.
 * @param ownerSeat seat of the source card's controller — drives the
 *   `ownerSeatId` / `controllerSeatId` of the gameObject.
 * @param objectSourceGrpId the grpId of the *resolving* ability that registered
 *   the delayed trigger (e.g. 188696 for Mobilize 3 keyword row, 136340 for
 *   Charming Prince's modal-ETB choice). Drives the side-panel icon's source.
 * @param parentIid instanceId of the source card on the battlefield — the
 *   client uses this to link the side-panel indicator back to its origin card.
 * @param cleanupGrpId the grpId of the delayed-trigger ability that fires later
 *   (e.g. 189930/189931 for Mobilize cleanup, 136220 for Charming Prince
 *   return). Lands in `uniqueAbilities[0].grpId` and drives the indicator's
 *   tooltip text.
 */
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
 */
data class HolderRecord(
    val iid: Int,
    val ownerSeat: Int,
    val objectSourceGrpId: Int,
    val parentIid: Int,
    val cleanupGrpId: Int,
)

/** Diff result from [DelayedTriggerHolderTracker.computeBatch]. */
data class HolderBatch(
    val added: List<HolderRecord>,
    val removed: List<Int>,
)
