package leyline.game.state

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId

data class PendingTargetSpecRecord(
    val seatId: SeatId,
    val spec: InteractivePromptBridge.PendingTarget,
)

/**
 * Ordering-sensitive writes that `StateMapper.buildDiff` computes but does not commit.
 *
 * `buildDiff` returns a [BridgeMutations] alongside the Diff GSM; `BundleBuilder`
 * applies them via [GameBridge.applyMutations] between diff and action-build. This
 * keeps the diff pure on its ordering-sensitive outputs — required for replay
 * round-trip.
 *
 * **Ordering invariant** (applied in [GameBridge.applyMutations]):
 * 1. `instanceIdTransition` — complete tentative identity state, validated and
 *    installed once in [GameBridge.applyMutations]
 * 2. `revealTransition` — tentative reveal-view map state
 * 3. `idReallocations` — id swap description for zone-transferred cards
 * 4. `retiredIds` — old ids moved to limbo (writes to [LimboTracker])
 * 5. `zoneRecordings` — new zone assignments (writes to [DiffSnapshotter.previousZones])
 * 6. `persistentBatch` — persistent annotation state writes (writes to [PersistentAnnotationStore])
 * 7. `consumedTargetSpecs` — pending TargetSpec prompt records consumed after their persistent batch is committed
 * 8. `nextAnnotationId` — transient annotation ID counter update
 * 9. `holderBatch` — delayed-trigger holder lifecycle writes (writes to [DelayedTriggerHolderTracker])
 * 10. `nextTransientLinkedFaceFamilyIds` — one-frame hidden-zone projection lifecycle
 *
 * `diffDeletedInstanceIds` is compute-time output for GSM assembly only. It is
 * not applied to bridge state; the rest of this batch still owns bridge writes.
 *
 * Scope: identity allocation is a complete tentative transition. Other tracker
 * families remain explicit follow-up work; effect IDs and their lifecycle are
 * intentionally not part of this slice.
 */
data class BridgeMutations(
    val instanceIdTransition: InstanceIdRegistry.Transition? = null,
    val revealTransition: RevealProxyTracker.Transition? = null,
    val idReallocations: List<InstanceIdRegistry.IdReallocation>,
    val retiredIds: List<InstanceId>,
    val zoneRecordings: List<Pair<InstanceId, Int>>,
    val persistentBatch: PersistentAnnotationStore.BatchResult,
    val consumedTargetSpecs: List<PendingTargetSpecRecord> = emptyList(),
    /** Null until the complete transient frame has been finalized. */
    val nextAnnotationId: Int?,
    val holderBatch: HolderBatch,
    /** Extra object deletions emitted in this Diff GSM, without bridge-state writes. */
    val diffDeletedInstanceIds: List<InstanceId> = emptyList(),
    /** Linked-face family objects to delete on the following GSM. */
    val nextTransientLinkedFaceFamilyIds: Set<InstanceId> = emptySet(),
) {
    companion object {
        val EMPTY: BridgeMutations =
            BridgeMutations(
                instanceIdTransition = null,
                revealTransition = null,
                idReallocations = emptyList(),
                retiredIds = emptyList(),
                zoneRecordings = emptyList(),
                persistentBatch =
                    PersistentAnnotationStore.BatchResult(
                        allAnnotations = emptyList(),
                        deletedIds = emptyList(),
                        nextPersistentId = 1,
                    ),
                consumedTargetSpecs = emptyList(),
                nextAnnotationId = 50,
                holderBatch = HolderBatch.EMPTY,
            )
    }
}
