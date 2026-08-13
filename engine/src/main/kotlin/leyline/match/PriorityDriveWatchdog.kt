package leyline.match

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Safety net for the priority-drive handoff between the Forge game-loop thread
 * and the session thread that answers prompts.
 *
 * The drive ([AutoPassEngine.autoPassAndAdvance], which sends `ActionsAvailableReq`
 * or auto-passes) is invoked per inbound client message. But the game-loop can
 * create a seat priority window ([leyline.bridge.handoff.GameActionBridge.awaitAction])
 * that does *not* correspond to a client response — e.g. the opponent's turn
 * advances to another of our priority stops after the previous drive already
 * returned. `awaitAction` fires the priority signal, but if no drive is in flight
 * to consume it the pending action is orphaned: never prompted, never auto-passed,
 * and the game-loop parks in `awaitAction` until its timeout. On the live push
 * path this manifests as an intermittent mid-turn freeze.
 *
 * A pending action carries [leyline.bridge.handoff.GameActionBridge.PendingAction.actionCatalog],
 * which is null until the drive prompts it. So an orphan is exactly: a pending
 * action that stays *un-prompted* (catalog null) across several checks while no
 * response-bearing prompt is outstanding. Specialized combat and selection
 * prompts do not bind an action catalog, so their prompt horizon is the second
 * half of the orphan predicate.
 *
 * [tick] holds all the decision logic and is pure/deterministic for testing; the
 * thread wrapper just calls it on an interval.
 */
class PriorityDriveWatchdog(
    private val probe: () -> Probe?,
    private val redrive: () -> Unit,
    private val staleChecks: Int = 2,
    private val log: Logger = LoggerFactory.getLogger(PriorityDriveWatchdog::class.java),
) {
    /** A snapshot of the seat's pending action: its id and whether it's been prompted. */
    data class Probe(
        val actionId: String,
        val prompted: Boolean,
        val outstandingPrompt: Boolean = false,
    )

    private var lastUnpromptedId: String? = null
    private var staleCount = 0

    /**
     * Evaluate the current pending state once. Re-drives (and returns true) when
     * the same un-prompted pending action has been observed [staleChecks] times
     * in a row — the orphan signature. Resets on no pending, a prompted window,
     * or a different action id.
     */
    fun tick(): Boolean {
        val p = probe()
        if (p == null || p.prompted || p.outstandingPrompt) {
            lastUnpromptedId = null
            staleCount = 0
            return false
        }
        if (p.actionId == lastUnpromptedId) {
            staleCount++
        } else {
            lastUnpromptedId = p.actionId
            staleCount = 1
        }
        if (staleCount >= staleChecks) {
            log.warn(
                "priority-drive watchdog: re-driving orphaned un-prompted pending action {} (stale {} checks)",
                p.actionId.take(8),
                staleCount,
            )
            staleCount = 0
            lastUnpromptedId = null
            redrive()
            return true
        }
        return false
    }
}
