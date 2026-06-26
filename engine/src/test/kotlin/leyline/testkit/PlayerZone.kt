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
 *
 * Session-tier only — do not promote `Player.battlefield` / `iid` to a
 * top-level extension. The probe DSL only makes sense paired with a live
 * `MatchFlowHarness` that owns the bridge.
 */
data class PlayerZone(
    val player: Player,
    val zone: ZoneType,
)
