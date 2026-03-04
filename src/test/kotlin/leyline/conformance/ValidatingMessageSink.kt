package leyline.conformance

import leyline.analysis.InvariantChecker
import leyline.infra.ListMessageSink
import leyline.infra.MessageSink
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * [MessageSink] decorator that wraps [ListMessageSink] and runs invariant checks
 * on every [send] call automatically.
 *
 * Delegates to [InvariantChecker] for all invariant logic — single source of truth
 * shared with [SessionAnalyzer] at runtime.
 *
 * Swap in for any test that uses [ListMessageSink] to get free invariant coverage
 * without changing assertions. Access captured messages via [inner].
 *
 * @param inner   the underlying [ListMessageSink] (delegates storage)
 * @param strict  when true, throws [AssertionError] on first violation;
 *                when false, logs violations into [violations] for later inspection
 */
class ValidatingMessageSink(
    val inner: ListMessageSink = ListMessageSink(),
    private val strict: Boolean = true,
) : MessageSink {

    private val checker = InvariantChecker()

    /** Accumulated violation descriptions (useful when [strict] = false). */
    val violations = mutableListOf<String>()

    // --- MessageSink ---

    override fun send(messages: List<GREToClientMessage>) {
        val beforeCount = checker.violations.size
        for (msg in messages) {
            checker.process(msg)
            // Check for new violations after each message
            while (checker.violations.size > beforeCount + violations.size) {
                val v = checker.violations[beforeCount + violations.size]
                record(v.message)
            }
        }
        inner.send(messages)
    }

    override fun sendRaw(msg: MatchServiceToClientMessage) {
        inner.sendRaw(msg)
    }

    // --- Public API ---

    /** Convenience: delegate to [inner] messages list. */
    val messages: MutableList<GREToClientMessage> get() = inner.messages
    val rawMessages: MutableList<MatchServiceToClientMessage> get() = inner.rawMessages

    /** Assert no violations accumulated (for use in @AfterMethod). */
    fun assertClean() {
        if (violations.isNotEmpty()) {
            throw AssertionError(
                "ValidatingMessageSink recorded ${violations.size} violation(s):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    fun clear() {
        inner.clear()
    }

    // --- Seed support ---

    /**
     * Seed the internal checker with a Full GSM (e.g. handshake baseline).
     * Must be called before processing thin Diffs so zone/object state is populated.
     */
    fun seedFull(gsm: GameStateMessage) {
        checker.seedFull(gsm)
    }

    private fun record(violation: String) {
        violations.add(violation)
        if (strict) {
            throw AssertionError("ValidatingMessageSink: $violation")
        }
    }
}
