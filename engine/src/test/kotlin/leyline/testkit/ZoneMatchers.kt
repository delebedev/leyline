package leyline.testkit

import forge.game.player.Player
import forge.game.zone.ZoneType
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult

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

/** Assert that a card is on top of a zone (first in the list). */
fun haveOnTop(cardName: String): Matcher<PlayerZone> =
    Matcher { zone ->
        val cards = zone.player.getZone(zone.zone).cards
        val actual = cards.firstOrNull()?.name
        val passed = actual == cardName
        val present = cards.map { it.name }
        val expectation =
            "${zone.player.name}'s ${zone.zone.name} top should be '$cardName' but was '$actual'\n" +
                "${zone.zone.name} (${cards.size}): $present"
        val negation = "${zone.player.name}'s ${zone.zone.name} should not have '$cardName' on top (but does)"
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
