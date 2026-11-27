package leyline.bridge.interaction

import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.types.AbilityKeywordFamily
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.bridge.types.StackTargetAffectee
import leyline.bridge.types.StackTargetGroup
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds

/**
 * Target-group facts derived from a Forge [SpellAbility] chain.
 *
 * A stack object owns one target group per ability in its chain that has
 * target restrictions; groups are numbered from 1 in chain order. These rules
 * are shared by the interactive submission path and by stack objects whose
 * targets Forge chose without a client prompt.
 */
object TargetGroupShapes {
    /** 1-based position of [sa]'s target group within its root ability chain. */
    fun groupIndex(sa: SpellAbility): Int {
        var index = 0
        var current: SpellAbility? = sa.rootAbility
        while (current != null) {
            if (current.targetRestrictions != null) index++
            if (current === sa) return index.coerceAtLeast(1)
            current = current.subAbility
        }
        return 1
    }

    /** Chosen targets of [sa] in Forge's order; [seatOf] maps a player to its seat. */
    fun affectees(
        sa: SpellAbility,
        seatOf: (Player) -> Int?,
    ): List<StackTargetAffectee> =
        sa.targets.targetEntities.mapNotNull { target ->
            when (target) {
                is Card -> StackTargetAffectee(cardId = target.id, distribution = sa.getDividedValue(target))
                is Player -> seatOf(target)?.let { StackTargetAffectee(seatId = it, distribution = sa.getDividedValue(target)) }
                else -> null
            }
        }

    /** Every chosen target group of the stack object rooted at [root]. */
    fun groups(
        root: SpellAbility,
        seatOf: (Player) -> Int?,
        identityOf: (SpellAbility) -> ResolvedAbilityIdentity?,
    ): List<StackTargetGroup> {
        val groups = mutableListOf<StackTargetGroup>()
        var index = 0
        var current: SpellAbility? = root.rootAbility
        while (current != null) {
            if (current.targetRestrictions != null) {
                index++
                val affectees = affectees(current, seatOf)
                if (affectees.isNotEmpty()) {
                    val identity = identityOf(current)
                    groups +=
                        StackTargetGroup(
                            index = index,
                            affectees = affectees,
                            promptId = promptId(current, identity),
                            abilityGrpId = abilityGrpId(current, identity),
                        )
                }
            }
            current = current.subAbility
        }
        return groups
    }

    /** Prompt id shared by a target request group and its TargetSpec. */
    fun promptId(
        sa: SpellAbility,
        identity: ResolvedAbilityIdentity?,
    ): Int =
        when {
            identity?.keywordFamily == AbilityKeywordFamily.Mentor -> PromptIds.MENTOR_TARGET
            sa.isMutate -> PromptIds.MUTATE_TARGET
            else -> restrictionPromptId(sa) ?: PromptIds.SELECT_TARGETS
        }

    /** Targeting ability id for a TargetSpec; 0 when the identity is unresolved. */
    fun abilityGrpId(
        sa: SpellAbility,
        identity: ResolvedAbilityIdentity?,
    ): Int =
        if (SpellAbilityShapes.isReconfigureAttach(sa)) {
            KeywordAbilityIds.RECONFIGURE_ATTACH
        } else {
            identity?.abilityGrpId ?: 0
        }

    private fun restrictionPromptId(sa: SpellAbility): Int? {
        val valid =
            sa.targetRestrictions
                ?.validTgts
                ?.toList()
                .orEmpty()
        if (valid.isEmpty()) return null
        val normalized = valid.map { it.lowercase() }
        if (normalized == listOf("permanent.nonland")) return PromptIds.TARGET_NONLAND_PERMANENT
        if (normalized == listOf("opponent")) return PromptIds.TARGET_OPPONENT
        if (normalized == listOf("any")) return PromptIds.CHOOSE_ANY_TARGET
        val allOpponentControlled = normalized.all { "youdontctrl" in it }
        val targetKinds =
            normalized
                .flatMap { restriction ->
                    buildList {
                        if ("creature" in restriction) add("creature")
                        if ("planeswalker" in restriction) add("planeswalker")
                    }
                }.toSet()
        return when {
            targetKinds == setOf("creature", "planeswalker") && allOpponentControlled ->
                PromptIds.TARGET_CREATURE_OR_PLANESWALKER_YOU_DONT_CONTROL
            targetKinds == setOf("creature") && normalized.all { "youctrl" in it && "youdontctrl" !in it } ->
                PromptIds.TARGET_CREATURE_YOU_CONTROL
            targetKinds == setOf("creature") && normalized.all { "oppctrl" in it } ->
                PromptIds.TARGET_OPPONENT_CREATURE
            targetKinds == setOf("creature") && allOpponentControlled ->
                PromptIds.TARGET_CREATURE_YOU_DONT_CONTROL
            targetKinds == setOf("creature") && normalized.none { "youctrl" in it || "youdontctrl" in it || "oppctrl" in it } ->
                PromptIds.TARGET_CREATURE
            else -> null
        }
    }
}
