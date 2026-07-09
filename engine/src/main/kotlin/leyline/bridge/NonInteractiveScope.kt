package leyline.bridge

/**
 * Thread-scoped answer policy for controller callbacks fired outside a real
 * prompt window.
 *
 * Forge consults the player controller from three contexts: a real decision at
 * priority, cost calculation ([forge.game.cost.CostAdjustment]), and
 * hypothetical evaluation (payability probes). Only the first may prompt.
 * Callers entering the other two wrap the computation in [quiet] or
 * [bestEffort]; [leyline.bridge.forge.PlayerController]'s payment callbacks
 * answer from the active policy instead of prompting, and
 * [leyline.bridge.handoff.InteractivePromptBridge.requestChoice] refuses any
 * callback that reaches it while a scope is active.
 *
 * Policies (see docs/decisions/0007-displayed-cost-and-controller-contexts.md):
 * - [Policy.QUIET] — every payment choice answers "nothing chosen". Consumer:
 *   displayed cost, where payment-time reductions must not apply.
 * - [Policy.BEST_EFFORT] — every payment choice answers with the maximum
 *   legal reduction, deterministically. Consumers: payability and
 *   hypothetical evaluation.
 *
 * The scope is per-thread: entry and exit happen on the thread running the
 * computation, so a session-thread cost build and an engine-thread decision
 * never see each other's policy. Nested scopes restore the enclosing policy
 * on exit, including on exception.
 */
object NonInteractiveScope {
    enum class Policy { QUIET, BEST_EFFORT }

    private val current = ThreadLocal<Policy?>()

    /** Active policy on the calling thread, or null in interactive context. */
    val active: Policy? get() = current.get()

    /** Run [block] with every payment choice answering "nothing chosen". */
    fun <T> quiet(block: () -> T): T = scoped(Policy.QUIET, block)

    /** Run [block] with every payment choice answering the maximum legal reduction. */
    fun <T> bestEffort(block: () -> T): T = scoped(Policy.BEST_EFFORT, block)

    private fun <T> scoped(
        policy: Policy,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(policy)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
