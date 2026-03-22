package leyline.conformance

import com.google.protobuf.Message

/**
 * Computes per-field variance profiles from a collection of segment instances.
 *
 * For each field path in the proto message, determines: how often it's present,
 * whether its value is constant/enum/ID/ranged/sized, and collects samples.
 */
object FieldVarianceProfiler {

    private val PROTO_SUFFIX = Regex("_[a-f0-9]{3,4}$")

    /** Fields to skip — session-specific metadata, not protocol-meaningful. */
    private val SKIP_FIELDS = setOf(
        "gameStateId",
        "prevGameStateId",
        "msgId",
        "matchID",
        "timestamp",
        "transactionId",
        "requestId",
    )

    /** Fields known to carry instance IDs. */
    private val ID_FIELDS = setOf(
        "instanceId", "affectorId", "affectedIds", "objectInstanceIds",
        "sourceId", "itemsToSearch", "itemsSought", "targetInstanceId",
        "attackerInstanceId", "blockerInstanceId", "attackerIds",
        "targetId", "parentId",
    )

    fun profile(segments: List<Segment>): SegmentProfile {
        if (segments.isEmpty()) return emptyProfile("Empty")

        val category = segments.first().category
        val sessions = segments.map { it.session }.toSet()
        val n = segments.size

        // Collect field values across all instances
        val fieldValues = mutableMapOf<String, MutableList<String>>() // path -> values (as strings)
        val fieldPresence = mutableMapOf<String, Int>() // path -> count of instances where present

        for (segment in segments) {
            val presentPaths = mutableSetOf<String>()
            walkMessage(segment.message, "", presentPaths, fieldValues)
            for (path in presentPaths) {
                fieldPresence[path] = (fieldPresence[path] ?: 0) + 1
            }
        }

        // Build field profiles
        val fieldProfiles =
            fieldValues.mapNotNull { (path, values) ->
                val fieldName = path.substringAfterLast(".")
                if (fieldName in SKIP_FIELDS) return@mapNotNull null

                val presence = fieldPresence[path] ?: 0
                val frequency = presence.toDouble() / n
                val distinct = values.toSet()
                val variance = classifyVariance(fieldName, distinct, values.size)
                val samples = distinct.take(5).toList()

                path to FieldProfile(frequency, variance, samples)
            }.toMap()

        // Compute annotation presence
        val annotationPresence = computeAnnotationPresence(segments)

        return SegmentProfile(
            category = category,
            instanceCount = n,
            sessionCount = sessions.size,
            confidence = Confidence.from(n, sessions.size),
            fieldProfiles = fieldProfiles,
            annotationPresence = annotationPresence,
        )
    }

    private fun classifyVariance(
        fieldName: String,
        distinct: Set<String>,
        totalCount: Int,
    ): ValueVariance {
        if (fieldName in ID_FIELDS) return ValueVariance.ID
        if (distinct.size == 1) return ValueVariance.CONSTANT
        if (distinct.size <= 10) return ValueVariance.ENUM
        // Check if numeric range
        val numbers = distinct.mapNotNull { it.toIntOrNull() }
        if (numbers.size == distinct.size) return ValueVariance.RANGED
        return ValueVariance.SIZED
    }

    /** Walk a proto message, collecting field values by path. */
    private fun walkMessage(
        msg: Message,
        prefix: String,
        presentPaths: MutableSet<String>,
        fieldValues: MutableMap<String, MutableList<String>>,
    ) {
        for ((field, value) in msg.allFields) {
            val path = if (prefix.isEmpty()) field.name else "$prefix.${field.name}"
            presentPaths.add(path)

            when {
                field.isRepeated -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = value as List<Any>
                    // Track size as a value for SIZED detection
                    fieldValues.getOrPut("$path._size") { mutableListOf() }.add(list.size.toString())
                    presentPaths.add("$path._size")

                    for (item in list) {
                        if (item is Message) {
                            walkMessage(item, path, presentPaths, fieldValues)
                        } else {
                            fieldValues.getOrPut(path) { mutableListOf() }.add(item.toString())
                        }
                    }
                }
                value is Message -> walkMessage(value, path, presentPaths, fieldValues)
                else -> fieldValues.getOrPut(path) { mutableListOf() }.add(value.toString())
            }
        }
    }

    private fun computeAnnotationPresence(segments: List<Segment>): Map<String, AnnotationPresence> {
        val n = segments.size
        val typeCounts = mutableMapOf<String, Int>()
        val coOccurrence = mutableMapOf<String, MutableMap<String, Int>>()

        for (segment in segments) {
            if (!segment.message.hasGameStateMessage()) continue
            val gsm = segment.message.gameStateMessage
            val typesInSegment = mutableSetOf<String>()

            for (ann in gsm.annotationsList + gsm.persistentAnnotationsList) {
                for (type in ann.typeList) {
                    typesInSegment.add(type.name.replace(PROTO_SUFFIX, ""))
                }
            }

            for (type in typesInSegment) {
                typeCounts[type] = (typeCounts[type] ?: 0) + 1
            }

            // Track co-occurrence
            for (a in typesInSegment) {
                for (b in typesInSegment) {
                    if (a != b) {
                        coOccurrence.getOrPut(a) { mutableMapOf() }
                            .merge(b, 1) { old, new -> old + new }
                    }
                }
            }
        }

        return typeCounts
            .map { (type, count) ->
                val frequency = count.toDouble() / n
                val coOccurs =
                    coOccurrence[type]
                        ?.filter { it.value == count } // only types that ALWAYS co-occur
                        ?.keys
                        ?.toSet()
                        ?: emptySet()
                type to AnnotationPresence(frequency, count, coOccurs)
            }.toMap()
    }

    private fun emptyProfile(category: String) =
        SegmentProfile(category, 0, 0, Confidence.TENTATIVE, emptyMap(), emptyMap())
}
