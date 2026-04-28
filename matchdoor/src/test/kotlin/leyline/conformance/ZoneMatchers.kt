package leyline.conformance

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
 * "Coral Merfolk" shouldNot beOnBattlefieldOf(ai)
 * "Forest" should beOnBattlefieldOf(human, count = 2)
 * "Treasure Token" should beInZoneOf(ZoneType.Battlefield, human)
 * ```
 *
 * Failure messages name the card, player, zone, and (when set) count so
 * assertSoftly reports are self-describing.
 */

fun beInZoneOf(
    zone: ZoneType,
    player: Player,
    count: Int? = null,
): Matcher<String> =
    Matcher { cardName ->
        val actual = player.getZone(zone).cards.count { it.name == cardName }
        val passed =
            if (count == null) {
                actual >= 1
            } else {
                actual == count
            }
        val expectation =
            if (count == null) {
                "'$cardName' should be in ${player.name}'s $zone"
            } else {
                "${player.name}'s $zone should contain $count copies of '$cardName' (found $actual)"
            }
        val negation =
            if (count == null) {
                "'$cardName' should not be in ${player.name}'s $zone"
            } else {
                "${player.name}'s $zone should not contain $count copies of '$cardName' (found $actual)"
            }
        MatcherResult(passed, { expectation }, { negation })
    }

fun beInHandOf(
    player: Player,
    count: Int? = null,
): Matcher<String> = beInZoneOf(ZoneType.Hand, player, count)

fun beOnBattlefieldOf(
    player: Player,
    count: Int? = null,
): Matcher<String> = beInZoneOf(ZoneType.Battlefield, player, count)

fun beInGraveyardOf(
    player: Player,
    count: Int? = null,
): Matcher<String> = beInZoneOf(ZoneType.Graveyard, player, count)

fun beInLibraryOf(
    player: Player,
    count: Int? = null,
): Matcher<String> = beInZoneOf(ZoneType.Library, player, count)

fun beInExileOf(
    player: Player,
    count: Int? = null,
): Matcher<String> = beInZoneOf(ZoneType.Exile, player, count)

fun beInCommandOf(
    player: Player,
    count: Int? = null,
): Matcher<String> = beInZoneOf(ZoneType.Command, player, count)
