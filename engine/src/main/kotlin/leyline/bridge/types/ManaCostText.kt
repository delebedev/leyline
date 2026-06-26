package leyline.bridge.types

import wotc.mtgo.gre.external.messaging.Messages.ManaColor

object ManaCostText {
    fun clientText(cost: List<Pair<ManaColor, Int>>): String =
        cost.joinToString(separator = "") { (color, count) ->
            when (color) {
                ManaColor.Generic -> "o$count"
                ManaColor.White_afc9 -> "oW".repeat(count)
                ManaColor.Blue_afc9 -> "oU".repeat(count)
                ManaColor.Black_afc9 -> "oB".repeat(count)
                ManaColor.Red_afc9 -> "oR".repeat(count)
                ManaColor.Green_afc9 -> "oG".repeat(count)
                else -> ""
            }
        }
}
