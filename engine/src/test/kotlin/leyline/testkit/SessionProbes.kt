package leyline.testkit

/**
 * Session-tier probes that depend on test-only types and so cannot live on
 * [MatchFlowHarness] itself. Everything that needs only harness state belongs
 * on the harness; keep this file to the testkit boundary crossings.
 */

/**
 * Snapshot the message stream, run [block], and return the slice of messages
 * it produced. Collapses `messageSnapshot()` / `messagesSince(snap)` into one
 * line and lets typed prompt expectations replace raw `any { hasFooReq() }`
 * scans. Raw access remains via [MessageSlice.messages].
 */
internal fun MatchFlowHarness.after(block: () -> Unit): MessageSlice {
    val snapshot = messageSnapshot()
    block()
    return MessageSlice(messagesSince(snapshot))
}

/** Assert the client accumulator's projected state is self-consistent. */
internal fun MatchFlowHarness.assertAccumulatorConsistent(context: String) = accumulator.assertConsistent(context)
