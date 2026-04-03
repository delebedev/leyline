package leyline.game

import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Enforces annotation partial-ordering constraints before numbering.
 *
 * The client processes annotations sequentially, accumulating state changes.
 * Each annotation handler sees the output of all prior handlers. This makes
 * ordering load-bearing for two reasons:
 *
 * **Rule 1 — ObjectIdChanged first:** Must precede any annotation referencing
 * the new instanceId. The ObjectIdChanged handler populates the identity map;
 * downstream handlers expect the mapping to exist when they encounter the new ID.
 *
 * **Rule 2 — Same-card incremental chaining:** When two annotation handlers
 * both modify the same card via incremental entity updates, the second builds
 * on the first's output via a reverse scan. Wrong order = stale entity state.
 *
 * Ordering rules derived from recording analysis (29 sessions, 7676 messages).
 * The pipeline already builds annotations in correct order by construction.
 * This enforcer is a safety net against regressions.
 */
object AnnotationOrderEnforcer {

    private val log = LoggerFactory.getLogger(AnnotationOrderEnforcer::class.java)

    /**
     * Incremental entity annotation metadata for same-card chaining (Rule 2).
     *
     * Lower precedence = must come first when two types affect the same card.
     * [cardIdFromAffected] = true if the card ID is in affectedIds (most types),
     * false if it's in affectorId (LayeredEffectCreated, AttachmentCreated).
     */
    private data class IncrementalSpec(val precedence: Int, val cardIdFromAffected: Boolean = true)

    /**
     * Precedence table validated against 29 recording sessions (7676 messages).
     *
     * Observed server ordering:
     * - LayeredEffectCreated BEFORE AttachmentCreated (10 instances)
     * - ControllerChanged BEFORE TappedUntapped (2 instances)
     */
    private val INCREMENTAL_SPECS: Map<AnnotationType, IncrementalSpec> = mapOf(
        AnnotationType.ControllerChanged to IncrementalSpec(0),
        AnnotationType.TappedUntappedPermanent to IncrementalSpec(1),
        AnnotationType.DamageDealt_af5a to IncrementalSpec(2),
        AnnotationType.CounterAdded to IncrementalSpec(3),
        AnnotationType.CounterRemoved to IncrementalSpec(3),
        AnnotationType.PowerToughnessModCreated to IncrementalSpec(4),
        AnnotationType.LayeredEffectCreated to IncrementalSpec(5, cardIdFromAffected = false),
        AnnotationType.AttachmentCreated to IncrementalSpec(6, cardIdFromAffected = false),
    )

    /**
     * Enforce partial ordering. Returns a reordered list if violations exist,
     * or the original list unchanged if ordering is already correct.
     *
     * O(n) for the common case (no violations).
     */
    fun enforce(annotations: List<AnnotationInfo>): List<AnnotationInfo> {
        val rule1Edges = buildRule1Edges(annotations)
        val rule2Edges = buildRule2Edges(annotations)

        if (rule1Edges.isEmpty() && rule2Edges.isEmpty()) return annotations

        // Check for violations before doing any work
        val allEdges = rule1Edges + rule2Edges
        val hasViolation = allEdges.any { (from, to) -> from > to }

        if (!hasViolation) return annotations

        logViolations(annotations, allEdges)
        return topologicalSort(annotations, allEdges)
    }

    /**
     * Rule 1: ObjectIdChanged must precede any annotation referencing its new_id.
     * Returns edges as (fromIndex, toIndex) pairs.
     */
    private fun buildRule1Edges(annotations: List<AnnotationInfo>): List<Pair<Int, Int>> {
        val newIdToOicIndex = mutableMapOf<Int, Int>()
        for ((i, ann) in annotations.withIndex()) {
            if (AnnotationType.ObjectIdChanged in ann.typeList) {
                val newId = ann.detailInt(DetailKeys.NEW_ID)
                if (newId != 0) newIdToOicIndex[newId] = i
            }
        }
        if (newIdToOicIndex.isEmpty()) return emptyList()

        val edges = mutableListOf<Pair<Int, Int>>()
        for ((i, ann) in annotations.withIndex()) {
            if (AnnotationType.ObjectIdChanged in ann.typeList) continue
            val refs = referencedIds(ann)
            for (refId in refs) {
                val oicIndex = newIdToOicIndex[refId] ?: continue
                edges.add(oicIndex to i) // OIC must come before this annotation
            }
        }
        return edges
    }

    /**
     * Rule 2: Same-card incremental annotations must follow precedence order.
     * Returns edges as (fromIndex, toIndex) pairs.
     */
    private fun buildRule2Edges(annotations: List<AnnotationInfo>): List<Pair<Int, Int>> {
        val cardToAnnotations = mutableMapOf<Int, MutableList<Int>>()
        for ((i, ann) in annotations.withIndex()) {
            val spec = annotationSpec(ann) ?: continue
            for (cardId in cardIdsFor(ann, spec)) {
                if (cardId != 0) {
                    cardToAnnotations.getOrPut(cardId) { mutableListOf() }.add(i)
                }
            }
        }

        val edges = mutableListOf<Pair<Int, Int>>()
        for ((_, indices) in cardToAnnotations) {
            if (indices.size < 2) continue
            for (a in indices.indices) {
                for (b in a + 1 until indices.size) {
                    val idxA = indices[a]
                    val idxB = indices[b]
                    val precA = annotationSpec(annotations[idxA])?.precedence ?: continue
                    val precB = annotationSpec(annotations[idxB])?.precedence ?: continue
                    if (precA == precB) continue
                    if (precA < precB) {
                        edges.add(idxA to idxB)
                    } else {
                        edges.add(idxB to idxA)
                    }
                }
            }
        }
        return edges
    }

    /** Get the incremental spec for an annotation, or null if not applicable. */
    private fun annotationSpec(ann: AnnotationInfo): IncrementalSpec? =
        ann.typeList.firstNotNullOfOrNull { INCREMENTAL_SPECS[it] }

    /** Extract card IDs from an annotation based on its spec. */
    private fun cardIdsFor(ann: AnnotationInfo, spec: IncrementalSpec): List<Int> =
        if (spec.cardIdFromAffected) {
            ann.affectedIdsList.toList()
        } else {
            if (ann.affectorId != 0) listOf(ann.affectorId) else emptyList()
        }

    /** All IDs referenced by an annotation (affectedIds + affectorId). */
    private fun referencedIds(ann: AnnotationInfo): Set<Int> = buildSet {
        addAll(ann.affectedIdsList)
        if (ann.affectorId != 0) add(ann.affectorId)
    }

    /**
     * Topological sort respecting edge constraints. Preserves original order
     * where no constraint exists (stable).
     *
     * Uses Kahn's algorithm with original-index tie-breaking for stability.
     */
    private fun topologicalSort(
        annotations: List<AnnotationInfo>,
        edges: List<Pair<Int, Int>>,
    ): List<AnnotationInfo> {
        val n = annotations.size
        val inDegree = IntArray(n)
        val adjList = Array(n) { mutableListOf<Int>() }

        for ((from, to) in edges) {
            if (from == to) continue
            adjList[from].add(to)
            inDegree[to]++
        }

        // Priority queue with original index as tie-breaker (stable order)
        val queue = java.util.PriorityQueue<Int>()
        for (i in 0 until n) {
            if (inDegree[i] == 0) queue.add(i)
        }

        val result = mutableListOf<AnnotationInfo>()
        while (queue.isNotEmpty()) {
            val idx = queue.poll()
            result.add(annotations[idx])
            for (next in adjList[idx]) {
                inDegree[next]--
                if (inDegree[next] == 0) queue.add(next)
            }
        }

        if (result.size < n) {
            // Cycle detected — fall back to original order with warning
            log.warn("Annotation ordering: cycle detected in dependency graph, using original order")
            return annotations
        }

        log.info("Annotation ordering enforced: reordered {} annotations", n)
        return result
    }

    private fun logViolations(annotations: List<AnnotationInfo>, edges: List<Pair<Int, Int>>) {
        for ((from, to) in edges) {
            if (from > to) {
                val fromType = annotations[from].typeList
                val toType = annotations[to].typeList
                log.warn(
                    "Annotation ordering violation: {} at index {} must precede {} at index {}",
                    fromType,
                    from,
                    toType,
                    to,
                )
            }
        }
    }

    /** Extract int32 detail value by key, returns 0 if not found. */
    private fun AnnotationInfo.detailInt(key: String): Int =
        detailsList.firstOrNull { it.key == key }?.let {
            if (it.valueInt32Count > 0) it.getValueInt32(0) else 0
        } ?: 0
}
