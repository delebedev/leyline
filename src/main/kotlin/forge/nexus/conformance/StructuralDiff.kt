package forge.nexus.conformance

/**
 * Compares two [StructuralFingerprint] sequences and reports divergences.
 *
 * Comparison is positional (message N in real vs message N in ours).
 * Checks: greMessageType, gsType, updateType, annotationTypes,
 * annotationCategories, fieldPresence, actionTypes, hasPrompt, promptId.
 */
object StructuralDiff {

    /** Action kinds that must appear in actual if golden has them (structural, not deck-dependent). */
    private val LOAD_BEARING_ACTION_KINDS = setOf("ActivateMana")

    data class Divergence(
        val messageIndex: Int,
        val field: String,
        val expected: String,
        val actual: String,
    )

    data class DiffResult(
        val matches: Boolean,
        val divergences: List<Divergence>,
        val lengthMismatch: Pair<Int, Int>? = null,
    ) {
        fun report(): String = buildString {
            if (lengthMismatch != null) {
                appendLine("Sequence length mismatch: expected=${lengthMismatch.first}, actual=${lengthMismatch.second}")
            }
            for (d in divergences) {
                appendLine("Message #${d.messageIndex}: ${d.field}")
                appendLine("  expected: ${d.expected}")
                appendLine("  actual:   ${d.actual}")
            }
            if (matches) appendLine("PASS: sequences match structurally")
        }
    }

    fun compare(
        expected: List<StructuralFingerprint>,
        actual: List<StructuralFingerprint>,
    ): DiffResult {
        val divergences = mutableListOf<Divergence>()
        var lengthMismatch: Pair<Int, Int>? = null

        if (expected.size != actual.size) {
            lengthMismatch = expected.size to actual.size
        }

        val count = minOf(expected.size, actual.size)
        for (i in 0 until count) {
            val e = expected[i]
            val a = actual[i]
            diff(i, "greMessageType", e.greMessageType, a.greMessageType, divergences)
            diff(i, "gsType", e.gsType.orEmpty(), a.gsType.orEmpty(), divergences)
            diff(i, "updateType", e.updateType.orEmpty(), a.updateType.orEmpty(), divergences)
            diff(i, "annotationTypes", e.annotationTypes.toString(), a.annotationTypes.toString(), divergences)
            diff(i, "annotationCategories", e.annotationCategories.toString(), a.annotationCategories.toString(), divergences)
            diff(i, "fieldPresence", e.fieldPresence.sorted().toString(), a.fieldPresence.sorted().toString(), divergences)
            diff(i, "actionTypes", e.actionTypes.toString(), a.actionTypes.toString(), divergences)
            diff(i, "hasPrompt", e.hasPrompt.toString(), a.hasPrompt.toString(), divergences)
            diff(i, "promptId", e.promptId.toString(), a.promptId.toString(), divergences)
        }

        return DiffResult(
            matches = divergences.isEmpty() && lengthMismatch == null,
            divergences = divergences,
            lengthMismatch = lengthMismatch,
        )
    }

    /**
     * Shape-only comparison: checks message types, gsType, updateType, annotations,
     * annotation categories, action type categories, and prompt fields.
     * Allows extra fieldPresence entries (actual ⊇ expected).
     * Action types: checks that all unique types in expected appear in actual
     * (exact counts are deck-dependent and ignored).
     */
    fun compareShape(
        expected: List<StructuralFingerprint>,
        actual: List<StructuralFingerprint>,
    ): DiffResult {
        val divergences = mutableListOf<Divergence>()
        var lengthMismatch: Pair<Int, Int>? = null

        if (expected.size != actual.size) {
            lengthMismatch = expected.size to actual.size
        }

        val count = minOf(expected.size, actual.size)
        for (i in 0 until count) {
            val e = expected[i]
            val a = actual[i]
            diff(i, "greMessageType", e.greMessageType, a.greMessageType, divergences)
            diff(i, "gsType", e.gsType.orEmpty(), a.gsType.orEmpty(), divergences)
            diff(i, "updateType", e.updateType.orEmpty(), a.updateType.orEmpty(), divergences)
            diff(i, "annotationTypes", e.annotationTypes.toString(), a.annotationTypes.toString(), divergences)
            diff(i, "annotationCategories", e.annotationCategories.toString(), a.annotationCategories.toString(), divergences)
            // fieldPresence: actual must contain all expected fields (extras OK)
            val missing = e.fieldPresence - a.fieldPresence
            if (missing.isNotEmpty()) {
                diff(i, "fieldPresence (missing)", missing.sorted().toString(), "[]", divergences)
            }
            diff(i, "hasPrompt", e.hasPrompt.toString(), a.hasPrompt.toString(), divergences)
            diff(i, "promptId", e.promptId.toString(), a.promptId.toString(), divergences)
        }

        // actionTypes: sequence-level check for load-bearing kinds.
        // If the golden has a kind anywhere, actual must also have it somewhere.
        // Per-message action sets are game-state-dependent and intentionally skipped.
        val expectedKinds = expected.flatMap { it.actionTypes }.toSet()
        val actualKinds = actual.flatMap { it.actionTypes }.toSet()
        for (kind in LOAD_BEARING_ACTION_KINDS) {
            if (kind in expectedKinds && kind !in actualKinds) {
                divergences.add(Divergence(-1, "actionTypes (missing $kind in sequence)", "present", "absent"))
            }
        }

        return DiffResult(
            matches = divergences.isEmpty() && lengthMismatch == null,
            divergences = divergences,
            lengthMismatch = lengthMismatch,
        )
    }

    private fun diff(i: Int, field: String, expected: String, actual: String, out: MutableList<Divergence>) {
        if (expected != actual) out.add(Divergence(i, field, expected, actual))
    }
}
