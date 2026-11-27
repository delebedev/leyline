package leyline.bridge.types

/** One chosen target of a stack object's target group: a card or a player seat, with its divided amount when allocated. */
data class StackTargetAffectee(
    val cardId: Int? = null,
    val seatId: Int? = null,
    val distribution: Int? = null,
)

/** A chosen target group of one stack object, numbered from 1 in ability-chain order. */
data class StackTargetGroup(
    val index: Int,
    val affectees: List<StackTargetAffectee>,
    val promptId: Int,
    val abilityGrpId: Int,
)
