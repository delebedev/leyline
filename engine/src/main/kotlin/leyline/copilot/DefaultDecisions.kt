package leyline.copilot

import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Pure prompt → [SimDecision] defaults shared by the two response pipelines: the
 * copilot endpoint (its fallback when Forge AI declines, and its handler for
 * families Forge AI doesn't model) and the simclient greedy policy.
 *
 * Every function reads only the prompt — no game state — so it is the single
 * source of "the safe default answer" for a family. This is what lets the copilot
 * endpoint cover every prompt type simclient already handles instead of falling
 * through to `unrealizable`.
 */
internal object DefaultDecisions {
    private const val NUMERIC_INPUT_DEFAULT_MAX = 3

    /** Keep the order the engine offered. */
    fun order(msg: GREToClientMessage): SimDecision = SimDecision.Order(msg.orderReq.idsList.toList())

    /** Allocate the required total with one point per target, giving the remainder to the first target. */
    fun distribution(msg: GREToClientMessage): SimDecision {
        val req = msg.distributionReq
        val ids = req.validSelectedTargetIdsList.ifEmpty { req.targetIdsList }
        val min = req.minPerTarget.coerceAtLeast(1)
        val total = req.maxAmount.coerceAtLeast(req.minAmount)
        val amounts = ids.map { it to min }.toMutableList()
        val remainder = total - amounts.sumOf { it.second }
        if (remainder > 0 && amounts.isNotEmpty()) {
            val (id, amount) = amounts.first()
            amounts[0] = id to (amount + remainder)
        }
        return SimDecision.Distribution(amounts.toMap())
    }

    /** Take the required number of sought items. */
    fun search(msg: GREToClientMessage): SimDecision {
        val req = msg.searchReq
        val count = (if (req.maxFind > 0) req.maxFind else req.minFind).coerceAtLeast(req.minFind).coerceAtLeast(1)
        return SimDecision.Search(req.itemsSoughtList.take(count))
    }

    /** Select the first permitted entries from the first grouped-search row. */
    fun groupedSearch(msg: GREToClientMessage): SimDecision {
        val group =
            msg.searchFromGroupsReq.groupsList.firstOrNull()
                ?: return SimDecision.GroupedSearch(0, emptyList(), 0)
        val count = group.maxSelect.coerceAtLeast(1)
        return SimDecision.GroupedSearch(group.groupId, group.idsList.take(count), group.maxSelect)
    }

    /** Echo the first complete replacement row as the documented default. */
    fun selectReplacement(msg: GREToClientMessage): SimDecision =
        SimDecision.SelectReplacement(msg.selectReplacementReq.replacementsList.first())

    /** Pick a small legal value (min, capped at [NUMERIC_INPUT_DEFAULT_MAX]). */
    fun numericInput(msg: GREToClientMessage): SimDecision {
        val req = msg.numericInputReq
        return SimDecision.NumericInput(req.minValue.coerceAtLeast(NUMERIC_INPUT_DEFAULT_MAX.coerceAtMost(req.maxValue)))
    }

    /** Submit an allocation only when the prompt constraints leave one possible answer. */
    fun forcedDistribution(msg: GREToClientMessage): SimDecision.Distribution? {
        val req = msg.distributionReq
        if (req.minAmount <= 0 || req.minAmount != req.maxAmount) return null
        if (req.existingDistributionValuesCount > 0 || req.requiredDistributionValuesCount > 0) return null
        val targets = (req.validSelectedTargetIdsList.ifEmpty { req.targetIdsList }).distinct()
        if (targets.isEmpty() || (req.targetIdsCount > 0 && targets.toSet() != req.targetIdsList.toSet())) return null
        val amount =
            when {
                targets.size == 1 -> req.minAmount
                req.minAmount == targets.size * req.minPerTarget -> req.minPerTarget
                else -> return null
            }
        if (amount < req.minPerTarget) return null
        return SimDecision.Distribution(targets.associateWith { amount })
    }

    /** Answer a "you may" trigger; accept by default. */
    fun optionalAction(accept: Boolean = true): SimDecision = SimDecision.OptionalAction(accept)

    /** Scry/surveil: keep everything on top (moves nothing). */
    fun group(msg: GREToClientMessage): SimDecision = SimDecision.GroupTop(msg.groupReq.instanceIdsList.toList())

    /** Echo the engine's pre-filled combat damage assignment (lethal per blocker + trample overflow). */
    fun assignDamage(msg: GREToClientMessage): SimDecision =
        SimDecision.AssignDamage(
            msg.assignDamageReq.damageAssignersList.map { da ->
                SimDecision.DamageAssignerDecision(
                    instanceId = da.instanceId,
                    totalDamage = da.totalDamage,
                    assignments =
                        da.assignmentsList.map { assignment ->
                            SimDecision.DamageAssignmentDecision(
                                instanceId = assignment.instanceId,
                                minDamage = assignment.minDamage,
                                maxDamage = assignment.maxDamage.takeIf { it > 0 } ?: da.totalDamage,
                                assignedDamage = assignment.assignedDamage,
                            )
                        },
                )
            },
        )

    /** Select the minimum required entries. */
    fun selectN(msg: GREToClientMessage): SimDecision {
        val req = msg.selectNReq
        val min = req.minSel.coerceAtLeast(0)
        val max = if (req.maxSel > 0) req.maxSel else min
        return SimDecision.SelectN(req.idsList.take(min.coerceAtMost(max)))
    }

    /** Per-cto mana colours for a mana-type casting-time choice, or null if not one. */
    fun manaTypeChoices(msg: GREToClientMessage): List<Pair<Int, ManaColor>>? {
        val options =
            msg.castingTimeOptionsReq.castingTimeOptionReqList.filter {
                it.castingTimeOptionType == CastingTimeOptionType.ManaType && it.hasSelectManaTypeReq()
            }
        if (options.isEmpty()) return null
        return options.map { option ->
            val color = option.selectManaTypeReq.manaColorsList.firstOrNull { it != ManaColor.TwoGeneric } ?: ManaColor.TwoGeneric
            option.ctoId to color
        }
    }

    /** First [minSel] modal grpIds, bound to the casting-time option that offered them. */
    fun modalChoice(msg: GREToClientMessage): SimDecision.ModalChoice? {
        val option =
            msg.castingTimeOptionsReq.castingTimeOptionReqList
                .firstOrNull { it.castingTimeOptionType == CastingTimeOptionType.Modal_a7b4 && it.hasModalReq() }
                ?: return null
        val req = option.modalReq
        val min = req.minSel.coerceAtLeast(0)
        val max = if (req.maxSel > 0) req.maxSel else min
        return SimDecision.ModalChoice(
            ctoId = option.ctoId,
            selectedGrpIds = req.modalOptionsList.map { it.grpId }.take(min.coerceAtMost(max)),
        )
    }

    /** First [minSel] modal grpIds for callers that do not submit the response themselves. */
    fun modalGrpIds(msg: GREToClientMessage): List<Int>? = modalChoice(msg)?.selectedGrpIds

    /**
     * The required branch pick for an alternate additional cost, or null if this
     * request carries no such option. The branch is named by a `selectNReq` id,
     * so declining with ctoId 0 leaves the cast unanswerable.
     */
    fun alternateCostChoice(msg: GREToClientMessage): SimDecision.AlternateCost? {
        val option =
            msg.castingTimeOptionsReq.castingTimeOptionReqList
                .firstOrNull { it.castingTimeOptionType == CastingTimeOptionType.ChooseOrCost && it.hasSelectNReq() }
                ?: return null
        val optionIndex = option.selectNReq.idsList.firstOrNull() ?: return null
        return SimDecision.AlternateCost(ctoId = option.ctoId, optionIndex = optionIndex)
    }

    /** CastingTimeOptions: mana-type, else modal, else alternate cost, else decline optional costs (ctoId 0). */
    fun castingTimeOptions(
        msg: GREToClientMessage,
        acceptOptionalCosts: Boolean = false,
    ): SimDecision {
        manaTypeChoices(msg)?.let { return SimDecision.ManaTypeChoices(it) }
        modalChoice(msg)?.let { return it }
        alternateCostChoice(msg)?.let { return it }
        val ctoId =
            if (!acceptOptionalCosts) {
                0
            } else {
                msg.castingTimeOptionsReq.castingTimeOptionReqList
                    .firstOrNull { it.ctoId != 0 }
                    ?.ctoId ?: 0
            }
        return SimDecision.OptionalCost(ctoId)
    }
}
