package leyline.tooling.headless

import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
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
 * Find a card by name in [player]'s [zone], naming what the zone did hold when
 * the lookup fails. Single implementation behind the `card` members, the
 * [Player.card] extension, and [iidVia].
 */
fun cardIn(
    player: Player,
    zone: ZoneType,
    cardName: String,
): Card {
    val cards = player.getZone(zone).cards
    return cards.firstOrNull { it.name == cardName }
        ?: error("No '$cardName' in ${player.name}'s $zone. Present: ${cards.map { it.name }}")
}

/**
 * Zone-qualified card lookup for call sites that hold a [Player] but no probe
 * handle — unit-tier tests driving a bare Forge `Game`. Harness-tier tests
 * should prefer the probe DSL (`human.battlefield.card("Grizzly Bears")`).
 */
fun Player.card(
    name: String,
    zone: ZoneType,
): Card = cardIn(this, zone, name)

/**
 * Resolve a card by name within this (player, zone) handle to its proto
 * instanceId through [bridge]. Single implementation behind the `iid` members.
 */
fun PlayerZone.iidVia(
    bridge: GameBridge,
    cardName: String,
): Int = bridge.instanceId(cardIn(player, zone, cardName))
