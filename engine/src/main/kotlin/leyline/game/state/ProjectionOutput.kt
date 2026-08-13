package leyline.game.state

import leyline.bridge.types.InstanceId
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Wire-assembly metadata produced with a [ProjectionTransition], never committed as state. */
data class ProjectionOutput(
    val idReallocations: List<InstanceIdRegistry.IdReallocation> = emptyList(),
    val persistentBatch: PersistentAnnotationStore.BatchResult,
    val holderBatch: HolderBatch = HolderBatch.EMPTY,
    val diffDeletedInstanceIds: List<InstanceId> = emptyList(),
    val promptFactConsumption: PromptFactConsumption = PromptFactConsumption(),
    val consumedEarthbendResolutionVersions: Set<Long> = emptySet(),
    val priorPersistentAnnotations: Map<Int, AnnotationInfo> = emptyMap(),
)
