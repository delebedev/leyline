package leyline.game.annotations

import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import java.util.PriorityQueue

/**
 * Enforces annotation partial-ordering constraints before numbering.
 *
 * The client processes annotations sequentially, accumulating state changes.
 * Each annotation handler sees the output of all prior handlers. This makes
 * ordering load-bearing — see individual [OrderRule] implementations for the
 * specific constraints.
 *
 * The pipeline already builds annotations in correct order by construction.
 * This enforcer is a safety net against regressions, exercised by puzzle
 * fixtures under `data/puzzles/` and the unit suite in
 * `engine/src/test/kotlin/leyline/game/`.
 *
 * Adding a new rule: implement [OrderRule] as a `data object` and add it to
 * [OrderRules.all]. The enforcer body never changes.
 */
object AnnotationOrderEnforcer {
    private val log = LoggerFactory.getLogger(AnnotationOrderEnforcer::class.java)

    /**
     * Enforce partial ordering. Returns a reordered list if violations exist,
     * or the original list unchanged if ordering is already correct.
     *
     * O(n) for the common case (no violations).
     */
    fun enforce(annotations: List<AnnotationInfo>): List<AnnotationInfo> {
        val allEdges = OrderRules.all.flatMap { it.edges(annotations) }
        if (allEdges.isEmpty()) return annotations
        val violationCount = allEdges.count { (from, to) -> from > to }
        if (violationCount == 0) return annotations
        return topologicalSort(annotations, allEdges, violationCount)
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
        violationCount: Int,
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
        val queue = PriorityQueue<Int>()
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
            // A cycle cannot be repaired while preserving the declared rules.
            log.warn("Annotation ordering: cycle detected in dependency graph, using original order")
            return annotations
        }

        // Reordering is a routine safety-net correction. A cycle above remains actionable.
        log
            .atDebug()
            .addKeyValue("event", "annotations.reordered")
            .addKeyValue("annotation_count", n)
            .addKeyValue("violation_count", violationCount)
            .log("Annotation order corrected")
        return result
    }
}
