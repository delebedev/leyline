package leyline.match

/** Observation only: phase markers never acquire engine locks or control progression. */
enum class MatchReceivePhase { Idle, Decoding, LockWait, ActionProcessing, HorizonWait, CoordinatorWait, OutputDelivery, Companion }

data class MatchReceiveDiagnostic(
    val phase: MatchReceivePhase,
    val elapsedMs: Long,
)

/** The latest entrant owns its record; older concurrent receives cannot overwrite it. */
internal class MatchReceiveProbe {
    private class Entry {
        @Volatile var state = MatchReceivePhase.Idle to System.nanoTime()
    }

    @Volatile private var latest = Entry()

    fun snapshot(): MatchReceiveDiagnostic {
        val (phase, since) = latest.state
        return MatchReceiveDiagnostic(phase, ((System.nanoTime() - since) / 1_000_000).coerceAtLeast(0))
    }

    fun <T> observe(block: () -> T): T {
        val previous = current.get()
        val entry = Entry()
        latest = entry
        current.set(entry)
        try {
            return inPhase(MatchReceivePhase.Decoding, block)
        } finally {
            current.set(previous)
        }
    }

    companion object {
        private val current = ThreadLocal<Entry?>()

        fun <T> inPhase(
            phase: MatchReceivePhase,
            block: () -> T,
        ): T {
            val entry = current.get() ?: return block()
            val previous = entry.state.first
            entry.state = phase to System.nanoTime()
            try {
                return block()
            } finally {
                entry.state = previous to System.nanoTime()
            }
        }
    }
}
