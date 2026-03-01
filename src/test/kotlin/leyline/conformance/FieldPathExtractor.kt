package leyline.conformance

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message

/**
 * Extracts the set of non-default field paths from a protobuf [Message].
 *
 * Walks the proto descriptor recursively. Each path is a dot-separated field name,
 * with `[]` for repeated fields. Only paths where the field is actually set
 * (non-default value) are included.
 *
 * Example output for a SelectTargetsReq:
 * ```
 * selectTargetsReq.sourceId
 * selectTargetsReq.targets[].targetIdx
 * selectTargetsReq.targets[].targets[].targetInstanceId
 * selectTargetsReq.targets[].targets[].highlight
 * prompt.promptId
 * allowCancel
 * allowUndo
 * ```
 *
 * Dynamic integer values (instanceIds, gsIds, etc.) are irrelevant —
 * this captures field *presence*, not values.
 */
object FieldPathExtractor {

    /**
     * Extract all set field paths from [msg].
     *
     * @param msg the protobuf message to inspect
     * @param prefix path prefix (for recursion; leave empty at top level)
     */
    fun extract(msg: Message, prefix: String = ""): Set<String> {
        val paths = mutableSetOf<String>()
        collect(msg, prefix, paths)
        return paths
    }

    private fun collect(msg: Message, prefix: String, paths: MutableSet<String>) {
        for ((field, value) in msg.allFields) {
            val name = if (prefix.isEmpty()) field.name else "$prefix.${field.name}"

            if (field.isRepeated) {
                @Suppress("UNCHECKED_CAST")
                val list = value as List<Any>
                if (list.isEmpty()) continue

                val arrayPath = "$name[]"
                if (field.type == FieldDescriptor.Type.MESSAGE) {
                    // Collect union of paths across all elements
                    for (item in list) {
                        collect(item as Message, arrayPath, paths)
                    }
                } else {
                    // Repeated scalar (e.g. objectInstanceIds, systemSeatIds)
                    paths += arrayPath
                }
            } else if (field.type == FieldDescriptor.Type.MESSAGE) {
                collect(value as Message, name, paths)
            } else {
                paths += name
            }
        }
    }

    /**
     * Format a field coverage diff for human reading.
     *
     * @param golden paths from real server recording
     * @param ours paths from our builder output
     * @param expectedMissing documented known gaps
     * @param expectedExtra documented known extras (we produce, golden doesn't)
     * @return multi-line report, empty if everything matches
     */
    fun formatDiff(
        golden: Set<String>,
        ours: Set<String>,
        expectedMissing: Set<String>,
        expectedExtra: Set<String> = emptySet(),
    ): String {
        val missing = golden - ours
        val extra = ours - golden
        val newGaps = missing - expectedMissing
        val fixedGaps = expectedMissing - missing
        val newExtras = extra - expectedExtra
        val removedExtras = expectedExtra - extra

        val sb = StringBuilder()
        if (newGaps.isNotEmpty()) {
            sb.appendLine("NEW GAPS (golden has, we don't, not in expectedMissing):")
            newGaps.sorted().forEach { sb.appendLine("  + $it") }
        }
        if (fixedGaps.isNotEmpty()) {
            sb.appendLine("FIXED GAPS (in expectedMissing but we now produce):")
            fixedGaps.sorted().forEach { sb.appendLine("  - $it") }
        }
        if (newExtras.isNotEmpty()) {
            sb.appendLine("NEW EXTRAS (we produce, golden doesn't, not in expectedExtra):")
            newExtras.sorted().forEach { sb.appendLine("  > $it") }
        }
        if (removedExtras.isNotEmpty()) {
            sb.appendLine("REMOVED EXTRAS (in expectedExtra but we no longer produce):")
            removedExtras.sorted().forEach { sb.appendLine("  < $it") }
        }
        // Context: show all documented gaps/extras for orientation
        if (expectedMissing.isNotEmpty()) {
            sb.appendLine("DOCUMENTED GAPS (${expectedMissing.size}):")
            expectedMissing.sorted().forEach { sb.appendLine("    $it") }
        }
        if (expectedExtra.isNotEmpty()) {
            sb.appendLine("DOCUMENTED EXTRAS (${expectedExtra.size}):")
            expectedExtra.sorted().forEach { sb.appendLine("    $it") }
        }
        return sb.toString()
    }
}
