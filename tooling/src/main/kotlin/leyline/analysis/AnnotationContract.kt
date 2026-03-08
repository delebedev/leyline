package leyline.analysis

import leyline.recording.RecordingDecoder
import java.io.File

/**
 * Extracts the full annotation "contract" from proxy recordings.
 *
 * Given a session + annotation type (+ optional effectId), dumps:
 * - Type arrays (multi-type co-occurrence)
 * - affectorId patterns
 * - Detail keys with value types and samples
 * - Companion annotations in the same GSM
 * - Lifecycle (created → persistent → destroyed)
 *
 * Designed to be the "nail it from first attempt" tool — run this
 * before writing any annotation implementation code.
 */
object AnnotationContract {

    data class ContractResult(
        val typeName: String,
        val instances: List<Instance>,
        val typeArrays: Map<List<String>, Int>,
        val affectorIdPattern: String,
        val detailKeys: Map<String, KeyProfile>,
        val companions: Map<String, Int>,
        val lifecycle: List<String>,
    )

    data class Instance(
        val gsId: Int,
        val annotationId: Int,
        val types: List<String>,
        val affectorId: Int,
        val affectedIds: List<Int>,
        val details: Map<String, Any>,
        val isPersistent: Boolean,
        val companionTypes: List<String>,
    )

    data class KeyProfile(
        val frequency: String,
        val sampleValues: List<String>,
        val valueType: String,
    )

    /**
     * Extract full contract for [typeName] from a recording session.
     * If [effectId] is set, narrows to annotations referencing that effect ID.
     */
    fun extract(sessionDir: File, typeName: String, effectId: Int? = null): ContractResult? {
        val engineDir = File(sessionDir, "engine")
        val captureDir = File(sessionDir, "capture/payloads")
        val sourceDir = when {
            captureDir.isDirectory && RecordingDecoder.listRecordingFiles(captureDir).isNotEmpty() -> captureDir
            engineDir.isDirectory && RecordingDecoder.listRecordingFiles(engineDir).isNotEmpty() -> engineDir
            else -> return null
        }

        val messages = RecordingDecoder.decodeDirectory(sourceDir)
        if (messages.isEmpty()) return null

        // Collect all annotations matching the type
        val instances = mutableListOf<Instance>()
        val companionCounts = mutableMapOf<String, Int>()

        for (msg in messages) {
            // Find matching annotations in both transient and persistent
            val matchingTransient = msg.annotations.filter { typeName in it.types }
            val matchingPersistent = msg.persistentAnnotations.filter { typeName in it.types }

            // Process transient matches
            for (ann in matchingTransient) {
                if (!matchesEffectFilter(ann, effectId)) continue
                val companions = findCompanions(msg, ann, isPersistent = false)
                instances.add(toInstance(msg.gsId, ann, isPersistent = false, companions))
                companions.forEach { companionCounts.merge(it, 1, Int::plus) }
            }

            // Process persistent matches
            for (ann in matchingPersistent) {
                if (!matchesEffectFilter(ann, effectId)) continue
                val companions = findCompanions(msg, ann, isPersistent = true)
                instances.add(toInstance(msg.gsId, ann, isPersistent = true, companions))
                companions.forEach { companionCounts.merge(it, 1, Int::plus) }
            }
        }

        if (instances.isEmpty()) return null

        // Analyze type arrays (multi-type co-occurrence)
        val typeArrays = instances.groupBy { it.types.sorted() }
            .mapValues { it.value.size }
            .toSortedMap(compareBy { it.joinToString(",") })

        // Analyze affectorId patterns
        val withAffector = instances.count { it.affectorId != 0 }
        val affectorPattern = when {
            withAffector == 0 -> "never set"
            withAffector == instances.size -> "always set"
            else -> "set on $withAffector/${instances.size} (${100 * withAffector / instances.size}%)"
        }

        // Analyze detail keys
        val keyProfiles = buildKeyProfiles(instances)

        // Analyze lifecycle
        val lifecycle = buildLifecycle(messages, typeName, effectId)

        return ContractResult(
            typeName = typeName,
            instances = instances,
            typeArrays = typeArrays,
            affectorIdPattern = affectorPattern,
            detailKeys = keyProfiles,
            companions = companionCounts,
            lifecycle = lifecycle,
        )
    }

    private fun matchesEffectFilter(ann: RecordingDecoder.AnnotationSummary, effectId: Int?): Boolean {
        if (effectId == null) return true
        // Check affectedIds for effect ID (LayeredEffectCreated uses affectedIds=[effectId])
        if (effectId in ann.affectedIds) return true
        // Check detail keys for effect_id
        val detailEffectId = ann.details["effect_id"]
        return when (detailEffectId) {
            is Number -> detailEffectId.toInt() == effectId
            is List<*> -> detailEffectId.any { (it as? Number)?.toInt() == effectId }
            else -> false
        }
    }

    private fun findCompanions(
        msg: RecordingDecoder.DecodedMessage,
        target: RecordingDecoder.AnnotationSummary,
        isPersistent: Boolean,
    ): List<String> {
        // Companion = other annotation in same GSM that shares an affectedId or has related effectId
        val targetIds = target.affectedIds.toSet()
        val targetAffector = target.affectorId

        val candidates = if (isPersistent) {
            // For persistent, look at transient annotations in same msg
            msg.annotations
        } else {
            // For transient, look at other transient + persistent annotations
            msg.annotations + msg.persistentAnnotations
        }

        return candidates
            .filter { it.id != target.id }
            .filter { candidate ->
                // Share an affectedId, or candidate's affectorId matches
                candidate.affectedIds.any { it in targetIds } ||
                    (targetAffector != 0 && candidate.affectorId == targetAffector) ||
                    candidate.affectedIds.any { it == targetAffector }
            }
            .flatMap { it.types }
            .distinct()
    }

    private fun toInstance(
        gsId: Int,
        ann: RecordingDecoder.AnnotationSummary,
        isPersistent: Boolean,
        companions: List<String>,
    ): Instance = Instance(
        gsId = gsId,
        annotationId = ann.id,
        types = ann.types,
        affectorId = ann.affectorId,
        affectedIds = ann.affectedIds,
        details = ann.details,
        isPersistent = isPersistent,
        companionTypes = companions,
    )

    private fun buildKeyProfiles(instances: List<Instance>): Map<String, KeyProfile> {
        val allKeys = instances.flatMap { it.details.keys }.distinct().sorted()
        val total = instances.size

        return allKeys.associateWith { key ->
            val withKey = instances.filter { key in it.details }
            val freq = if (withKey.size == total) "always" else "${withKey.size}/$total (${100 * withKey.size / total}%)"
            val samples = withKey.take(5).mapNotNull { it.details[key]?.toString() }.distinct()
            val valueType = withKey.firstOrNull()?.details?.get(key)?.let { v ->
                when (v) {
                    is Number -> "int"
                    is String -> "string"
                    is List<*> -> "list<${v.firstOrNull()?.javaClass?.simpleName ?: "?"}>"
                    else -> v.javaClass.simpleName
                }
            } ?: "?"

            KeyProfile(frequency = freq, sampleValues = samples, valueType = valueType)
        }
    }

    private fun buildLifecycle(
        messages: List<RecordingDecoder.DecodedMessage>,
        typeName: String,
        effectId: Int?,
    ): List<String> {
        val events = mutableListOf<String>()

        for (msg in messages) {
            // Check transient annotations
            for (ann in msg.annotations) {
                if (typeName in ann.types && matchesEffectFilter(ann, effectId)) {
                    events.add("gsId=${msg.gsId} TRANSIENT types=${ann.types} affectedIds=${ann.affectedIds}")
                }
                // Also check for related Created/Destroyed
                if (effectId != null) {
                    if ("${typeName}Created" in ann.types && effectId in ann.affectedIds) {
                        events.add("gsId=${msg.gsId} CREATED affectedIds=${ann.affectedIds}")
                    }
                    if ("${typeName}Destroyed" in ann.types && effectId in ann.affectedIds) {
                        events.add("gsId=${msg.gsId} DESTROYED affectedIds=${ann.affectedIds}")
                    }
                }
            }
            // Check persistent annotations
            for (ann in msg.persistentAnnotations) {
                if (typeName in ann.types && matchesEffectFilter(ann, effectId)) {
                    events.add("gsId=${msg.gsId} PERSISTENT id=${ann.id} types=${ann.types}")
                }
            }
            // Check deletions
            if (effectId != null) {
                // diffDeletedPersistentAnnotationIds removes PA by id
                // We can't directly map PA id → effectId here without tracking, but note deletions
            }
        }

        return events.distinct()
    }

    /** Format contract as readable markdown for terminal output. */
    fun format(result: ContractResult): String = buildString {
        appendLine("# Annotation Contract: ${result.typeName}")
        appendLine("${result.instances.size} instances found")
        appendLine()

        appendLine("## Type Arrays (multi-type co-occurrence)")
        for ((types, count) in result.typeArrays) {
            appendLine("  $types  ($count instances)")
        }
        appendLine()

        appendLine("## affectorId")
        appendLine("  ${result.affectorIdPattern}")
        appendLine()

        appendLine("## Detail Keys")
        if (result.detailKeys.isEmpty()) {
            appendLine("  (none)")
        } else {
            appendLine("  %-25s %-12s %-8s %s".format("key", "frequency", "type", "samples"))
            appendLine("  ${"-".repeat(70)}")
            for ((key, profile) in result.detailKeys) {
                appendLine(
                    "  %-25s %-12s %-8s %s".format(
                        key,
                        profile.frequency,
                        profile.valueType,
                        profile.sampleValues.joinToString(", "),
                    ),
                )
            }
        }
        appendLine()

        appendLine("## Companion Annotations (same GSM, related IDs)")
        if (result.companions.isEmpty()) {
            appendLine("  (none)")
        } else {
            for ((type, count) in result.companions.toSortedMap()) {
                appendLine("  $type  ($count co-occurrences)")
            }
        }
        appendLine()

        appendLine("## Lifecycle")
        for (event in result.lifecycle.take(20)) {
            appendLine("  $event")
        }
        if (result.lifecycle.size > 20) {
            appendLine("  ... and ${result.lifecycle.size - 20} more")
        }
        appendLine()

        // Show first 3 full instances as samples
        appendLine("## Sample Instances")
        for (inst in result.instances.take(3)) {
            appendLine("  gsId=${inst.gsId} id=${inst.annotationId} ${if (inst.isPersistent) "PERSISTENT" else "TRANSIENT"}")
            appendLine("    types: ${inst.types}")
            appendLine("    affectorId: ${inst.affectorId}")
            appendLine("    affectedIds: ${inst.affectedIds}")
            appendLine("    details: ${inst.details}")
            if (inst.companionTypes.isNotEmpty()) {
                appendLine("    companions: ${inst.companionTypes}")
            }
            appendLine()
        }
    }
}
