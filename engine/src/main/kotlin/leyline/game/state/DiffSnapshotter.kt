package leyline.game.state

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks zone membership needed to detect zone transfers between game state snapshots.
 *
 * **Zone tracking** — records which zone each instanceId was last seen in,
 * so [leyline.game.mapping.StateMapper.detectZoneTransfers] can detect zone changes.
 *
 * Note: the diff-baseline GSM is [leyline.game.bundle.BundleCursor.lastSent] (`GsmSnapshot?`) on
 * [GameBridge.bundleCursor], not here. This class only tracks per-instance
 * zone history, used by [leyline.game.annotations.ZoneTransferDetector] to identify transfers.
 *
 * Thread-safe: zone map uses [ConcurrentHashMap].
 */
class DiffSnapshotter {
    /** Previous zone assignment per instanceId — for detecting zone transfers. */
    private val previousZones = ConcurrentHashMap<Int, Int>()

    /** Record current zone for an instance. Returns previous zone or null if new. */
    fun recordZone(
        instanceId: Int,
        zoneId: Int,
    ): Int? = previousZones.put(instanceId, zoneId)

    /** Get the zone an instanceId was last seen in. */
    fun getPreviousZone(instanceId: Int): Int? = previousZones[instanceId]

    /** Read-only snapshot of all zone assignments (for debug panel). */
    fun allZones(): Map<Int, Int> = HashMap(previousZones)

    /** Full reset — clear all tracked state (zones). Used on puzzle hot-swap. */
    fun resetAll() {
        previousZones.clear()
    }
}
