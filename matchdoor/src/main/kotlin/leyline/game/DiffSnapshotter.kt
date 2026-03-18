package leyline.game

import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.TurnInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks state needed to compute diffs between game state snapshots.
 *
 * Two responsibilities:
 * 1. **Zone tracking** — records which zone each instanceId was last seen in,
 *    so [StateMapper.detectZoneTransfers] can detect zone changes.
 * 2. **Diff baseline** — stores the last [GameStateMessage] used as the diff baseline
 *    so builders can compute what changed (life totals, combat damage, etc.).
 * 3. **Client-seen turn info** — tracks the last [TurnInfo] actually sent to the
 *    client so phase annotations aren't derived from the wrong baseline.
 *
 * Thread-safe: zone map uses [ConcurrentHashMap], snapshot is volatile.
 */
class DiffSnapshotter(private val idRegistry: InstanceIdRegistry) {

    /** Previous zone assignment per instanceId — for detecting zone transfers. */
    private val previousZones = ConcurrentHashMap<Int, Int>()

    /** Full GameStateMessage used as the current diff baseline. */
    @Volatile
    private var diffBaselineState: GameStateMessage? = null

    /** Record current zone for an instance. Returns previous zone or null if new. */
    fun recordZone(instanceId: Int, zoneId: Int): Int? =
        previousZones.put(instanceId, zoneId)

    /** Get the zone an instanceId was last seen in. */
    fun getPreviousZone(instanceId: Int): Int? = previousZones[instanceId]

    /** Read-only snapshot of all zone assignments (for debug panel). */
    fun allZones(): Map<Int, Int> = HashMap(previousZones)

    /** Store a full game state snapshot for future diff computation. */
    fun snapshotDiffBaseline(state: GameStateMessage) {
        diffBaselineState = state
    }

    /** Get the current diff baseline (null before first state). */
    fun getDiffBaselineState(): GameStateMessage? = diffBaselineState

    /** Full reset — clear all tracked state (zones, diff baseline, turn info). Used on puzzle hot-swap. */
    fun resetAll() {
        previousZones.clear()
        diffBaselineState = null
        clientSeenTurnInfo = null
    }

    // --- Client-seen TurnInfo tracking ---
    //
    // Separate from [diffBaselineState]. Tracks what the client
    // actually received, so BundleBuilder can detect skipped-phase transitions.

    /** TurnInfo from the most recent GSM sent to the client. */
    @Volatile
    private var clientSeenTurnInfo: TurnInfo? = null

    /** Update from a GSM being sent to the client. Only overwrites if turnInfo is present. */
    fun recordClientSeenTurnInfo(gsm: GameStateMessage) {
        if (gsm.hasTurnInfo()) {
            clientSeenTurnInfo = gsm.turnInfo
        }
    }

    /** The TurnInfo the client last received (null before first state sent). */
    fun getClientSeenTurnInfo(): TurnInfo? = clientSeenTurnInfo

    /**
     * True if [currentTurnInfo] represents a phase/step change from the last
     * sent state. Also true when no prior state has been sent (first message).
     */
    fun isPhaseChangedFromClientSeen(currentTurnInfo: TurnInfo): Boolean {
        val last = clientSeenTurnInfo ?: return true
        return last.phase != currentTurnInfo.phase || last.step != currentTurnInfo.step
    }
}
