package leyline.testkit

import leyline.game.bundle.InvariantChecker
import leyline.game.bundle.InvariantSelection
import leyline.infra.ListMessageSink
import leyline.infra.MessageSink
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * [MessageSink] decorator that wraps [ListMessageSink] and runs invariant checks
 * on every [send] call automatically.
 *
 * Delegates to [InvariantChecker] for all invariant logic.
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
    selection: InvariantSelection = InvariantSelection.protocolFacts(),
) : MessageSink {
    private val checker = InvariantChecker(selection)

    /** Accumulated violation descriptions (useful when [strict] = false). */
    val violations = mutableListOf<String>()
    val violationsByCheck = mutableMapOf<String, Int>()

    // --- MessageSink ---

    override fun send(messages: List<GREToClientMessage>) {
        val beforeCount = checker.violations.size
        for (msg in messages) {
            checker.process(msg)
            // Check for new violations after each message
            while (checker.violations.size > beforeCount + violations.size) {
                val v = checker.violations[beforeCount + violations.size]
                record(v.gsId, v.check, v.message)
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

    private fun record(
        gsId: Int,
        check: String,
        violation: String,
    ) {
        violations.add("gsId=$gsId $violation")
        violationsByCheck.merge(check, 1) { a, b -> a + b }
        if (strict) {
            throw AssertionError("ValidatingMessageSink: $violation")
        }
    }
}
