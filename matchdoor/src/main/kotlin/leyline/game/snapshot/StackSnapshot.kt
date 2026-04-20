package leyline.game.snapshot

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

data class StackSnapshot(
    val entries: List<StackEntry>,
)

/**
 * Immutable capture of a single stack item for the [addStackAbilitiesFromSnapshot] path.
 *
 * [grpId] is resolved during capture (saga chapter lookup + card name lookup) so the
 * mapper never needs a live Forge reference.  A value of 0 means the grpId could not
 * be resolved; [leyline.game.state.GameBridge.FALLBACK_GRPID] is applied at render time.
 */
data class StackEntry(
    /** Forge card ID of the source card (host of the ability). */
    val forgeCardId: ForgeCardId,
    /** Seat that controls / activated the ability. */
    val controller: SeatId,
    /** Owner of the source card (used to set ownerSeatId on the ability object). */
    val owner: SeatId,
    /**
     * Resolved grpId for the ability object.  0 when resolution failed; callers
     * fall back to [leyline.game.state.GameBridge.FALLBACK_GRPID].
     */
    val grpId: Int,
    /** Card targets chosen for this stack item (may be empty). */
    val targets: List<ForgeCardId>,
)
