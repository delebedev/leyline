package forge.nexus.game

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

    // --- Last-sent TurnInfo tracking ---
    //
    // Tracks the TurnInfo from the last GSM actually sent to the client.
    // Independent from [previousState] (used for diff computation).
    //
    // Why separate: drainPlayback() snapshots the current engine state for diff
    // computation, but if the engine skipped phases (via PhaseStopProfile), the
    // snapshot phase may match the current phase — hiding a transition the client
    // hasn't seen. lastSentTurnInfo reflects what the client knows, so
    // BundleBuilder.postAction() can reliably detect phase changes.

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
    fun isPhaseChangedFrom(currentTurnInfo: TurnInfo): Boolean {
        val last = lastSentTurnInfo ?: return true
        return last.phase != currentTurnInfo.phase || last.step != currentTurnInfo.step
    }
}
