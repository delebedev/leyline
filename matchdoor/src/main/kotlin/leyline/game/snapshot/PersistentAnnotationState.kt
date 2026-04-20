package leyline.game.snapshot

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Persistent-annotation state captured at snapshot time.
 *
 * Lifted onto [GsmSnapshot] so [leyline.game.mapping.StateMapper.buildDiff] can read
 * prev-persistent-state from the snap (pure input) instead of mutably from
 * [leyline.game.state.GameBridge.annotations]. Required for replay round-trip.
 */
data class PersistentAnnotationState(
    /** Persistent annotations active at capture time, keyed by annotation ID. */
    val activeAnnotations: Map<Int, AnnotationInfo>,
    /** Next transient annotation ID to allocate (starts at 50). */
    val nextAnnotationId: Int,
    /** Next persistent annotation ID to allocate (starts at 1). */
    val nextPersistentId: Int,
) {
    companion object {
        val INITIAL: PersistentAnnotationState = PersistentAnnotationState(
            activeAnnotations = emptyMap(),
            nextAnnotationId = 50,
            nextPersistentId = 1,
        )
    }
}
