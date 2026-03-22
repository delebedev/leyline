package leyline.conformance

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * **DEPRECATED:** Use [ProtoDiffer] for direct proto comparison instead of
 * serializing to JSON. See `docs/conformance/how-we-conform.md`.
 *
 * Serialize proto annotations to the same flat JSON format used by
 * recording decoder (md-frames.jsonl). Enables Python-side comparison
 * between recording templates and engine output.
 *
 * Recording format:
 * ```json
 * {
 *   "id": 83,
 *   "types": ["ObjectIdChanged"],
 *   "affectorId": 1,
 *   "affectedIds": [282],
 *   "details": { "orig_id": 161, "new_id": 282 }
 * }
 * ```
 */
object AnnotationSerializer {

    /** Serialize a list of GREToClientMessages to recording-compatible JSON. */
    fun toRecordingJson(messages: List<GREToClientMessage>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        val entries = mutableListOf<String>()
        for (msg in messages) {
            if (!msg.hasGameStateMessage()) continue
            val gsm = msg.gameStateMessage
            val frame = mutableMapOf<String, Any>()
            frame["gsId"] = gsm.gameStateId
            frame["prevGsId"] = gsm.prevGameStateId
            frame["gsmType"] = gsm.type.name.removeSuffix("_695e")
            frame["annotations"] = gsm.annotationsList.map { serializeAnnotation(it) }
            frame["persistentAnnotations"] = gsm.persistentAnnotationsList.map { serializeAnnotation(it) }
            // Zones
            frame["zones"] = gsm.zonesList.map { zone ->
                mapOf(
                    "zoneId" to zone.zoneId,
                    "type" to zone.type.name.removeSuffix("_695e").removeSuffix("_aa0d").removeSuffix("_a099").removeSuffix("_a455"),
                    "owner" to zone.ownerSeatId,
                    "objectIds" to zone.objectInstanceIdsList.toList(),
                )
            }
            // Objects
            frame["objects"] = gsm.gameObjectsList.map { obj ->
                val m = mutableMapOf<String, Any>()
                m["instanceId"] = obj.instanceId
                m["grpId"] = obj.grpId
                m["zoneId"] = obj.zoneId
                m["cardTypes"] = obj.cardTypesList.map { it.name }
                m["subtypes"] = obj.subtypesList.map { it.name }
                m["superTypes"] = obj.superTypesList.map { it.name }
                m
            }
            entries.add(toJson(frame))
        }
        sb.append(entries.joinToString(",\n"))
        sb.append("\n]")
        return sb.toString()
    }

    /** Serialize annotations from messages that have a specific ZoneTransfer category. */
    fun extractByCategory(
        messages: List<GREToClientMessage>,
        category: String,
    ): Map<String, Any>? {
        for (msg in messages) {
            if (!msg.hasGameStateMessage()) continue
            val gsm = msg.gameStateMessage
            val hasCategory = gsm.annotationsList.any { ann ->
                ann.detailsList.any { d ->
                    d.key == "category" && d.valueStringList.any { it == category }
                }
            }
            if (hasCategory) {
                return mapOf(
                    "gsId" to gsm.gameStateId,
                    "prevGsId" to gsm.prevGameStateId,
                    "gsmType" to gsm.type.name,
                    "annotations" to gsm.annotationsList.map { serializeAnnotation(it) },
                    "persistentAnnotations" to gsm.persistentAnnotationsList.map { serializeAnnotation(it) },
                    "zones" to gsm.zonesList.map { zone ->
                        mapOf(
                            "zoneId" to zone.zoneId,
                            "type" to zone.type.name,
                            "objectIds" to zone.objectInstanceIdsList.toList(),
                        )
                    },
                    "objects" to gsm.gameObjectsList.map { obj ->
                        mapOf(
                            "instanceId" to obj.instanceId,
                            "grpId" to obj.grpId,
                            "zoneId" to obj.zoneId,
                            "cardTypes" to obj.cardTypesList.map { it.name },
                            "subtypes" to obj.subtypesList.map { it.name },
                        )
                    },
                )
            }
        }
        return null
    }

    /**
     * Serialize a message sequence to JSON array — one entry per GSM.
     * Includes greType so the Python-side sequence diff can distinguish
     * QueuedGameStateMessage from GameStateMessage.
     */
    fun toSequenceJson(messages: List<GREToClientMessage>): String {
        val frames = mutableListOf<Map<String, Any>>()
        for (msg in messages) {
            if (!msg.hasGameStateMessage()) continue
            val gsm = msg.gameStateMessage
            frames.add(
                mapOf(
                    "gsId" to gsm.gameStateId,
                    "prevGsId" to gsm.prevGameStateId,
                    "greType" to msg.type.name.replace(Regex("_[a-f0-9]{4}$"), ""),
                    "gsmType" to gsm.type.name.replace(Regex("_[a-f0-9]{4}$"), ""),
                    "updateType" to gsm.update.name.replace(Regex("_[a-f0-9]{4}$"), ""),
                    "annotations" to gsm.annotationsList.map { serializeAnnotation(it) },
                    "persistentAnnotations" to gsm.persistentAnnotationsList.map { serializeAnnotation(it) },
                ),
            )
        }
        return toJson(frames)
    }

    private fun serializeAnnotation(ann: AnnotationInfo): Map<String, Any> {
        val m = mutableMapOf<String, Any>()
        m["id"] = ann.id
        m["types"] = ann.typeList.map { cleanTypeName(it.name) }
        if (ann.affectorId != 0) m["affectorId"] = ann.affectorId
        if (ann.affectedIdsList.isNotEmpty()) m["affectedIds"] = ann.affectedIdsList.toList()

        val details = mutableMapOf<String, Any>()
        for (d in ann.detailsList) {
            when {
                d.valueStringList.isNotEmpty() -> {
                    details[d.key] = if (d.valueStringList.size == 1) d.valueStringList[0] else d.valueStringList.toList()
                }
                d.valueInt32List.isNotEmpty() -> {
                    details[d.key] = if (d.valueInt32List.size == 1) d.valueInt32List[0] else d.valueInt32List.toList()
                }
                d.valueUint32List.isNotEmpty() -> {
                    details[d.key] = if (d.valueUint32List.size == 1) d.valueUint32List[0] else d.valueUint32List.toList()
                }
            }
        }
        if (details.isNotEmpty()) m["details"] = details
        return m
    }

    /** Strip proto enum suffixes for readability (ZoneTransfer_af5a → ZoneTransfer). */
    private fun cleanTypeName(name: String): String =
        name.replace(Regex("_[a-f0-9]{4}$"), "")

    /** Minimal JSON serializer (no external deps). */
    private fun toJson(obj: Any?, indent: Int = 2): String {
        val sb = StringBuilder()
        writeJson(sb, obj, 0, indent)
        return sb.toString()
    }

    private fun writeJson(sb: StringBuilder, obj: Any?, depth: Int, indent: Int) {
        val pad = " ".repeat(depth * indent)
        val childPad = " ".repeat((depth + 1) * indent)
        when (obj) {
            null -> sb.append("null")
            is String -> sb.append("\"${obj.replace("\"", "\\\"")}\"")
            is Number -> sb.append(obj)
            is Boolean -> sb.append(obj)
            is Map<*, *> -> {
                if (obj.isEmpty()) {
                    sb.append("{}")
                } else {
                    sb.append("{\n")
                    val entries = obj.entries.toList()
                    for ((i, entry) in entries.withIndex()) {
                        sb.append("$childPad\"${entry.key}\": ")
                        writeJson(sb, entry.value, depth + 1, indent)
                        if (i < entries.size - 1) sb.append(",")
                        sb.append("\n")
                    }
                    sb.append("$pad}")
                }
            }
            is List<*> -> {
                if (obj.isEmpty()) {
                    sb.append("[]")
                } else if (obj.all { it is Number || it is String }) {
                    // Inline short lists
                    sb.append("[")
                    sb.append(obj.joinToString(", ") { if (it is String) "\"$it\"" else "$it" })
                    sb.append("]")
                } else {
                    sb.append("[\n")
                    for ((i, item) in obj.withIndex()) {
                        sb.append(childPad)
                        writeJson(sb, item, depth + 1, indent)
                        if (i < obj.size - 1) sb.append(",")
                        sb.append("\n")
                    }
                    sb.append("$pad]")
                }
            }
            else -> sb.append("\"$obj\"")
        }
    }
}
