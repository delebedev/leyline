package leyline.tooling.headless

import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.game.state.GameBridge

/**
 * A `(player, zone)` probe handle reachable from the probe DSL's extension
 * properties on [Player] — `human.battlefield`, `ai.exile`, etc.
 *
 * The handle itself holds nothing but the pair; bridge access happens inside
 * each `iid(name)` member-extension ([MatchFlowHarness] resolves via the live
 * harness bridge, `BoardTest` via the current board's bridge). Both delegate
 * to [iidVia] so name→instanceId resolution has one implementation.
 *
 * Keep `Player.battlefield` / `iid` as members of whatever owns a bridge, not
 * top-level extensions — the DSL only makes sense with a bridge in scope.
 */
data class PlayerZone(
    val player: Player,
    val zone: ZoneType,
)

/**
 * Resolve a card by name within this (player, zone) handle to its proto
 * instanceId through [bridge]. Single implementation behind the `iid` members.
 */
fun PlayerZone.iidVia(
    bridge: GameBridge,
    cardName: String,
): Int {
    val card =
        player.getZone(zone).cards.firstOrNull { it.name == cardName }
            ?: error("Card '$cardName' not found in ${player.name} $zone")
    return bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
}
