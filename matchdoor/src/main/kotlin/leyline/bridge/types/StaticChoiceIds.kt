package leyline.bridge.types

import forge.card.MagicColor
import wotc.mtgo.gre.external.messaging.Messages.SubType

/** Arena static-list ids used by enum-domain SelectN prompts. */
object StaticChoiceIds {
    private val subtypeByKey: Map<String, Int> =
        SubType
            .values()
            .asSequence()
            .filter { it.name != "UNRECOGNIZED" }
            .filter { it.number > 0 }
            .filterNot { it.name.startsWith("PlaceholderSubType") }
            .associateBy(
                keySelector = { normalize(it.name.substringBefore('_')) },
                valueTransform = { it.number },
            )

    fun colorIdForMask(mask: Byte): Int? =
        when (mask.toInt()) {
            MagicColor.WHITE.toInt() -> 1
            MagicColor.BLUE.toInt() -> 2
            MagicColor.BLACK.toInt() -> 3
            MagicColor.RED.toInt() -> 4
            MagicColor.GREEN.toInt() -> 5
            else -> null
        }

    fun colorIdForName(name: String): Int? =
        when (name.lowercase()) {
            "white", "w" -> 1
            "blue", "u" -> 2
            "black", "b" -> 3
            "red", "r" -> 4
            "green", "g" -> 5
            else -> null
        }

    fun parityIdForName(name: String): Int? =
        when (normalize(name)) {
            "even", "evens" -> 1
            "odd", "odds" -> 2
            else -> null
        }

    fun subtypeIdFor(typeName: String): Int? = subtypeByKey[normalize(typeName)]

    private fun normalize(value: String): String = value.filter { it.isLetterOrDigit() }.lowercase()
}
