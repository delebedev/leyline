package leyline.game

import leyline.bridge.types.ForgeCardId

/**
 * Immutable, protocol-neutral presentation of one priority action.
 *
 * Executable engine commands and owner-allocated protocol identity are
 * deliberately absent. The owner resolves card identity while projecting.
 */
internal sealed interface PriorityActionValue {
    data class Cast(
        val kind: PriorityCastKind,
        val cardId: ForgeCardId,
        val grpId: Int? = null,
        val facetCardId: ForgeCardId? = null,
        val abilityGrpId: Int = 0,
        val sourceCardId: ForgeCardId? = null,
        val alternativeGrpId: Int = 0,
        val manaCost: List<ManaRequirementValue> = emptyList(),
        val shouldStop: Boolean,
        val alternativeSourceCardId: ForgeCardId? = null,
        val autoTapSolution: PriorityAutoTapSolutionValue? = null,
    ) : PriorityActionValue

    data class Activate(
        val cardId: ForgeCardId,
        val grpId: Int? = null,
        val abilityGrpId: Int = 0,
        val uniqueAbilityId: Int = 0,
        val manaCost: List<ManaRequirementValue> = emptyList(),
        val shouldStop: Boolean,
        val autoTapSolution: PriorityAutoTapSolutionValue? = null,
    ) : PriorityActionValue

    data class ActivateMana(
        val cardId: ForgeCardId,
        val grpId: Int,
        val abilityGrpId: Int = 0,
        val uniqueAbilityId: Int = 0,
        val manaPaymentOptions: List<PriorityManaPaymentOptionValue> = emptyList(),
        val manaCost: List<ManaRequirementValue> = emptyList(),
        val manaSelections: List<PriorityManaSelectionValue> = emptyList(),
        val batchable: Boolean,
    ) : PriorityActionValue

    data class PlayLand(
        val kind: PriorityPlayKind,
        val cardId: ForgeCardId,
        val grpId: Int? = null,
        val shouldStop: Boolean,
    ) : PriorityActionValue

    data class TurnFaceUp(
        val cardId: ForgeCardId,
        val alternativeGrpId: Int,
        val manaCost: List<ManaRequirementValue>,
        val shouldStop: Boolean,
    ) : PriorityActionValue

    data object Pass : PriorityActionValue

    data object FloatMana : PriorityActionValue
}

internal data class PriorityActionSet(
    val actions: List<PriorityActionValue>,
    val inactiveActions: List<PriorityActionValue>,
)

internal enum class PriorityCastKind {
    CAST,
    ADVENTURE,
    MDFC,
    LEFT_ROOM,
    RIGHT_ROOM,
    OMEN,
}

internal enum class PriorityPlayKind {
    LAND,
    MDFC,
}

internal data class PriorityManaPaymentOptionValue(
    val mana: List<PriorityManaInfoValue>,
)

internal data class PriorityManaInfoValue(
    val manaId: Int,
    val color: PriorityManaColor,
    val sourceCardId: ForgeCardId,
    val specs: Set<PriorityManaSpec>,
    val abilityGrpId: Int,
    val count: Int,
)

internal data class PriorityManaSelectionValue(
    val cardId: ForgeCardId,
    val abilityGrpId: Int,
    val selectionCount: Int,
    val validation: PriorityManaSelectionValidation,
    val options: List<PriorityManaSelectionOptionValue>,
)

internal data class PriorityManaSelectionOptionValue(
    val selectedColor: PriorityManaColor,
    val mana: List<PriorityManaColorCountValue>,
)

internal data class PriorityManaColorCountValue(
    val color: PriorityManaColor,
    val count: Int,
)

internal data class PriorityAutoTapSolutionValue(
    val actions: List<PriorityAutoTapActionValue>,
)

internal data class PriorityAutoTapActionValue(
    val cardId: ForgeCardId,
    val abilityGrpId: Int,
    val manaPaymentOption: PriorityManaPaymentOptionValue,
)

internal enum class PriorityManaColor {
    WHITE,
    BLUE,
    BLACK,
    RED,
    GREEN,
    GENERIC,
    COLORLESS,
    SNOW,
    TWO_GENERIC,
}

internal enum class PriorityManaSpec {
    PREDICTIVE,
    FROM_SNOW,
}

internal enum class PriorityManaSelectionValidation {
    NON_REPEATABLE,
}
