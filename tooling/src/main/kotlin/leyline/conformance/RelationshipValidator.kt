package leyline.conformance

import com.google.protobuf.Message
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo

/**
 * Validates [Relationship] patterns against observed segment instances.
 *
 * Each pattern is checked against every segment instance. Returns a
 * [ValidationResult] with hold count, exceptions, and provenance.
 */
object RelationshipValidator {

    private val PROTO_SUFFIX get() = ConformanceConstants.PROTO_SUFFIX

    fun validate(pattern: Relationship, segments: List<Segment>): ValidationResult {
        var holds = 0
        val exceptions = mutableListOf<ValidationException>()

        for (segment in segments) {
            val result = checkOne(pattern, segment)
            if (result == null) {
                holds++
            } else {
                exceptions.add(
                    ValidationException(segment.session, segment.gsId, segment.frameIndex, result),
                )
            }
        }
        return ValidationResult(pattern, holds, segments.size, exceptions)
    }

    fun validateAll(
        patterns: List<Relationship>,
        segments: List<Segment>,
    ): List<ValidationResult> {
        val grouped = segments.groupBy { it.category }
        return patterns.map { pattern ->
            val relevant = grouped[pattern.category] ?: emptyList()
            validate(pattern, relevant)
        }
    }

    /** Check one pattern against one segment. Returns null if holds, error detail if violated. */
    private fun checkOne(pattern: Relationship, segment: Segment): String? =
        when (pattern) {
            is Relationship.AlwaysPresent -> checkAlwaysPresent(pattern, segment)
            is Relationship.NonEmpty -> checkNonEmpty(pattern, segment)
            is Relationship.ValueIn -> checkValueIn(pattern, segment)
            is Relationship.Equals -> checkEquals(pattern, segment)
            is Relationship.AffectorIdRule -> checkAffectorIdRule(pattern, segment)
        }

    private fun checkAffectorIdRule(pattern: Relationship.AffectorIdRule, segment: Segment): String? {
        if (!segment.message.hasGameStateMessage()) return "no gameStateMessage"
        val gsm = segment.message.gameStateMessage
        val allAnnotations = gsm.annotationsList + gsm.persistentAnnotationsList

        for (ann in allAnnotations) {
            val matchesType = ann.typeList.any { it.name.replace(PROTO_SUFFIX, "") == pattern.annotationType }
            if (!matchesType) continue

            val isZero = ann.affectorId == 0
            return if (pattern.mustBeNonZero && isZero) {
                "affectorId=0 on ${pattern.annotationType} (expected non-zero)"
            } else if (!pattern.mustBeNonZero && !isZero) {
                "affectorId=${ann.affectorId} on ${pattern.annotationType} (expected 0)"
            } else {
                null // holds
            }
        }
        // Annotation type not present — not a violation of the affectorId rule
        // (AlwaysPresent is a separate check)
        return null
    }

    private fun checkAlwaysPresent(pattern: Relationship.AlwaysPresent, segment: Segment): String? {
        if (!segment.message.hasGameStateMessage()) return "no gameStateMessage"
        val gsm = segment.message.gameStateMessage
        val allAnnotations = gsm.annotationsList + gsm.persistentAnnotationsList
        val hasType =
            allAnnotations.any { ann ->
                ann.typeList.any { it.name.replace(PROTO_SUFFIX, "") == pattern.annotationType }
            }
        return if (hasType) null else "annotation type ${pattern.annotationType} not found"
    }

    private fun checkNonEmpty(pattern: Relationship.NonEmpty, segment: Segment): String? {
        val value = resolveFieldPath(segment.message, pattern.path)
        return when {
            value == null -> "path ${pattern.path} not found"
            value is List<*> && value.isEmpty() -> "list at ${pattern.path} is empty"
            value is List<*> -> null // non-empty list
            value is Message -> null // present message
            value is Number && value.toInt() == 0 -> "value at ${pattern.path} is 0"
            else -> null // present and non-default
        }
    }

    private fun checkValueIn(pattern: Relationship.ValueIn, segment: Segment): String? {
        // Handle annotation detail paths: annotations[TypeName].details.key
        val annotationMatch = Regex("annotations\\[(.+?)]\\.(.*)")
            .matchEntire(pattern.path)
        if (annotationMatch != null) {
            return checkAnnotationDetailValue(
                annotationMatch.groupValues[1],
                annotationMatch.groupValues[2],
                pattern.values,
                segment,
            )
        }

        val value = resolveFieldPath(segment.message, pattern.path)
        return when {
            value == null -> "path ${pattern.path} not found"
            value.toString() in pattern.values -> null
            else -> "value '$value' not in ${pattern.values}"
        }
    }

    private fun checkAnnotationDetailValue(
        annotationType: String,
        detailPath: String,
        expectedValues: Set<String>,
        segment: Segment,
    ): String? {
        if (!segment.message.hasGameStateMessage()) return "no gameStateMessage"
        val gsm = segment.message.gameStateMessage
        val allAnnotations = gsm.annotationsList + gsm.persistentAnnotationsList

        // Find annotation with matching type
        val ann =
            allAnnotations.firstOrNull { a ->
                a.typeList.any { it.name.replace(PROTO_SUFFIX, "") == annotationType }
            } ?: return "annotation type $annotationType not found"

        // Parse detail path: "details.key_name"
        val keyName = detailPath.removePrefix("details.")

        // Find the detail
        val detail =
            ann.detailsList.firstOrNull { it.key == keyName }
                ?: return "detail key '$keyName' not found in $annotationType"

        // Extract value
        val value = extractDetailValue(detail)
        return if (value in expectedValues) {
            null
        } else {
            "detail '$keyName' value '$value' not in $expectedValues"
        }
    }

    private fun extractDetailValue(detail: KeyValuePairInfo): String =
        when {
            detail.valueStringList.isNotEmpty() -> detail.valueStringList[0]
            detail.valueUint32List.isNotEmpty() -> detail.valueUint32List[0].toString()
            detail.valueInt32List.isNotEmpty() -> detail.valueInt32List[0].toString()
            else -> ""
        }

    private fun checkEquals(pattern: Relationship.Equals, segment: Segment): String? {
        val valA = resolveFieldPath(segment.message, pattern.a)
        val valB = resolveFieldPath(segment.message, pattern.b)
        return when {
            valA == null -> "path ${pattern.a} not found"
            valB == null -> "path ${pattern.b} not found"
            valA.toString() == valB.toString() -> null
            else -> "${pattern.a}='$valA' != ${pattern.b}='$valB'"
        }
    }

    /** Resolve a dot-separated field path on a proto message. Returns List for repeated fields. */
    private fun resolveFieldPath(msg: Message, path: String): Any? {
        val parts = path.split(".")
        var current: Any = msg

        for (part in parts) {
            when (current) {
                is Message -> {
                    val field = current.descriptorForType.findFieldByName(part) ?: return null
                    val value = current.getField(field)
                    // For repeated fields return the list directly
                    current = if (field.isRepeated) value else (value ?: return null)
                }
                is List<*> -> return current // can't descend further into a list
                else -> return null
            }
        }
        return current
    }
}
