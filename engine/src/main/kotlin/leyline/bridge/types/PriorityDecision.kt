package leyline.bridge.types

/**
 * Why priority was skipped without client input.
 *
 * Rendered in structured priority-decision log entries (for example, `Skip(SmartPhaseSkip)`).
 */
sealed class AutoPassReason {
    data object EndTurnFlag : AutoPassReason() {
        override fun toString() = "EndTurnFlag"
    }

    data object SmartPhaseSkip : AutoPassReason() {
        override fun toString() = "SmartPhaseSkip"
    }

    data object AutoPassCancelled : AutoPassReason() {
        override fun toString() = "AutoPassCancelled"
    }

    class PhaseNotStopped(
        val phase: String,
    ) : AutoPassReason() {
        override fun toString() = "PhaseNotStopped($phase)"
    }
}

/** Result of evaluating whether to grant priority. */
sealed class PriorityDecision {
    class Skip(
        val reason: AutoPassReason,
    ) : PriorityDecision() {
        override fun toString() = "Skip($reason)"
    }
}
