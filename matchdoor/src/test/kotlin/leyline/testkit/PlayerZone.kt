package leyline.testkit

import forge.game.player.Player
import forge.game.zone.ZoneType

/**
 * A `(player, zone)` probe handle reachable from `SessionTest`'s extension
 * properties on [Player] — `human.battlefield`, `ai.exile`, etc.
 *
 * The handle itself holds nothing but the pair; bridge access happens inside
 * `SessionTest.iid(name)`, which is a member-extension on [PlayerZone] so it
 * can only be called inside session-tier tests.
 */
data class PlayerZone(
    val player: Player,
    val zone: ZoneType,
)
