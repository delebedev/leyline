package leyline.conformance

/** A structural invariant that should hold across all instances of a segment category. */
sealed class Relationship(open val category: String) {

    /** Field A == Field B within the same segment (after ID normalization). */
    data class Equals(
        override val category: String,
        val a: String,
        val b: String,
    ) : Relationship(category)

    /** Field or list is present and non-empty. */
    data class NonEmpty(
        override val category: String,
        val path: String,
    ) : Relationship(category)

    /** Field value is one of an expected set. */
    data class ValueIn(
        override val category: String,
        val path: String,
        val values: Set<String>,
    ) : Relationship(category)

    /** Annotation type appears in all instances of this category. */
    data class AlwaysPresent(
        override val category: String,
        val annotationType: String,
    ) : Relationship(category)
}

data class ValidationResult(
    val pattern: Relationship,
    val holds: Int,
    val total: Int,
    val exceptions: List<ValidationException>,
) {
    val confidence
        get() = if (total == 0) 0.0 else holds.toDouble() / total

    val status
        get() =
            when {
                total == 0 -> ValidationStatus.NO_DATA
                holds == total -> ValidationStatus.HOLDS
                confidence >= 0.95 -> ValidationStatus.MOSTLY_HOLDS
                else -> ValidationStatus.VIOLATED
            }
}

data class ValidationException(
    val session: String,
    val gsId: Int,
    val frameIndex: Int,
    val detail: String,
)

enum class ValidationStatus {
    HOLDS,
    MOSTLY_HOLDS,
    VIOLATED,
    NO_DATA,
    UNRESOLVABLE,
}
