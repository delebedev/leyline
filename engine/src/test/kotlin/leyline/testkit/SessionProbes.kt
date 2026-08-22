package leyline.testkit

/**
 * Session-tier probes over the semantic headless match.
 */

/**
 * Snapshot the message stream, run [block], and return the slice of messages
 * it produced. Collapses `messageSnapshot()` / `messagesSince(snap)` into one
 * line and lets typed prompt expectations replace raw `any { hasFooReq() }`
 * scans. Raw access remains via [MessageSlice.messages].
 */
internal fun leyline.tooling.headless.HeadlessMatch.after(block: () -> Unit): MessageSlice {
    val snapshot = checkpoint()
    block()
    return MessageSlice(messagesSince(snapshot))
}

/** Assert the client accumulator's projected state is self-consistent. */
internal fun leyline.tooling.headless.HeadlessMatch.assertAccumulatorConsistent(context: String) =
    check(observe().client.actionInstanceIdsMissingFromObjects().isEmpty()) { "inconsistent client state: $context" }
