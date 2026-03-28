package leyline.game

import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Immutable card metadata from the client's card database.
 *
 * DB enum values (CardColor, CardType, SubType) map 1:1 to proto enum values.
 */
data class CardData(
    val grpId: Int,
    val titleId: Int,
    val power: String,
    val toughness: String,
    val colors: List<Int>, // proto CardColor values
    val types: List<Int>, // proto CardType values
    val subtypes: List<Int>, // proto SubType values
    val supertypes: List<Int>, // proto SuperType values
    val abilityIds: List<Pair<Int, Int>>, // abilityGrpId:textId pairs
    val manaCost: List<Pair<ManaColor, Int>>, // (color, count) from OldSchoolManaText
    val tokenGrpIds: Map<Int, Int> = emptyMap(), // abilityGrpId → tokenGrpId
    val keywordAbilityGrpIds: Map<String, Int> = emptyMap(), // keyword name → abilityGrpId
    val linkedFaceGrpIds: List<Int> = emptyList(),
) {
    val isMultiFace: Boolean get() = linkedFaceGrpIds.isNotEmpty()
}
