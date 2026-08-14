package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.util.Collections

/** Immutable client-prompt facts and opaque runtime choices for one offered cast. */
@ConsistentCopyVisibility
internal data class DeferredCastCostPlan private constructor(
    val sourceCardId: ForgeCardId,
    val instanceId: Int,
    val grpId: Int,
    val hybrid: HybridManaPlan?,
    val optional: OptionalCostPlan?,
    val alternate: AlternateCostPlan?,
) {
    @ConsistentCopyVisibility
    data class HybridManaPlan private constructor(
        val promptColors: List<ManaColor>,
        val paymentColors: List<ManaColor>,
        val manaCost: List<ManaRequirementSpec>,
    ) {
        companion object {
            fun frozen(
                promptColors: List<ManaColor>,
                paymentColors: List<ManaColor>,
                manaCost: List<ManaRequirementSpec>,
            ) = HybridManaPlan(
                frozenList(promptColors),
                frozenList(paymentColors),
                frozenList(manaCost.map { ManaRequirementSpec.frozen(it.colors, it.count) }),
            )
        }
    }

    @ConsistentCopyVisibility
    data class OptionalCostPlan private constructor(
        val entries: List<OptionalCostEntry>,
        val baseManaCost: List<Pair<ManaColor, Int>>,
    ) {
        companion object {
            fun frozen(
                entries: List<OptionalCostEntry>,
                baseManaCost: List<Pair<ManaColor, Int>>,
            ) = OptionalCostPlan(frozenList(entries), frozenList(baseManaCost))
        }
    }

    data class OptionalCostEntry(
        val type: CastingTimeOptionType,
        val abilityGrpId: Int,
        val keywordName: String?,
    )

    @ConsistentCopyVisibility
    data class AlternateCostPlan private constructor(
        val choices: List<AlternateCostChoice>,
    ) {
        companion object {
            fun frozen(choices: List<AlternateCostChoice>) = AlternateCostPlan(frozenList(choices))
        }
    }

    data class AlternateCostChoice(
        val runtimeToken: Long,
        val promptId: Int?,
    )

    companion object {
        fun frozen(
            sourceCardId: ForgeCardId,
            instanceId: Int,
            grpId: Int,
            hybrid: HybridManaPlan?,
            optional: OptionalCostPlan?,
            alternate: AlternateCostPlan?,
        ): DeferredCastCostPlan =
            DeferredCastCostPlan(
                sourceCardId,
                instanceId,
                grpId,
                hybrid?.let {
                    HybridManaPlan.frozen(
                        frozenList(it.promptColors),
                        frozenList(it.paymentColors),
                        frozenList(it.manaCost.map { requirement -> ManaRequirementSpec.frozen(requirement.colors, requirement.count) }),
                    )
                },
                optional?.let { OptionalCostPlan.frozen(it.entries, it.baseManaCost) },
                alternate?.let { AlternateCostPlan.frozen(it.choices) },
            )

        fun hybrid(
            promptColors: List<ManaColor>,
            paymentColors: List<ManaColor>,
            manaCost: List<ManaRequirementSpec>,
        ): HybridManaPlan = HybridManaPlan.frozen(promptColors, paymentColors, manaCost)

        fun optional(
            entries: List<OptionalCostEntry>,
            baseManaCost: List<Pair<ManaColor, Int>>,
        ): OptionalCostPlan = OptionalCostPlan.frozen(entries, baseManaCost)

        fun alternate(choices: List<AlternateCostChoice>): AlternateCostPlan = AlternateCostPlan.frozen(choices)

        private fun <T> frozenList(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    }
}
