package leyline.game.data

import leyline.game.codes.SlotKind
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
    /**
     * Engine-only ability ids not displayed on the card face. Mirrors the
     * client card-DB `Cards.HiddenAbilityIds` field (same `id:textLocId`
     * shape as [abilityIds]). Used for delayed-trigger pairings — every
     * Mobilize source has its cleanup ability ("Sacrifice them at the
     * beginning of the next end step.") here, paired with the keyword on
     * [abilityIds]. Empty for cards that don't carry a hidden
     * delayed-trigger ability.
     */
    val hiddenAbilityIds: List<Pair<Int, Int>> = emptyList(),
    /**
     * Per-slot kind aligned 1:1 with [abilityIds]. Sourced from Arena
     * `Abilities.Category` (1=Activated → [SlotKind.Activated]; everything
     * else → [SlotKind.Intrinsic] for triggers/statics) for production
     * data, and from explicit construction by [AbilityIdDeriver] for
     * puzzle/test paths. Empty list means "kinds unknown" — consumers
     * should fall back to the legacy "all-after-keywords-are-activated"
     * positional assumption.
     */
    val abilityKinds: List<SlotKind> = emptyList(),
    val manaCost: List<Pair<ManaColor, Int>>, // (color, count) from OldSchoolManaText
    val tokenGrpIds: Map<Int, Int> = emptyMap(), // abilityGrpId → tokenGrpId
    /**
     * Per-chapter ability grpIds for Saga cards, indexed 0-based by chapter number
     * (chapter I at index 0, chapter II at index 1, ...). Empty for non-saga cards.
     *
     * Production (Arena client DB) stores these inside [abilityIds] and the resolver
     * falls back to positional lookup there. This field is populated by
     * [AbilityIdDeriver] from live Forge triggers so InMemoryCardRepository tests
     * can exercise the same resolution path.
     */
    val chapterAbilityGrpIds: List<Int> = emptyList(),
    val linkedFaceGrpIds: List<Int> = emptyList(),
) {
    val isMultiFace: Boolean get() = linkedFaceGrpIds.isNotEmpty()
}
