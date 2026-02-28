package forge.nexus.debug

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import forge.nexus.NexusPaths
import forge.nexus.recording.RecordingDecoder
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.io.File

private val INT_TYPES = setOf(
    FieldDescriptor.Type.INT32,
    FieldDescriptor.Type.UINT32,
    FieldDescriptor.Type.SINT32,
    FieldDescriptor.Type.FIXED32,
    FieldDescriptor.Type.SFIXED32,
)

/**
 * Trace an ID across all recorded client payloads.
 *
 * Recursively walks every proto field via reflection. When an ObjectIdChanged
 * annotation renames orig_id → new_id, the new ID is added to the trace set
 * so subsequent payloads show the continuation.
 *
 * Usage: proto-trace <id> [payloads-dir]
 */
fun main(args: Array<String>) {
    val targetId = args.firstOrNull()?.toIntOrNull()
    if (targetId == null) {
        System.err.println("Usage: proto-trace <id> [payloads-dir]")
        System.exit(1)
        return
    }

    val dir = File(args.getOrElse(1) { NexusPaths.CAPTURE_PAYLOADS.absolutePath })
    if (!dir.isDirectory) {
        System.err.println("Not a directory: $dir")
        System.exit(1)
        return
    }

    val files = RecordingDecoder.listRecordingFiles(dir)

    if (files.isEmpty()) {
        System.err.println("No .bin files in $dir")
        System.exit(1)
        return
    }

    val tracing = mutableSetOf(targetId)
    val labels = mutableMapOf<Int, String>() // instanceId → "Land/Swamp" etc.
    var totalHits = 0
    var filesWithHits = 0

    println("Tracing ID: $targetId")
    println("Payloads: ${files.size} files in $dir")
    println()

    for (file in files) {
        val msg = RecordingDecoder.parseMatchMessage(file.readBytes()) ?: continue

        if (!msg.hasGreToClientEvent()) continue
        val greMessages = msg.greToClientEvent.greToClientMessagesList

        // Learn card labels from gameObjects before reporting hits
        for (gre in greMessages) {
            learnLabels(gre, tracing, labels)
        }

        val fileHits = mutableListOf<String>()

        for ((i, gre) in greMessages.withIndex()) {
            val hits = mutableListOf<String>()
            walkMessage(gre, "gre", tracing, hits)
            if (hits.isNotEmpty()) {
                val header = buildString {
                    append("GRE[$i]: ${gre.type}")
                    if (gre.gameStateId != 0) append(" gsId=${gre.gameStateId}")
                    if (gre.msgId != 0) append(" msgId=${gre.msgId}")
                }
                fileHits += "  $header"
                hits.forEach { fileHits += "    $it" }
            }
        }

        // Check for ObjectIdChanged renames
        val renames = checkRenames(greMessages, tracing)
        for ((oldId, newId) in renames) {
            val oldLabel = labels[oldId]?.let { " ($it)" } ?: ""
            fileHits += "  -> ID renamed: $oldId$oldLabel -> $newId, now also tracing $newId"
        }

        if (fileHits.isNotEmpty()) {
            println("=== ${file.name} ===")
            fileHits.forEach(::println)
            println()
            totalHits += fileHits.count { it.startsWith("    ") }
            filesWithHits++
        }
    }

    // Print legend of all labeled IDs
    if (labels.isNotEmpty()) {
        println("Labels: ${labels.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
    }
    println("Summary: $totalHits hits across $filesWithHits files. Traced IDs: $tracing")
}

private fun walkMessage(
    msg: Message,
    path: String,
    targets: Set<Int>,
    hits: MutableList<String>,
) {
    for ((field, value) in msg.allFields) {
        val name = field.name
        when {
            field.type == FieldDescriptor.Type.MESSAGE && field.isRepeated -> {
                @Suppress("UNCHECKED_CAST")
                for ((idx, sub) in (value as List<Message>).withIndex()) {
                    walkMessage(sub, "$path.$name[$idx]", targets, hits)
                }
            }
            field.type == FieldDescriptor.Type.MESSAGE -> {
                walkMessage(value as Message, "$path.$name", targets, hits)
            }
            field.type in INT_TYPES && field.isRepeated -> {
                @Suppress("UNCHECKED_CAST")
                for ((idx, v) in (value as List<Number>).withIndex()) {
                    if (v.toInt() in targets) {
                        val ctx = detailContext(msg, field, idx)
                        hits += "$path.$name[$idx] = $v$ctx"
                    }
                }
            }
            field.type in INT_TYPES -> {
                if ((value as Number).toInt() in targets) {
                    hits += "$path.$name = $value"
                }
            }
        }
    }
}

/** Add context hint for KeyValuePairInfo value fields (show the key name). */
private fun detailContext(msg: Message, field: FieldDescriptor, @Suppress("UNUSED_PARAMETER") idx: Int): String {
    // If this is a value field inside a KeyValuePairInfo, show the key
    val keyField = msg.descriptorForType.findFieldByName("key") ?: return ""
    val key = msg.getField(keyField) as? String ?: return ""
    if (key.isNotEmpty()) return "  (key=\"$key\")"
    return ""
}

/** Extract card type/subtype labels from gameObjects for any traced IDs. */
private fun learnLabels(gre: GREToClientMessage, targets: Set<Int>, labels: MutableMap<Int, String>) {
    if (!gre.hasGameStateMessage()) return
    for (obj in gre.gameStateMessage.gameObjectsList) {
        if (obj.instanceId in targets && obj.instanceId !in labels) {
            val parts = buildList {
                for (st in obj.superTypesList) add(st.name.replace(Regex("_.*"), ""))
                for (ct in obj.cardTypesList) add(ct.name.replace(Regex("_.*"), ""))
                if (isEmpty()) add(obj.type.name.replace(Regex("_.*"), ""))
                for (sub in obj.subtypesList) add(sub.name.replace(Regex("_.*"), ""))
            }
            labels[obj.instanceId] = parts.joinToString("/")
        }
    }
}

private fun checkRenames(
    greMessages: List<com.google.protobuf.Message>,
    targets: MutableSet<Int>,
): List<Pair<Int, Int>> {
    val renames = mutableListOf<Pair<Int, Int>>()
    for (gre in greMessages) {
        val gsmField = gre.descriptorForType.findFieldByName("gameStateMessage") ?: continue
        if (!gre.hasField(gsmField)) continue
        val gsm = gre.getField(gsmField) as Message
        val annotField = gsm.descriptorForType.findFieldByName("annotations") ?: continue

        @Suppress("UNCHECKED_CAST")
        val annotations = gsm.getField(annotField) as List<Message>

        for (annot in annotations) {
            val typeField = annot.descriptorForType.findFieldByName("type") ?: continue

            @Suppress("UNCHECKED_CAST")
            val types = annot.getField(typeField) as List<*>
            val isIdChanged = types.any {
                it is com.google.protobuf.Descriptors.EnumValueDescriptor &&
                    it.name == AnnotationType.ObjectIdChanged.name
            }
            if (!isIdChanged) continue

            val detailsField = annot.descriptorForType.findFieldByName("details") ?: continue

            @Suppress("UNCHECKED_CAST")
            val details = annot.getField(detailsField) as List<Message>

            var origId: Int? = null
            var newId: Int? = null
            for (detail in details) {
                val key = detail.getField(detail.descriptorForType.findFieldByName("key")) as? String
                val uint32Field = detail.descriptorForType.findFieldByName("valueUint32")
                val int32Field = detail.descriptorForType.findFieldByName("valueInt32")

                @Suppress("UNCHECKED_CAST")
                val uint32s = if (uint32Field != null) detail.getField(uint32Field) as List<Number> else emptyList()

                @Suppress("UNCHECKED_CAST")
                val int32s = if (int32Field != null) detail.getField(int32Field) as List<Number> else emptyList()
                val v = uint32s.firstOrNull()?.toInt() ?: int32s.firstOrNull()?.toInt()
                when (key) {
                    "orig_id" -> origId = v
                    "new_id" -> newId = v
                }
            }

            if (origId != null && newId != null && origId in targets && newId !in targets) {
                targets += newId
                renames += origId to newId
            }
        }
    }
    return renames
}
