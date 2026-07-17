package leyline.game.annotations

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

data class FinalizedAnnotationFrame(
    val annotations: List<AnnotationInfo>,
    val nextId: Int,
)

/** Applies same-GSM ordering and transient IDs after every frame rider is known. */
object AnnotationFrameFinalizer {
    fun finalize(
        annotations: List<AnnotationInfo>,
        firstId: Int,
    ): FinalizedAnnotationFrame {
        require(firstId > 0) { "Transient annotation IDs must be positive" }
        val ordered = AnnotationOrderEnforcer.enforce(annotations)
        val numbered = ordered.mapIndexed { index, annotation -> annotation.toBuilder().setId(firstId + index).build() }
        return FinalizedAnnotationFrame(numbered, firstId + numbered.size)
    }
}
