package forge.nexus.game

import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks state needed to compute diffs between game state snapshots.
 *
 * Two responsibilities:
 * 1. **Zone tracking** — records which zone each instanceId was last seen in,
 *    so [StateMapper.detectAndApplyZoneTransfers] can detect zone changes.
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
}
