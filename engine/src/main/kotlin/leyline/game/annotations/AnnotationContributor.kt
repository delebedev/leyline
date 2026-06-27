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
 * [contribute] may mutate bridge-attached state as a side effect — effect-id
 * allocators (crew / reconfigure / mutate-merge) and the convoke payment journal
 * are read-then-advanced here. That is why invocation order is load-bearing and
 * why [AnnotationPipeline] calls contributors at fixed phase-correct sites rather
 * than via a rank-sorted loop.
 *
 * [rank] is descriptive, not a runtime sort key: it records the canonical order
 * the call sites already follow (lower contributes earlier), so the registry
 * reads in the same order the spine invokes. The transient stream is
 * order-sensitive — the client accumulates state as it processes annotations.
 */
interface AnnotationContributor {
    /** Canonical contribution order (descriptive); lower contributes earlier. */
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
