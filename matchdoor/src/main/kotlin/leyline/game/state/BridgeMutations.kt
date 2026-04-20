package leyline.game.state

import leyline.bridge.types.InstanceId

/**
 * Ordering-sensitive writes that `StateMapper.buildDiff` computes but does not commit.
 *
 * `buildDiff` returns a [BridgeMutations] alongside the Diff GSM; `BundleBuilder`
 * applies them via [GameBridge.applyMutations] between diff and action-build. This
 * keeps the diff pure on its ordering-sensitive outputs — required for replay
 * round-trip.
 *
 * **Ordering invariant** (applied in [GameBridge.applyMutations]):
 * 1. `idReallocations` — id swap for zone-transferred cards (writes to [InstanceIdRegistry])
 * 2. `retiredIds` — old ids moved to limbo (writes to [LimboTracker])
 * 3. `zoneRecordings` — new zone assignments (writes to [DiffSnapshotter.previousZones])
 * 4. `persistentBatch` — persistent annotation state writes (writes to [PersistentAnnotationStore])
 * 5. `nextAnnotationId` — transient annotation ID counter update
 *
 * Scope: only ordering-sensitive mutations. Monotonic allocators
 * ([InstanceIdRegistry.getOrAlloc] for new cards, [EffectTracker.nextEffectId],
 * etc.) stay as in-place mutations inside `buildDiff` — their ordering doesn't
 * affect correctness.
 */
data class BridgeMutations(
    val idReallocations: List<InstanceIdRegistry.IdReallocation>,
    val retiredIds: List<InstanceId>,
    val zoneRecordings: List<Pair<InstanceId, Int>>,
    val persistentBatch: PersistentAnnotationStore.BatchResult,
    val nextAnnotationId: Int,
) {
    companion object {
        val EMPTY: BridgeMutations = BridgeMutations(
            idReallocations = emptyList(),
            retiredIds = emptyList(),
            zoneRecordings = emptyList(),
            persistentBatch = PersistentAnnotationStore.BatchResult(
                allAnnotations = emptyList(),
                deletedIds = emptyList(),
                nextPersistentId = 1,
            ),
            nextAnnotationId = 50,
        )
    }
}
