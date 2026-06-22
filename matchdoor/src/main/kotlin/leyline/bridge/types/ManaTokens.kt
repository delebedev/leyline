package leyline.bridge.types

import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Map a single mana symbol token (W/U/B/R/G/C/S/X or a positive integer) to a
 * (ManaColor, count) pair. Returns null for empty / unrecognized tokens.
 */
internal fun manaTokenToPair(token: String): Pair<ManaColor, Int>? {
    if (token.isEmpty()) return null
    return when (token.uppercase()) {
        "W" -> ManaColor.White_afc9 to 1
        "U" -> ManaColor.Blue_afc9 to 1
        "B" -> ManaColor.Black_afc9 to 1
        "R" -> ManaColor.Red_afc9 to 1
        "G" -> ManaColor.Green_afc9 to 1
        "C" -> ManaColor.Colorless_afc9 to 1
        "S", "SI" -> ManaColor.Snow_afc9 to 1
        "X" -> ManaColor.X to 1
        else -> token.toIntOrNull()?.takeIf { it > 0 }?.let { ManaColor.Generic to it }
    }
}
