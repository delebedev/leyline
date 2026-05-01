package leyline.game.state

import leyline.bridge.types.InstanceId

/**
 * Bundled result of a card moving to a new zone — the downstream effects of
 * one realloc, packaged so callers cannot accidentally do three of the four.
 * Replaces the inline `realloc → retire → zone-record → objectIdChanged`
 * idiom that recurred at multiple sites in [leyline.game.annotations.ZoneTransferDetector].
 *
 * Continuation of leyline-9d8: that bead lifted the realloc step's mutations
 * into [BridgeMutations] as data; this bead lifts the *call shape* — callers
 * receive one structured value instead of computing four separate ops at
 * each transfer site.
 *
 *  - [realloc] — old → new instanceId. Both equal when the transfer keeps
 *    the same iid (Resolve, no-op handoff).
 *  - [limboRetirement] — old iid to retire to Limbo. Null when the handoff
 *    is a no-op (realloc.old == realloc.new); the caller then skips the
 *    retire / OIC / zone-patch steps.
 *  - [zoneAssignment] — (new iid, destination zone id) pair the caller
 *    folds into [BridgeMutations.zoneRecordings].
 *
 * Built via the [Companion] factories — pure-pipeline callers use
 * [fromRealloc] (with an `idAllocator` lambda); a direct-bridge variant
 * isn't currently needed because the only realloc site
 * ([leyline.game.annotations.ZoneTransferDetector]) takes lambdas.
 */
data class ZoneHandoff(
    val realloc: InstanceIdRegistry.IdReallocation,
    val limboRetirement: InstanceId?,
    val zoneAssignment: Pair<InstanceId, Int>,
) {
    companion object {
        /**
         * No-op handoff for the keep-same-instanceId case (Resolve
         * Stack→Battlefield). Realloc collapses to old==new; no limbo
         * retire; zone-assignment field still applies because the caller
         * may want to track the new zone for the iid.
         */
        fun keepingSameInstanceId(
            instanceId: InstanceId,
            destinationZoneId: Int,
        ): ZoneHandoff =
            ZoneHandoff(
                realloc = InstanceIdRegistry.IdReallocation(instanceId, instanceId),
                limboRetirement = null,
                zoneAssignment = instanceId to destinationZoneId,
            )

        /**
         * Build a handoff from a fresh [InstanceIdRegistry.IdReallocation]
         * (the result of an `idAllocator(forgeCardId)` call).
         */
        fun fromRealloc(
            realloc: InstanceIdRegistry.IdReallocation,
            destinationZoneId: Int,
        ): ZoneHandoff {
            val didRealloc = realloc.old != realloc.new
            return ZoneHandoff(
                realloc = realloc,
                limboRetirement = if (didRealloc) realloc.old else null,
                zoneAssignment = realloc.new to destinationZoneId,
            )
        }
    }
}
