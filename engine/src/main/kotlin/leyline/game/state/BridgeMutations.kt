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
 * 1. `instanceIdTransition` — complete tentative identity state, validated and
 *    installed once in [GameBridge.applyMutations]
 * 2. `revealTransition` — tentative reveal-view map state
 * 3. `effectTransition` — complete synthetic-effect state
 * 4. `opponentKnowledgeTransition` — hidden-card knowledge state
 * 5. `annotationJournalTransition` — all cross-frame annotation correlation state
 * 6. `retiredIds` / `zoneRecordings` — identity and zone bookkeeping
 * 7. `persistentBatch` — persistent annotation state writes (writes to [PersistentAnnotationStore])
 * 8. `promptFactConsumption` — exact observed prompt facts consumed only after their projection commits
 * 9. `nextAnnotationId` — transient annotation ID counter update
 * 10. `holderBatch` — delayed-trigger holder lifecycle writes (writes to [DelayedTriggerHolderTracker])
 * 11. `nextTransientLinkedFaceFamilyIds` — one-frame hidden-zone projection lifecycle
 *
 * `diffDeletedInstanceIds` is compute-time output for GSM assembly only. It is
 * not applied to bridge state; the rest of this batch still owns bridge writes.
 *
 * Scope: identity, reveal, and synthetic-effect lifecycles are complete
 * tentative transitions. The shell validates their baselines before installing
 * any projection state; a discarded plan leaves every family unchanged.
 */
data class BridgeMutations(
    val instanceIdTransition: InstanceIdRegistry.Transition,
    /** Complete synthetic-effect state transition computed by the private planner. */
    val effectTransition: SyntheticEffectTransition,
    val opponentKnowledgeTransition: OpponentKnowledgeTracker.Transition,
    /** Callback facts incorporated by the accepted effect transition. */
    val consumedEarthbendResolutions: List<GameBridge.EarthbendResolution> = emptyList(),
    val revealTransition: RevealProxyTracker.Transition? = null,
    val annotationJournalTransition: ProjectionAnnotationJournal.Transition,
    val idReallocations: List<InstanceIdRegistry.IdReallocation>,
    val retiredIds: List<InstanceId>,
    val zoneRecordings: List<Pair<InstanceId, Int>>,
    val persistentBatch: PersistentAnnotationStore.BatchResult,
    val promptFactConsumption: PromptFactConsumption = PromptFactConsumption(),
    /** Null until the complete transient frame has been finalized. */
    val nextAnnotationId: Int?,
    val holderBatch: HolderBatch,
    /** Extra object deletions emitted in this Diff GSM, without bridge-state writes. */
    val diffDeletedInstanceIds: List<InstanceId> = emptyList(),
    /** Linked-face family objects to delete on the following GSM. */
    val nextTransientLinkedFaceFamilyIds: Set<InstanceId> = emptySet(),
)

/** One compare-and-install transition for all synthetic effect families. */
data class SyntheticEffectTransition(
    val expected: SyntheticEffectProjection,
    val next: SyntheticEffectProjection,
)
