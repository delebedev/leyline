package leyline.game.annotations

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * A mechanic-scoped source of annotations, registered with [AnnotationPipeline].
 *
 * Each contributor turns the shared annotation-time state ([AnnotationContext])
 * into a flat list of annotations. The pipeline collects every contributor's
 * output and orders it by [rank], replacing the historical hand-wired
 * `addAll(...)` / `addAll(index, ...)` sequencing inside the spine.
 *
 * Contract for slice ordering: a lower [rank] contributes earlier. Ranks are
 * chosen to reproduce the current emission order exactly when emitters are
 * ported onto this seam (see the AnnotationPipeline extraction epic).
 */
interface AnnotationContributor {
    /** Ordering key; lower contributes earlier. */
    val rank: Int

    fun contribute(ctx: AnnotationContext): List<AnnotationInfo>
}
