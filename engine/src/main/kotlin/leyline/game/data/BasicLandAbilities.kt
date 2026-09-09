package leyline.game.data

import forge.card.MagicColor
import forge.game.card.Card
import forge.game.spellability.SpellAbility
import wotc.mtgo.gre.external.messaging.Messages.SubType

/**
 * Well-known client ability identifiers for the five basic-land mana
 * abilities. The integer is the row's `Id` in the client's `Abilities` table
 * (also the value that appears verbatim in `Cards.AbilityIds` for any
 * basic of that type). Shared by action projection and mana-activation
 * annotations.
 */
object BasicLandAbilities {
    private data class Entry(
        val forgeSubtypeName: String,
        val forgeColor: MagicColor.Color,
        val protoSubtype: SubType,
        val abilityGrpId: Int,
    )

    private val entries =
        listOf(
            Entry("plains", MagicColor.Color.WHITE, SubType.Plains, 1001),
            Entry("island", MagicColor.Color.BLUE, SubType.Island, 1002),
            Entry("swamp", MagicColor.Color.BLACK, SubType.Swamp, 1003),
            Entry("mountain", MagicColor.Color.RED, SubType.Mountain, 1004),
            Entry("forest", MagicColor.Color.GREEN, SubType.Forest, 1005),
        )

    /** Returns the implicit mana ability grpId for Forge subtype names. */
    fun byForgeSubtypeNames(subtypes: Iterable<String>): Int? {
        val normalized = subtypes.mapTo(mutableSetOf()) { it.lowercase() }
        return entries.firstOrNull { it.forgeSubtypeName in normalized }?.abilityGrpId
    }

    /** Returns the implicit identity only when [ability] is the state-cached basic-land ability. */
    fun byTypeDerivedManaAbility(
        card: Card,
        ability: SpellAbility,
    ): Int? {
        val entry = entries.singleOrNull { it.forgeColor.shortName == ability.manaPart?.origProduced } ?: return null
        if (!card.type.hasSubtype(entry.forgeColor.basicLandType)) return null
        val generated = card.currentState?.getLandManaForColor(entry.forgeColor) ?: return null
        return entry.abilityGrpId.takeIf { generated.definitionId == ability.definitionId }
    }

    /** Returns the implicit mana ability grpId for proto SubType ordinals. */
    fun byProtoSubtypeOrdinals(subtypes: Iterable<Int>): Int? {
        val values = subtypes.toSet()
        return entries.firstOrNull { it.protoSubtype.number in values }?.abilityGrpId
    }
}
