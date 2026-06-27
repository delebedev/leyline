package leyline.bridge.types

import forge.card.MagicColor
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

object WubrgColorMapping {
    private data class Entry(
        val names: Set<String>,
        val mask: Byte,
        val manaColor: ManaColor,
        val staticId: Int,
    )

    private val entries =
        listOf(
            Entry(setOf("white", "w"), MagicColor.WHITE, ManaColor.White_afc9, 1),
            Entry(setOf("blue", "u"), MagicColor.BLUE, ManaColor.Blue_afc9, 2),
            Entry(setOf("black", "b"), MagicColor.BLACK, ManaColor.Black_afc9, 3),
            Entry(setOf("red", "r"), MagicColor.RED, ManaColor.Red_afc9, 4),
            Entry(setOf("green", "g"), MagicColor.GREEN, ManaColor.Green_afc9, 5),
        )

    fun magicMaskForManaColor(color: ManaColor): Byte? = entries.firstOrNull { it.manaColor == color }?.mask

    fun manaColorNumbersFromMagicMask(mask: Int): List<Int> =
        entries.mapNotNull { entry ->
            if (mask and entry.mask.toInt() != 0) entry.manaColor.number else null
        }

    fun staticIdForMagicMask(mask: Byte): Int? = entries.firstOrNull { it.mask == mask }?.staticId

    fun staticIdForName(name: String): Int? = entries.firstOrNull { name.lowercase() in it.names }?.staticId
}
