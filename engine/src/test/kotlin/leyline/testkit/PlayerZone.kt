package leyline.testkit

import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.game.state.GameBridge

/**
 * A `(player, zone)` probe handle reachable from the spec bases' extension
 * properties on [Player] — `human.battlefield`, `ai.exile`, etc.
 *
 * The handle itself holds nothing but the pair; bridge access happens inside
 * each base's `iid(name)` member-extension ([SessionTest] resolves via the
 * live harness bridge, [BoardTest] via the current board's bridge). Both
 * delegate to [iidVia] so name→instanceId resolution has one implementation.
 *
 * Keep `Player.battlefield` / `iid` as spec-base members, not top-level
 * extensions — the DSL only makes sense with a bridge in scope.
 */
data class PlayerZone(
    val player: Player,
    val zone: ZoneType,
)

/**
 * Resolve a card by name within this (player, zone) handle to its proto
 * instanceId through [bridge]. Single implementation behind the spec bases'
 * `iid` members.
 */
internal fun PlayerZone.iidVia(
    bridge: GameBridge,
    cardName: String,
): Int {
    val card =
        player.getZone(zone).cards.firstOrNull { it.name == cardName }
            ?: error("Card '$cardName' not found in ${player.name} $zone")
    return bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
}
