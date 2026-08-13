package leyline.game.state

import leyline.bridge.types.InstanceId

/**
 * Bundled result of a card moving to a new zone — the downstream effects of
 * one realloc (id swap, limbo retire, new-zone assignment) packaged so a
 * caller can't accidentally do two of the three. Reads as one structured
 * value at each transfer site instead of three separate writes plus an
 * `if (origId != newId)` guard around each.
 *
 *  - [realloc] — old → new instanceId. Both equal when the transfer keeps
 *    the same iid (Resolve, no-op handoff).
 *  - [limboRetirement] — old iid to retire to Limbo. Null when the handoff
 *    is a no-op (realloc.old == realloc.new); the caller then skips the
 *    retire / OIC / zone-patch steps.
 *  - [zoneAssignment] — (new iid, destination zone id) pair the caller
 *    folds into the next [ProjectionState].
 *
 * Built via the [Companion] factories. Pure-pipeline callers use
 * [fromRealloc] with an `idAllocator` lambda; a direct-bridge entry point
 * is omitted because the only realloc site
 * ([leyline.game.annotations.ZoneTransferDetector]) is itself
 * lambda-driven. Add one if a second realloc site appears.
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
