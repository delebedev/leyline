package leyline.tooling.simclient

internal data class ActionAttemptStats(
    val submittedByType: Map<String, Int>,
    val outcomes: Map<String, Int>,
    val noPendingByDecision: Map<String, Int>,
    val skippedAlreadyTried: Int,
) {
    companion object {
        val Empty = ActionAttemptStats(emptyMap(), emptyMap(), emptyMap(), 0)
    }
}

internal class ActionAttemptLedger(
    private val currentTurn: () -> Int?,
) {
    private var turn = 0
    private val attemptedThisTurn = mutableSetOf<String>()
    private val quarantinedThisTurn = mutableSetOf<String>()
    private val submittedByType = mutableMapOf<String, Int>()
    private val outcomes = mutableMapOf<String, Int>()
    private val noPendingByDecision = mutableMapOf<String, Int>()
    private var skippedAlreadyTried = 0
    private var lastSubmitted = emptySet<String>()

    fun skipFingerprints(): Set<String> {
        resetIfTurnChanged()
        return attemptedThisTurn + quarantinedThisTurn
    }

    fun noteSkippedAlreadyTried() {
        skippedAlreadyTried++
    }

    fun markSubmitted(
        fingerprint: String,
        kind: String,
    ) = markSubmitted(setOf(fingerprint), kind)

    fun markSubmitted(
        fingerprints: Set<String>,
        kind: String,
    ) {
        resetIfTurnChanged()
        attemptedThisTurn += fingerprints
        lastSubmitted = fingerprints
        submittedByType.merge(kind, 1) { a, b -> a + b }
    }

    fun markProgress() {
        lastSubmitted = emptySet()
        outcomes.merge("progress", 1) { a, b -> a + b }
    }

    fun markNoProgress() {
        resetIfTurnChanged()
        quarantinedThisTurn += lastSubmitted
        lastSubmitted = emptySet()
        outcomes.merge("no-progress", 1) { a, b -> a + b }
    }

    fun markNoPending(kind: String? = null) {
        outcomes.merge("no-pending", 1) { a, b -> a + b }
        if (kind != null) noPendingByDecision.merge(kind, 1) { a, b -> a + b }
    }

    fun stats(): ActionAttemptStats =
        ActionAttemptStats(
            submittedByType = submittedByType.toMap(),
            outcomes = outcomes.toMap(),
            noPendingByDecision = noPendingByDecision.toMap(),
            skippedAlreadyTried = skippedAlreadyTried,
        )

    private fun resetIfTurnChanged() {
        val current = currentTurn() ?: return
        if (current != turn) {
            turn = current
            attemptedThisTurn.clear()
            quarantinedThisTurn.clear()
            lastSubmitted = emptySet()
        }
    }
}
