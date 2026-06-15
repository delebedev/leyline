package leyline.game.annotations

import leyline.game.state.PersistentAnnotationKind
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * A mechanic-scoped source of annotations, registered with [AnnotationPipeline].
 *
 * Each contributor turns the shared annotation-time state ([AnnotationContext])
 * into a [Contribution]: the transient annotations spliced into the per-frame
 * stream plus the persistent annotations fed, keyed by [PersistentAnnotationKind],
 * into the persistent-annotation store batch. This replaces the historical
 * hand-wired per-mechanic emitter functions and `put(Kind, ...)` calls inside
 * the spine.
 *
 * Contract for slice ordering: a lower [rank] contributes earlier. Ranks are
 * chosen to reproduce the current emission order exactly when emitters are
 * ported onto this seam (see the AnnotationPipeline extraction epic). The
 * transient stream is order-sensitive — the client accumulates state as it
 * processes annotations — so rank pins the relative position of each
 * contributor's transient block.
 */
interface AnnotationContributor {
    /** Ordering key; lower contributes earlier. */
    val rank: Int

    fun contribute(ctx: AnnotationContext): Contribution
}

/**
 * Output of one [AnnotationContributor].
 *
 * - [transient] — annotations spliced into the per-frame stream, ordered by the
 *   contributor's [AnnotationContributor.rank].
 * - [persistent] — annotations fed into the persistent-annotation store batch,
 *   keyed by [PersistentAnnotationKind]. Multiple contributors may target the
 *   same kind (e.g. crew + reconfigure both feed `ModifiedTypeForCrewKind`); the
 *   pipeline concatenates their lists in rank order.
 */
data class Contribution(
    val transient: List<AnnotationInfo> = emptyList(),
    val persistent: Map<PersistentAnnotationKind, List<AnnotationInfo>> = emptyMap(),
)
