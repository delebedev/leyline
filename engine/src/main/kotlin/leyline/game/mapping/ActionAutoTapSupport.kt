package leyline.game.mapping

import forge.card.mana.ManaCost
import leyline.bridge.getPlayableManaAbilities
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ManaColorMapping
import leyline.game.PriorityAutoTapActionValue
import leyline.game.PriorityAutoTapSolutionValue
import leyline.game.PriorityManaInfoValue
import leyline.game.PriorityManaPaymentOptionValue
import leyline.game.PriorityManaSpec
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds predictive auto-tap suggestions for payable action costs.
 *
 * Preserve the client-visible mana details here: predictive ids start at 10,
 * snow sources carry `FromSnow`, mana ability ids match the tapped source, and
 * two-generic hybrid costs may be paid either by color or by two generic mana.
 */
internal object ActionAutoTapSupport {
    private const val INITIAL_MANA_ID = 10

    private data class ManaSource(
        val cardId: ForgeCardId,
        val color: ManaColor,
        val abilityGrpId: Int,
        val fromSnow: Boolean,
    )

    fun build(
        manaCost: ManaCost,
        context: ActionBuildContext,
    ): PriorityAutoTapSolutionValue? =
        if (manaCost.any { ManaColorMapping.fromOrTwoGenericShard(it) != null }) {
            buildOrTwoGenericAutoTapSolution(manaCost, context)
        } else {
            build(ActionManaCosts.forgeManaCostToPairs(manaCost), context)
        }

    @Suppress("CyclomaticComplexMethod")
    private fun build(
        manaCost: List<Pair<ManaColor, Int>>,
        context: ActionBuildContext,
    ): PriorityAutoTapSolutionValue? {
        if (manaCost.isEmpty()) return null
        val sources = collectManaSources(context)

        val usedSourceCardIds = mutableSetOf<ForgeCardId>()
        val matched = mutableListOf<Pair<ManaSource, ManaColor>>()
        val coloredReqs = manaCost.filter { it.first != ManaColor.Generic }
        val genericReqs = manaCost.filter { it.first == ManaColor.Generic }

        for ((reqColor, reqCount) in coloredReqs) {
            var remaining = reqCount
            for (src in sources) {
                if (remaining <= 0) break
                if (src.cardId in usedSourceCardIds) continue
                if (canPayRequirement(src, reqColor)) {
                    usedSourceCardIds.add(src.cardId)
                    matched.add(src to src.color)
                    remaining--
                }
            }
            if (remaining > 0) return null
        }

        for ((_, reqCount) in genericReqs) {
            var remaining = reqCount
            for (src in sources) {
                if (remaining <= 0) break
                if (src.cardId in usedSourceCardIds) continue
                usedSourceCardIds.add(src.cardId)
                matched.add(src to src.color)
                remaining--
            }
            if (remaining > 0) return null
        }

        return buildAutoTapSolution(matched)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun buildOrTwoGenericAutoTapSolution(
        manaCost: ManaCost,
        context: ActionBuildContext,
    ): PriorityAutoTapSolutionValue? {
        val sources = collectManaSources(context)
        if (sources.isEmpty()) return null

        val coloredRequirements = mutableListOf<ManaColor>()
        val hybridRequirements = mutableListOf<ManaColor>()
        for (shard in manaCost) {
            val hybridColor = ManaColorMapping.fromOrTwoGenericShard(shard)
            if (hybridColor != null) {
                hybridRequirements.add(hybridColor)
            } else {
                ManaColorMapping.fromShard(shard)?.let(coloredRequirements::add)
            }
        }

        val used = mutableSetOf<ForgeCardId>()
        val matched = mutableListOf<Pair<ManaSource, ManaColor>>()

        fun payColor(
            color: ManaColor,
            then: () -> Boolean,
        ): Boolean {
            for (src in sources) {
                if (src.cardId in used || !canPayRequirement(src, color)) continue
                used.add(src.cardId)
                matched.add(src to src.color)
                if (then()) return true
                matched.removeAt(matched.lastIndex)
                used.remove(src.cardId)
            }
            return false
        }

        fun payGeneric(
            needed: Int,
            then: () -> Boolean,
        ): Boolean {
            if (needed == 0) return then()
            for (src in sources) {
                if (src.cardId in used) continue
                used.add(src.cardId)
                matched.add(src to src.color)
                if (payGeneric(needed - 1, then)) return true
                matched.removeAt(matched.lastIndex)
                used.remove(src.cardId)
            }
            return false
        }

        fun payHybrids(index: Int): Boolean {
            if (index == hybridRequirements.size) {
                return payGeneric(manaCost.genericCost) { true }
            }
            val color = hybridRequirements[index]
            return payColor(color) { payHybrids(index + 1) } ||
                payGeneric(2) { payHybrids(index + 1) }
        }

        fun payColored(index: Int): Boolean {
            if (index == coloredRequirements.size) return payHybrids(0)
            return payColor(coloredRequirements[index]) { payColored(index + 1) }
        }

        return if (payColored(0)) buildAutoTapSolution(matched) else null
    }

    private fun canPayRequirement(
        src: ManaSource,
        reqColor: ManaColor,
    ): Boolean =
        if (reqColor == ManaColor.Snow_afc9) {
            src.fromSnow
        } else {
            reqColor == ManaColor.Generic || src.color == ManaColor.Generic || src.color == reqColor
        }

    private fun collectManaSources(context: ActionBuildContext): List<ManaSource> {
        val sources = mutableListOf<ManaSource>()
        for (card in context.player.getZone(ForgeZoneType.Battlefield).cards) {
            if (card.isTapped) continue
            for (sa in getPlayableManaAbilities(card, context.player)) {
                val colors = ActivatedActionEmitter.producedManaColors(sa)
                if (colors.isEmpty()) continue
                val grpId = context.grpId(card)
                val cardData = context.cardData(grpId)
                val registry = context.abilityRegistry(card, cardData)
                val abilityGrpId = registry?.forSpellAbility(sa.definitionId) ?: ActivatedActionEmitter.basicLandAbilityGrpId(card)
                for (color in colors) {
                    sources.add(ManaSource(ForgeCardId(card.id), color, abilityGrpId, fromSnow = card.type.isSnow))
                }
            }
        }
        return sources
    }

    private fun buildAutoTapSolution(matched: List<Pair<ManaSource, ManaColor>>): PriorityAutoTapSolutionValue {
        var manaIdCounter = INITIAL_MANA_ID
        return PriorityAutoTapSolutionValue(
            actions =
                matched.map { (src, payingColor) ->
                    PriorityAutoTapActionValue(
                        cardId = src.cardId,
                        abilityGrpId = src.abilityGrpId,
                        manaPaymentOption =
                            PriorityManaPaymentOptionValue(
                                mana =
                                    listOf(
                                        PriorityManaInfoValue(
                                            manaId = manaIdCounter++,
                                            color = payingColor.toPriorityManaColor(),
                                            sourceCardId = src.cardId,
                                            specs =
                                                buildSet {
                                                    add(PriorityManaSpec.PREDICTIVE)
                                                    if (src.fromSnow) add(PriorityManaSpec.FROM_SNOW)
                                                },
                                            abilityGrpId = src.abilityGrpId,
                                            count = 1,
                                        ),
                                    ),
                            ),
                    )
                },
        )
    }
}
