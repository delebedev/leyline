package leyline.game.state

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Immutable persistent-annotation history owned by [ProjectionState]. */
data class PersistentAnnotationState(
    val activeAnnotations: Map<Int, AnnotationInfo>,
    val nextAnnotationId: Int,
    val nextPersistentId: Int,
) {
    companion object {
        val INITIAL: PersistentAnnotationState =
            PersistentAnnotationState(
                activeAnnotations = emptyMap(),
                nextAnnotationId = PersistentAnnotationStore.INITIAL_ANNOTATION_ID,
                nextPersistentId = PersistentAnnotationStore.INITIAL_PERSISTENT_ANNOTATION_ID,
            )
    }
}
