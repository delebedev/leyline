package leyline.testkit

import forge.game.player.Player
import forge.game.zone.ZoneType
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult

fun beInZoneOf(
    zone: ZoneType,
    player: HeadlessSeat,
    count: Int = 1,
): Matcher<String> =
    Matcher { cardName ->
        val actual = player.cards(zone).count { it.name == cardName }
        MatcherResult(
            passed = actual == count,
            { "seat ${player.seat}'s $zone should contain exactly $count copy(ies) of '$cardName' (found $actual)" },
            { "seat ${player.seat}'s $zone should not contain exactly $count copy(ies) of '$cardName' (found $actual)" },
        )
    }

fun beInHandOf(
    player: HeadlessSeat,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Hand, player, count)

fun beOnBattlefieldOf(
    player: HeadlessSeat,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Battlefield, player, count)

fun beInGraveyardOf(
    player: HeadlessSeat,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Graveyard, player, count)

fun beInLibraryOf(
    player: HeadlessSeat,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Library, player, count)

fun beInExileOf(
    player: HeadlessSeat,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Exile, player, count)

fun beInCommandOf(
    player: HeadlessSeat,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Command, player, count)

fun haveOnTop(cardName: String): Matcher<HeadlessZone> =
    Matcher { zone ->
        val actual = zone.cards.firstOrNull()?.name
        MatcherResult(
            actual == cardName,
            { "zone should have '$cardName' on top (was '$actual')" },
            { "zone should not have '$cardName' on top" },
        )
    }

/**
 * MTG-flavored zone-membership matchers. Subject is the card name (String);
 * matcher describes the expected zone + owner in rules-text phrasing.
 *
 * ```
 * "Grizzly Bears" should beInHandOf(human)
 * "Forest" should beOnBattlefieldOf(human, count = 2)
 * "Treasure Token" should beInZoneOf(ZoneType.Battlefield, human, count = 3)
 * ```
 *
 * `count` defaults to 1 and means **exactly N** copies. Failure messages name
 * the card, player, zone, and count so assertSoftly reports are self-describing.
 * For "X must not be in zone at all" use `should beMissingFrom(zone, player)`.
 */

fun beInZoneOf(
    zone: ZoneType,
    player: Player,
    count: Int = 1,
): Matcher<String> =
    Matcher { cardName ->
        val actual = player.getZone(zone).cards.count { it.name == cardName }
        val passed = actual == count
        val expectation = "${player.name}'s $zone should contain exactly $count copy(ies) of '$cardName' (found $actual)"
        val negation = "${player.name}'s $zone should not contain exactly $count copy(ies) of '$cardName' (found $actual)"
        MatcherResult(passed, { expectation }, { negation })
    }

fun beInHandOf(
    player: Player,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Hand, player, count)

fun beOnBattlefieldOf(
    player: Player,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Battlefield, player, count)

fun beInGraveyardOf(
    player: Player,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Graveyard, player, count)

fun beInLibraryOf(
    player: Player,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Library, player, count)

fun beInExileOf(
    player: Player,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Exile, player, count)

fun beInCommandOf(
    player: Player,
    count: Int = 1,
): Matcher<String> = beInZoneOf(ZoneType.Command, player, count)

/** Assert that a card does NOT appear in a zone (count = 0). */
fun beMissingFrom(
    zone: ZoneType,
    player: Player,
): Matcher<String> =
    Matcher { cardName ->
        val actual = player.getZone(zone).cards.count { it.name == cardName }
        val passed = actual == 0
        val expectation = "${player.name}'s $zone should not contain '$cardName' (found $actual)"
        val negation = "${player.name}'s $zone should contain '$cardName' but didn't"
        MatcherResult(passed, { expectation }, { negation })
    }

/**
 * Zone membership as a plain predicate, for wait conditions such as
 * `passUntil { human.hasCard("Zurgo Bellstriker", ZoneType.Hand) }`.
 *
 * Assertions should use the matchers above instead — they name the card,
 * player and zone on failure, where a bare boolean reports only `false`.
 */
fun Player.hasCard(
    name: String,
    zone: ZoneType,
): Boolean = getZone(zone).cards.any { it.name == name }
