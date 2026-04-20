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
 * ```
 *
 * Failure messages name the card, player, and zone so assertSoftly reports
 * are self-describing.
 */

private fun beInZoneOf(
    zone: ZoneType,
    player: Player,
): Matcher<String> =
    Matcher { cardName ->
        val found = player.getZone(zone).cards.any { it.name == cardName }
        MatcherResult(
            found,
            { "'$cardName' should be in ${player.name}'s $zone" },
            { "'$cardName' should not be in ${player.name}'s $zone" },
        )
    }

fun beInHandOf(player: Player): Matcher<String> = beInZoneOf(ZoneType.Hand, player)

fun beOnBattlefieldOf(player: Player): Matcher<String> = beInZoneOf(ZoneType.Battlefield, player)

fun beInGraveyardOf(player: Player): Matcher<String> = beInZoneOf(ZoneType.Graveyard, player)

fun beInLibraryOf(player: Player): Matcher<String> = beInZoneOf(ZoneType.Library, player)

fun beInExileOf(player: Player): Matcher<String> = beInZoneOf(ZoneType.Exile, player)
