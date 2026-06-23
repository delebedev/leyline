package leyline.game.data

import wotc.mtgo.gre.external.messaging.Messages.SubType

/**
 * Well-known client ability identifiers for the five basic-land mana
 * abilities. The integer is the row's `Id` in the client's `Abilities` table
 * (also the value that appears verbatim in `Cards.AbilityIds` for any
 * basic of that type). Used by `ZoneTransferDetector` to tag mana-ability
 * activations.
 */
object BasicLandAbilities {
    private data class Entry(
        val forgeSubtypeName: String,
        val protoSubtype: SubType,
        val abilityGrpId: Int,
    )

    private val entries =
        listOf(
            Entry("plains", SubType.Plains, 1001),
            Entry("island", SubType.Island, 1002),
            Entry("swamp", SubType.Swamp, 1003),
            Entry("mountain", SubType.Mountain, 1004),
            Entry("forest", SubType.Forest, 1005),
        )

    /** Returns the implicit mana ability grpId for Forge subtype names. */
    fun byForgeSubtypeNames(subtypes: Iterable<String>): Int? {
        val normalized = subtypes.mapTo(mutableSetOf()) { it.lowercase() }
        return entries.firstOrNull { it.forgeSubtypeName in normalized }?.abilityGrpId
    }

    /** Returns the implicit mana ability grpId for proto SubType ordinals. */
    fun byProtoSubtypeOrdinals(subtypes: Iterable<Int>): Int? {
        val values = subtypes.toSet()
        return entries.firstOrNull { it.protoSubtype.number in values }?.abilityGrpId
    }
}
