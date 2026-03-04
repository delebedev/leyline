package leyline.bridge

/**
 * Why priority was skipped without client input.
 *
 * Visible in `/api/priority-log` as the `decision` field (e.g. `Skip(SmartPhaseSkip)`).
 */
sealed class AutoPassReason {
    data object EndTurnFlag : AutoPassReason() {
        override fun toString() = "EndTurnFlag"
    }
    data object SmartPhaseSkip : AutoPassReason() {
        override fun toString() = "SmartPhaseSkip"
    }
    data object OnlyPassActions : AutoPassReason() {
        override fun toString() = "OnlyPassActions"
    }
    data object ClientAutoPass : AutoPassReason() {
        override fun toString() = "ClientAutoPass"
    }

    data object AutoPassCancelled : AutoPassReason() {
        override fun toString() = "AutoPassCancelled"
    }

    class PhaseNotStopped(val phase: String) : AutoPassReason() {
        override fun toString() = "PhaseNotStopped($phase)"
    }
}

/** Result of evaluating whether to grant priority. */
sealed class PriorityDecision {
    class Grant(val phase: String, val actionCount: Int) : PriorityDecision() {
        override fun toString() = "Grant($phase,$actionCount)"
    }

    class Skip(val reason: AutoPassReason) : PriorityDecision() {
        override fun toString() = "Skip($reason)"
    }
}
