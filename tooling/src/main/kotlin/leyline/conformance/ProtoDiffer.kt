package leyline.conformance

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

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
        /** All fields that were visited and compared (for audit). */
        val matchedPaths: List<FieldPath> = emptyList(),
        /** Relationship pattern results when profile-aware diff was used. */
        val relationshipResults: List<ValidationResult> = emptyList(),
    ) {
        fun isEmpty(): Boolean = missing.isEmpty() && extra.isEmpty() && mismatched.isEmpty()

        fun report(verbose: Boolean = false): String = buildString {
            if (isEmpty()) {
                appendLine("MATCH — no differences found ($matched fields matched)")
            } else {
                appendLine(
                    "DIFF — ${missing.size} missing, ${extra.size} extra, " +
                        "${mismatched.size} mismatched ($matched matched)",
                )
                for (m in missing) appendLine("  - MISSING: $m")
                for (e in extra) appendLine("  + EXTRA: $e")
                for (mm in mismatched) appendLine("  ~ MISMATCH: ${mm.path} expected=${mm.expected} actual=${mm.actual}")
            }
            if (verbose && matchedPaths.isNotEmpty()) {
                appendLine("Matched fields:")
                for (p in matchedPaths) appendLine("  ✓ $p")
            }
            if (relationshipResults.isNotEmpty()) {
                appendLine()
                appendLine("Relationship results:")
                for (r in relationshipResults) {
                    val statusSymbol = when (r.status) {
                        ValidationStatus.HOLDS -> "✓"
                        ValidationStatus.MOSTLY_HOLDS -> "?"
                        ValidationStatus.VIOLATED -> "✗"
                        ValidationStatus.NO_DATA -> "-"
                        ValidationStatus.UNRESOLVABLE -> "!"
                    }
                    appendLine("  $statusSymbol ${r.pattern}")
                    for (ex in r.exceptions.take(3)) {
                        appendLine("      ${ex.detail}")
                    }
                }
            }
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

    private val SKIP_FIELDS get() = ConformanceConstants.SKIP_FIELDS
    private val ID_FIELDS get() = ConformanceConstants.ID_FIELDS

    /**
     * Repeated fields that should be aligned by key, not by index.
     * null value = custom alignment logic (see compareRepeated).
     */
    @Suppress("UnusedPrivateProperty") // used via reflection-like field name lookup
    private val KEYED_REPEATED = mapOf(
        "gameObjects" to "instanceId",
        "annotations" to null, // align by type set
        "persistentAnnotations" to null, // align by type set
        "zones" to null, // align by (type, ownerSeatId)
        "details" to "key", // KeyValuePair: align by key field
    )

    /** Repeated fields that should be compared as sorted sets. */
    private val SET_FIELDS = setOf(
        "systemSeatIds",
        "viewers",
        "objectInstanceIds",
        "affectedIds",
        "attackerIds",
        "itemsToSearch",
        "itemsSought",
    )

    // ── ID normalization ─────────────────────────────────────────────────────

    /**
     * Collects instance IDs in encounter order from a proto message.
     * Returns a map from raw ID to ordinal (1, 2, ...).
     */
    internal fun collectIds(msg: Message): Map<Int, Int> {
        val ids = linkedMapOf<Int, Int>()
        var ordinal = 1

        fun visit(message: Message) {
            for ((field, value) in message.allFields) {
                val isIdField = field.name in ID_FIELDS
                when {
                    field.isRepeated -> {
                        @Suppress("UNCHECKED_CAST")
                        val list = value as List<Any>
                        for (item in list) {
                            when {
                                item is Message -> visit(item)
                                isIdField && item is Number -> {
                                    val id = item.toInt()
                                    if (id != 0 && id !in ids) ids[id] = ordinal++
                                }
                            }
                        }
                    }
                    value is Message -> visit(value)
                    isIdField && value is Number -> {
                        val id = value.toInt()
                        if (id != 0 && id !in ids) ids[id] = ordinal++
                    }
                }
            }
        }

        visit(msg)
        return ids
    }

    /**
     * Normalize a value: replace instance IDs with ordinals, skip metadata fields.
     * Returns null as a sentinel meaning "skip this field entirely".
     */
    internal fun normalizeValue(value: Any, idMap: Map<Int, Int>, fieldName: String): Any? {
        if (fieldName in SKIP_FIELDS) return null
        if (fieldName in ID_FIELDS && value is Number) {
            val id = value.toInt()
            return idMap[id] ?: id
        }
        return value
    }

    // ── diff ─────────────────────────────────────────────────────────────────

    @Suppress("UnusedParameter") // seatMap reserved for future seat normalization
    fun diff(
        recording: GREToClientMessage,
        engine: GREToClientMessage,
        profile: SegmentProfile? = null,
        seatMap: Map<Int, Int> = emptyMap(),
    ): DiffResult {
        val recIds = collectIds(recording)
        val engIds = collectIds(engine)
        val missing = mutableListOf<FieldPath>()
        val extra = mutableListOf<FieldPath>()
        val mismatched = mutableListOf<FieldMismatch>()
        val matchedPaths = mutableListOf<FieldPath>()
        var matched = 0

        fun walk(rec: Message, eng: Message, path: FieldPath) {
            val recFields = rec.allFields
            val engFields = eng.allFields

            // Fields in recording but not engine
            for ((field, _) in recFields) {
                if (field.name in SKIP_FIELDS) continue
                if (field !in engFields) {
                    // Profile-aware: skip if field is optional (frequency < 1.0)
                    if (profile != null) {
                        val childPath = path.child(field.name)
                        val fp = profile.fieldProfiles[childPath.toString()]
                        if (fp != null && fp.frequency < 1.0) continue
                    }
                    missing.add(path.child(field.name))
                }
            }

            // Fields in engine but not recording
            for ((field, _) in engFields) {
                if (field.name in SKIP_FIELDS) continue
                if (field !in recFields) {
                    extra.add(path.child(field.name))
                }
            }

            // Compare shared fields
            for ((field, recVal) in recFields) {
                if (field.name in SKIP_FIELDS) continue
                val engVal = engFields[field] ?: continue // already reported as missing

                val childPath = path.child(field.name)
                val fieldProfileVariance = profile?.fieldProfiles?.get(childPath.toString())?.variance

                when {
                    field.isRepeated -> {
                        // Profile-aware: SIZED fields — just check non-empty
                        if (fieldProfileVariance == ValueVariance.SIZED) {
                            @Suppress("UNCHECKED_CAST")
                            val engList = engVal as List<Any>
                            if (engList.isEmpty()) {
                                mismatched.add(FieldMismatch(childPath, "non-empty", "empty"))
                            } else {
                                matched++
                                matchedPaths.add(childPath)
                            }
                        } else {
                            compareRepeated(
                                field, recVal, engVal, recIds, engIds,
                                childPath, missing, extra, mismatched,
                                {
                                    matched++
                                    matchedPaths.add(childPath)
                                },
                                ::walk,
                                fieldProfileVariance,
                                profile,
                            )
                        }
                    }
                    recVal is Message && engVal is Message -> walk(recVal, engVal, childPath)
                    else -> {
                        val normRec = normalizeValue(recVal, recIds, field.name)
                        val normEng = normalizeValue(engVal, engIds, field.name)
                        if (normRec == null) continue
                        when (fieldProfileVariance) {
                            ValueVariance.RANGED -> {
                                // Don't compare exact values — just count as matched
                                matched++
                                matchedPaths.add(childPath)
                            }
                            ValueVariance.ENUM -> {
                                // Engine value must be in observed samples
                                val samples = profile!!.fieldProfiles[childPath.toString()]!!.samples
                                if (normEng.toString() in samples) {
                                    matched++
                                    matchedPaths.add(childPath)
                                } else {
                                    mismatched.add(FieldMismatch(childPath, samples, normEng))
                                }
                            }
                            ValueVariance.ID -> {
                                // ID: normalize and check present/non-zero (existing normalization handles this)
                                if (normRec == normEng) {
                                    matched++
                                    matchedPaths.add(childPath)
                                } else {
                                    mismatched.add(FieldMismatch(childPath, normRec, normEng))
                                }
                            }
                            else -> {
                                // CONSTANT or null (no profile) — exact match
                                if (normRec == normEng) {
                                    matched++
                                    matchedPaths.add(childPath)
                                } else {
                                    mismatched.add(FieldMismatch(childPath, normRec, normEng))
                                }
                            }
                        }
                    }
                }
            }
        }

        walk(recording, engine, FieldPath.ROOT)

        // Profile-aware: validate relationships against the engine message
        val relationshipResults = if (profile != null) {
            val engineSegment = Segment(profile.category, engine, "engine", 0, engine.gameStateId)
            val patterns = RelationshipCatalog.forCategory(profile.category)
            patterns.map { RelationshipValidator.validate(it, listOf(engineSegment)) }
        } else {
            emptyList()
        }

        return DiffResult(missing, extra, mismatched, matched, matchedPaths, relationshipResults)
    }

    // ── repeated field alignment ─────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST", "LongParameterList")
    private fun compareRepeated(
        field: FieldDescriptor,
        recVal: Any,
        engVal: Any,
        recIds: Map<Int, Int>,
        engIds: Map<Int, Int>,
        path: FieldPath,
        missing: MutableList<FieldPath>,
        extra: MutableList<FieldPath>,
        mismatched: MutableList<FieldMismatch>,
        countMatch: () -> Unit,
        walk: (Message, Message, FieldPath) -> Unit,
        fieldProfileVariance: ValueVariance? = null,
        profile: SegmentProfile? = null,
    ) {
        val recList = recVal as List<Any>
        val engList = engVal as List<Any>

        // Set fields: normalize, sort, compare — but skip with profile if SIZED or ID variance
        if (field.name in SET_FIELDS) {
            if (profile != null && fieldProfileVariance in setOf(ValueVariance.SIZED, ValueVariance.ID)) {
                // Just check non-empty
                if (engList.isEmpty()) {
                    mismatched.add(FieldMismatch(path, "non-empty", "empty"))
                } else {
                    countMatch()
                }
                return
            }
            val recSorted = recList.map { normalizeValue(it, recIds, field.name) }.sortedBy { it.toString() }
            val engSorted = engList.map { normalizeValue(it, engIds, field.name) }.sortedBy { it.toString() }
            if (recSorted != engSorted) {
                mismatched.add(FieldMismatch(path, recSorted, engSorted))
            } else {
                countMatch()
            }
            return
        }

        // Message lists: key-based or index alignment
        if (recList.firstOrNull() is Message || engList.firstOrNull() is Message) {
            val recMsgs = recList as List<Message>
            val engMsgs = engList as List<Message>

            when (field.name) {
                "gameObjects" -> alignByField(
                    recMsgs, engMsgs, "instanceId",
                    { v -> (recIds[v] ?: v).toString() },
                    { v -> (engIds[v] ?: v).toString() },
                    path, missing, extra, walk,
                )
                "annotations", "persistentAnnotations" -> alignByAnnotationType(
                    recMsgs,
                    engMsgs,
                    recIds,
                    engIds,
                    path,
                    missing,
                    extra,
                    walk,
                )
                "zones" -> alignByZoneKey(
                    recMsgs,
                    engMsgs,
                    path,
                    missing,
                    extra,
                    walk,
                )
                "details" -> alignByField(
                    recMsgs, engMsgs, "key",
                    { v -> v.toString() },
                    { v -> v.toString() },
                    path, missing, extra, walk,
                )
                else -> alignByIndex(recMsgs, engMsgs, path, missing, extra, walk)
            }
            return
        }

        // Primitive lists: direct comparison after normalization
        val recNorm = recList.map { normalizeValue(it, recIds, field.name) }
        val engNorm = engList.map { normalizeValue(it, engIds, field.name) }
        if (recNorm != engNorm) {
            mismatched.add(FieldMismatch(path, recNorm, engNorm))
        } else {
            countMatch()
        }
    }

    /** Align two message lists by the string value of a named field. */
    private fun alignByField(
        recMsgs: List<Message>,
        engMsgs: List<Message>,
        fieldName: String,
        recKeyOf: (Any) -> String,
        engKeyOf: (Any) -> String,
        path: FieldPath,
        missing: MutableList<FieldPath>,
        extra: MutableList<FieldPath>,
        walk: (Message, Message, FieldPath) -> Unit,
    ) {
        fun keyOf(msg: Message, keyFn: (Any) -> String): String {
            val fd = msg.descriptorForType.findFieldByName(fieldName) ?: return msg.toString()
            val v = msg.getField(fd)
            return keyFn(v)
        }

        val recByKey = recMsgs.associateBy { keyOf(it, recKeyOf) }
        val engByKey = engMsgs.associateBy { keyOf(it, engKeyOf) }

        val allKeys = recByKey.keys + engByKey.keys
        for (key in allKeys.toSortedSet()) {
            val childPath = path.child("[$key]")
            val rec = recByKey[key]
            val eng = engByKey[key]
            when {
                rec == null -> extra.add(childPath)
                eng == null -> missing.add(childPath)
                else -> walk(rec, eng, childPath)
            }
        }
    }

    /** Align annotations by their type set. Ties broken by normalized affectorId. */
    private fun alignByAnnotationType(
        recMsgs: List<Message>,
        engMsgs: List<Message>,
        recIds: Map<Int, Int>,
        engIds: Map<Int, Int>,
        path: FieldPath,
        missing: MutableList<FieldPath>,
        extra: MutableList<FieldPath>,
        walk: (Message, Message, FieldPath) -> Unit,
    ) {
        fun typeKey(msg: Message): String {
            val fd = msg.descriptorForType.findFieldByName("type") ?: return ""

            @Suppress("UNCHECKED_CAST")
            val types = msg.getField(fd) as List<Any>
            return types.sortedBy { it.toString() }.joinToString(",") { it.toString() }
        }

        fun affectorKey(msg: Message, idMap: Map<Int, Int>): String {
            val fd = msg.descriptorForType.findFieldByName("affectorId") ?: return "0"
            val v = msg.getField(fd) as? Number ?: return "0"
            return (idMap[v.toInt()] ?: v.toInt()).toString()
        }

        fun compositeKey(msg: Message, idMap: Map<Int, Int>): String =
            "${typeKey(msg)}|${affectorKey(msg, idMap)}"

        val recByKey = recMsgs.associateBy { compositeKey(it, recIds) }
        val engByKey = engMsgs.associateBy { compositeKey(it, engIds) }

        val allKeys = (recByKey.keys + engByKey.keys).toSortedSet()
        for (key in allKeys) {
            val childPath = path.child("[$key]")
            val rec = recByKey[key]
            val eng = engByKey[key]
            when {
                rec == null -> extra.add(childPath)
                eng == null -> missing.add(childPath)
                else -> walk(rec, eng, childPath)
            }
        }
    }

    /** Align zones by (type, ownerSeatId) composite key. */
    private fun alignByZoneKey(
        recMsgs: List<Message>,
        engMsgs: List<Message>,
        path: FieldPath,
        missing: MutableList<FieldPath>,
        extra: MutableList<FieldPath>,
        walk: (Message, Message, FieldPath) -> Unit,
    ) {
        fun zoneKey(msg: Message): String {
            val typeFd = msg.descriptorForType.findFieldByName("type")
            val ownerFd = msg.descriptorForType.findFieldByName("ownerSeatId")
            val type = if (typeFd != null) msg.getField(typeFd).toString() else "?"
            val owner = if (ownerFd != null) msg.getField(ownerFd).toString() else "0"
            return "$type|$owner"
        }

        val recByKey = recMsgs.associateBy { zoneKey(it) }
        val engByKey = engMsgs.associateBy { zoneKey(it) }

        val allKeys = (recByKey.keys + engByKey.keys).toSortedSet()
        for (key in allKeys) {
            val childPath = path.child("[$key]")
            val rec = recByKey[key]
            val eng = engByKey[key]
            when {
                rec == null -> extra.add(childPath)
                eng == null -> missing.add(childPath)
                else -> walk(rec, eng, childPath)
            }
        }
    }

    /** Fallback: align by index. */
    private fun alignByIndex(
        recMsgs: List<Message>,
        engMsgs: List<Message>,
        path: FieldPath,
        missing: MutableList<FieldPath>,
        extra: MutableList<FieldPath>,
        walk: (Message, Message, FieldPath) -> Unit,
    ) {
        val maxLen = maxOf(recMsgs.size, engMsgs.size)
        for (i in 0 until maxLen) {
            val childPath = path.child("[$i]")
            when {
                i >= recMsgs.size -> extra.add(childPath)
                i >= engMsgs.size -> missing.add(childPath)
                else -> walk(recMsgs[i], engMsgs[i], childPath)
            }
        }
    }
}
