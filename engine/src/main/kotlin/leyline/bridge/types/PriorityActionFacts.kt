package leyline.bridge.types

/** Immutable facts the match loop needs to decide whether to grant priority. */
data class PriorityActionFacts(
    val hasLegalNonManaAction: Boolean,
)
