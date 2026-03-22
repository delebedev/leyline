package leyline.conformance

import com.google.protobuf.Message

/**
 * Field-by-field diff of two protobuf messages with instance ID normalization.
 *
 * Walks both messages via reflection ([Message.getAllFields]), normalizes instance IDs
 * to encounter-order ordinals, and reports missing/extra/mismatched fields.
 */
object ProtoDiffer {

    /** Dot-separated field path for human-readable diff output. */
    data class FieldPath(val segments: List<String>) {
        override fun toString(): String = segments.joinToString(".")

        fun child(name: String) = FieldPath(segments + name)

        companion object {
            val ROOT = FieldPath(emptyList())
        }
    }

    data class FieldMismatch(
        val path: FieldPath,
        val expected: Any?,
        val actual: Any?,
    )

    data class DiffResult(
        val missing: List<FieldPath>,
        val extra: List<FieldPath>,
        val mismatched: List<FieldMismatch>,
        val matched: Int,
    ) {
        fun isEmpty(): Boolean = missing.isEmpty() && extra.isEmpty() && mismatched.isEmpty()

        fun report(): String = buildString {
            if (isEmpty()) {
                appendLine("MATCH — no differences found ($matched fields matched)")
                return@buildString
            }
            appendLine(
                "DIFF — ${missing.size} missing, ${extra.size} extra, " +
                    "${mismatched.size} mismatched ($matched matched)",
            )
            for (m in missing) appendLine("  - MISSING: $m")
            for (e in extra) appendLine("  + EXTRA: $e")
            for (mm in mismatched) appendLine("  ~ MISMATCH: ${mm.path} expected=${mm.expected} actual=${mm.actual}")
        }
    }

    data class SequenceDiffResult(
        val paired: List<Pair<Int, DiffResult>>,
        val unmatchedRecording: List<Int>,
        val unmatchedEngine: List<Int>,
    ) {
        fun report(): String = buildString {
            appendLine(
                "Sequence: ${paired.size} paired, " +
                    "${unmatchedRecording.size} unmatched recording, " +
                    "${unmatchedEngine.size} unmatched engine",
            )
            for ((idx, diff) in paired) {
                if (!diff.isEmpty()) {
                    appendLine("  Frame $idx: ${diff.missing.size}m ${diff.extra.size}e ${diff.mismatched.size}mm")
                }
            }
            for (idx in unmatchedRecording) appendLine("  Recording frame $idx: NO ENGINE MATCH")
            for (idx in unmatchedEngine) appendLine("  Engine message $idx: NO RECORDING MATCH")
        }
    }

    /** Fields that always differ between recording and engine — skip during diff. */
    val SKIP_FIELDS = setOf(
        "gameStateId",
        "prevGameStateId",
        "msgId",
        "matchID",
        "timestamp",
        "transactionId",
        "requestId",
    )

    /** Field names that carry instance IDs — values are normalized to ordinals. */
    val ID_FIELDS = setOf(
        "instanceId", "affectorId", "affectedIds", "objectInstanceIds",
        "sourceId", "itemsToSearch", "itemsSought", "targetInstanceId",
        "attackerInstanceId", "blockerInstanceId", "attackerIds",
        "targetId", "parentId", "orig_id", "new_id",
    )

    /**
     * Repeated fields that should be aligned by key, not by index.
     * null value = custom alignment logic.
     */
    val KEYED_REPEATED = mapOf(
        "gameObjects" to "instanceId",
        "annotations" to null, // align by type set
        "persistentAnnotations" to null, // align by type set
        "zones" to null, // align by (type, ownerSeatId)
        "details" to "key", // KeyValuePair: align by key field
    )

    /** Repeated fields that should be compared as sorted sets. */
    val SET_FIELDS = setOf(
        "systemSeatIds",
        "viewers",
        "objectInstanceIds",
        "affectedIds",
        "attackerIds",
        "itemsToSearch",
        "itemsSought",
    )
}
