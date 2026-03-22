package leyline.conformance

import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/** A single observed protocol interaction from a recording. */
data class Segment(
    /** Mechanic category: "CastSpell", "PlayLand", or message type: "SearchReq" */
    val category: String,
    /** The GRE message (GSM Diff for mechanics, full message for standalone) */
    val message: GREToClientMessage,
    /** Provenance */
    val session: String,
    val frameIndex: Int,
    val gsId: Int,
)

/** Variance profile for a segment category, computed from N instances. */
data class SegmentProfile(
    val category: String,
    val instanceCount: Int,
    val sessionCount: Int,
    val confidence: Confidence,
    val fieldProfiles: Map<String, FieldProfile>,
    val annotationPresence: Map<String, AnnotationPresence>,
)

/** Per-field variance observed across instances. */
data class FieldProfile(
    /** Fraction of instances where this field is present (1.0 = always) */
    val frequency: Double,
    /** How the value varies across instances */
    val variance: ValueVariance,
    /** Up to 5 unique values observed */
    val samples: List<String>,
)

enum class ValueVariance {
    /** Same value in all instances */
    CONSTANT,

    /** Small set of distinct values (≤10) */
    ENUM,

    /** Always different, instance-ID-like */
    ID,

    /** Numeric, varies within a range */
    RANGED,

    /** List/repeated field with varying length */
    SIZED,
}

/** How much an annotation type appears in a segment category. */
data class AnnotationPresence(
    val frequency: Double,
    val instanceCount: Int,
    val coOccursWith: Set<String>,
)

enum class Confidence {
    /** < 3 instances — shown but not reliable */
    TENTATIVE,

    /** ≥ 3 instances across ≥ 2 sessions */
    OBSERVED,

    /** ≥ 10 instances across ≥ 3 sessions */
    CONFIDENT,
    ;

    companion object {
        fun from(
            instances: Int,
            sessions: Int,
        ): Confidence =
            when {
                instances >= 10 && sessions >= 3 -> CONFIDENT
                instances >= 3 && sessions >= 2 -> OBSERVED
                else -> TENTATIVE
            }
    }
}
