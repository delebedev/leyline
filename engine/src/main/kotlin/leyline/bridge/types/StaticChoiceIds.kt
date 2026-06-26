package leyline.bridge.types

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

    fun colorIdForMask(mask: Byte): Int? = WubrgColorMapping.staticIdForMagicMask(mask)

    fun colorIdForName(name: String): Int? = WubrgColorMapping.staticIdForName(name)

    fun parityIdForName(name: String): Int? =
        when (normalize(name)) {
            "even", "evens" -> 1
            "odd", "odds" -> 2
            else -> null
        }

    fun subtypeIdFor(typeName: String): Int? = subtypeByKey[normalize(typeName)]

    private fun normalize(value: String): String = value.filter { it.isLetterOrDigit() }.lowercase()
}
