package leyline.game

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Input bundle for the pure annotation compute phase in [StateMapper.buildFromGame].
 *
 * Gathered once at the top of the method (before any mutation) so the compute
 * phase is a pure function over this snapshot. Fields mirror the local variables
 * that were previously scattered through the method body.
 *
 * @property events drained game events from [GameEventCollector]
 * @property previousZones per-instanceId zone recorded before this GSM (for transfer detection)
 * @property limboIds accumulated limbo instanceIds from [LimboTracker]
 * @property boostSnapshot P/T boost table snapshot keyed by instanceId
 * @property initEffectDiff one-shot init effects emitted on the first Full GSM
 * @property effectDiff boost diff relative to previously tracked effects
 * @property currentPersistentAnnotations persistent annotation snapshot from [PersistentAnnotationStore]
 * @property actingSeat seat index of the player currently holding priority (derived from priorityPlayer)
 */
data class PipelineInput(
    val events: List<GameEvent>,
    val previousZones: Map<Int, Int>,
    val limboIds: List<Int>,
    val boostSnapshot: Map<Int, List<EffectTracker.BoostEntry>>,
    val initEffectDiff: EffectTracker.DiffResult,
    val effectDiff: EffectTracker.DiffResult,
    val currentPersistentAnnotations: List<AnnotationInfo>,
    val actingSeat: Int,
)

/**
 * Deferred mutations produced by the pure annotation compute phase.
 *
 * The compute phase returns this instead of writing directly to mutable state,
 * keeping the pipeline side-effect-free and independently testable. The caller
 * applies each field in the "apply" stage at the bottom of [StateMapper.buildFromGame].
 *
 * @property retiredIds instanceIds to retire to Limbo via [LimboTracker.retireToLimbo]
 * @property zoneRecordings (instanceId, zoneId) pairs to persist via [ZoneTracking.recordZone]
 * @property effectPersistent persistent [AnnotationInfo] entries produced by effect annotations
 * @property transferPersistent persistent [AnnotationInfo] entries produced by transfer annotations
 * @property mechanicResult stage-4 mechanic annotation result — passed to [applyBatch]
 * @property effectDiff boost diff forwarded to [applyBatch] for LayeredEffect lifecycle annotations
 */
data class PipelineEffects(
    val retiredIds: List<Int>,
    val zoneRecordings: List<Pair<Int, Int>>,
    val effectPersistent: List<AnnotationInfo>,
    val transferPersistent: List<AnnotationInfo>,
    val mechanicResult: AnnotationPipeline.MechanicAnnotationResult,
    val effectDiff: EffectTracker.DiffResult,
)
