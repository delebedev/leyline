package leyline.game

import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks state needed to compute diffs between game state snapshots.
 *
 * Two responsibilities:
 * 1. **Zone tracking** — records which zone each instanceId was last seen in,
 *    so [StateMapper.detectZoneTransfers] can detect zone changes.
 * 2. **Diff baseline** — stores the last [GameStateMessage] used as the diff baseline
 *    so builders can compute what changed (life totals, combat damage, etc.).
 *
 * Thread-safe: zone map uses [ConcurrentHashMap], snapshot is volatile.
 */
class DiffSnapshotter(@Suppress("UnusedPrivateProperty") private val idRegistry: InstanceIdRegistry) {

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

    /** Clear diff baseline — next buildDiffFromGame produces a Full GSM. */
    fun clearBaseline() {
        diffBaselineState = null
    }

    /** Full reset — clear all tracked state (zones, diff baseline). Used on puzzle hot-swap. */
    fun resetAll() {
        previousZones.clear()
        diffBaselineState = null
    }
}
