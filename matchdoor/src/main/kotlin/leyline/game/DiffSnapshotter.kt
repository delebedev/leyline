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
 * 2. **State snapshots** — stores the previous [GameStateMessage] so diff builders
 *    can compute what changed (life totals, combat damage, etc.).
 *
 * Thread-safe: zone map uses [ConcurrentHashMap], snapshot is volatile.
 */
class DiffSnapshotter(private val idRegistry: InstanceIdRegistry) {

    /** Previous zone assignment per instanceId — for detecting zone transfers. */
    private val previousZones = ConcurrentHashMap<Int, Int>()

    /** Previous full GameStateMessage — used to compute diffs. */
    @Volatile
    private var previousState: GameStateMessage? = null

    /** Record current zone for an instance. Returns previous zone or null if new. */
    fun recordZone(instanceId: Int, zoneId: Int): Int? =
        previousZones.put(instanceId, zoneId)

    /** Get the zone an instanceId was last seen in. */
    fun getPreviousZone(instanceId: Int): Int? = previousZones[instanceId]

    /** Read-only snapshot of all zone assignments (for debug panel). */
    fun allZones(): Map<Int, Int> = HashMap(previousZones)

    /** Store a full game state snapshot for future diff computation. */
    fun snapshotState(state: GameStateMessage) {
        previousState = state
    }

    /** Get the previous snapshot (null before first state). */
    fun getPreviousState(): GameStateMessage? = previousState

    /** Clear the previous snapshot (e.g. on game reset). */
    fun clear() {
        previousState = null
    }

    /** Full reset — clear all tracked state (zones, snapshot, turn info). Used on puzzle hot-swap. */
    fun resetAll() {
        previousZones.clear()
        previousState = null
        lastSentTurnInfo = null
    }

    // --- Last-sent TurnInfo tracking ---
    //
    // Separate from [previousState] (diff baseline). Tracks what the client
    // actually received, so BundleBuilder can detect skipped-phase transitions.

    /** TurnInfo from the most recent GSM sent to the client. */
    @Volatile
    private var lastSentTurnInfo: TurnInfo? = null

    /** Update from a GSM being sent to the client. Only overwrites if turnInfo is present. */
    fun updateLastSentTurnInfo(gsm: GameStateMessage) {
        if (gsm.hasTurnInfo()) {
            lastSentTurnInfo = gsm.turnInfo
        }
    }

    /** The TurnInfo the client last received (null before first state sent). */
    fun getLastSentTurnInfo(): TurnInfo? = lastSentTurnInfo

    /**
     * True if [currentTurnInfo] represents a phase/step change from the last
     * sent state. Also true when no prior state has been sent (first message).
     */
    fun isPhaseChangedFromLastSent(currentTurnInfo: TurnInfo): Boolean {
        val last = lastSentTurnInfo ?: return true
        return last.phase != currentTurnInfo.phase || last.step != currentTurnInfo.step
    }
}
