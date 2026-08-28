package leyline.copilot

import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/** Resolves an instance id to a display-ready [EntityRef]. Bridge-backed live; faked in tests. */
internal fun interface EntityResolver {
    fun resolve(instanceId: Int): EntityRef
}

/**
 * Pure map from a Forge-AI [SimDecision] to an autoplay [CopilotProposal].
 * No engine state access — entity metadata comes through the injected
 * [EntityResolver] — so the decision→intent contract is unit-testable per
 * prompt family without a live game.
 *
 * Decision kinds outside the covered families (Order, Search, Group,
 * AssignDamage, OptionalAction, and the control sentinels) map to
 * `unrealizable`: the loop records them and falls back, rather than guessing a
 * gesture. Add a family here when its executor lands.
 */
internal object ProposalTranslator {
    fun unrealizable(
        promptType: GREMessageType,
        seat: Int,
        reason: String,
    ): CopilotProposal =
        CopilotProposal(
            intent = "unrealizable",
            promptType = promptType.name,
            seat = seat,
            reason = reason,
        )

    @Suppress("CyclomaticComplexMethod")
    fun translate(
        decision: SimDecision,
        promptType: GREMessageType,
        seat: Int,
        resolve: EntityResolver,
    ): CopilotProposal =
        when (decision) {
            is SimDecision.PerformAction -> fromAction(decision.action, promptType, seat, resolve)

            is SimDecision.SelectTargets ->
                base("target", promptType, seat).copy(
                    targets = decision.targetInstanceIds.map(resolve::resolve),
                    targetGroups = decision.targetGroups.mapKeys { (targetIdx, _) -> targetIdx.toString() },
                    responseIds = decision.targetInstanceIds,
                )

            is SimDecision.UnselectTargets ->
                base("untarget", promptType, seat).copy(
                    targets = decision.targetInstanceIds.map(resolve::resolve),
                    targetGroups = decision.targetGroups.mapKeys { (targetIdx, _) -> targetIdx.toString() },
                    responseIds = decision.targetInstanceIds,
                )

            SimDecision.SubmitTargets -> base("submit_targets", promptType, seat)

            is SimDecision.SelectN ->
                base("select_n", promptType, seat).copy(
                    targets = decision.selectedInstanceIds.map(resolve::resolve),
                    responseIds = decision.selectedInstanceIds,
                )

            is SimDecision.EffectCost ->
                base("pay_cost", promptType, seat).copy(
                    targets = decision.selectedInstanceIds.map(resolve::resolve),
                    responseIds = decision.selectedInstanceIds,
                )

            is SimDecision.AutoTapPayment -> base("auto_tap", promptType, seat).copy(ctoId = decision.solutionIndex)

            SimDecision.KeepHand -> base("keep_hand", promptType, seat)

            SimDecision.ChooseStartingPlayer -> base("choose_starting_player", promptType, seat)

            is SimDecision.ModalChoice ->
                base("modal", promptType, seat).copy(
                    ctoId = decision.ctoId,
                    modalGrpIds = decision.selectedGrpIds,
                    responseIds = decision.selectedGrpIds,
                )

            is SimDecision.ManaTypeChoices ->
                base("mana_type", promptType, seat).copy(
                    manaTypes = decision.choicesByCtoId.map { (ctoId, color) -> ManaTypeChoice(ctoId, color.name) },
                    responseIds = decision.choicesByCtoId.map { it.first },
                )

            is SimDecision.OptionalCost ->
                base("optional_cost", promptType, seat).copy(
                    ctoId = decision.ctoId,
                    responseIds = listOf(decision.ctoId),
                )

            is SimDecision.CastingTimeX ->
                base("numeric", promptType, seat).copy(
                    ctoId = decision.ctoId,
                    numericValue = decision.value,
                    responseIds = listOf(decision.value),
                )

            is SimDecision.AlternateCost ->
                base("alternate_cost", promptType, seat).copy(
                    ctoId = decision.ctoId,
                    responseIds = listOf(decision.optionIndex),
                )

            is SimDecision.NumericInput ->
                base("numeric", promptType, seat).copy(
                    numericValue = decision.value,
                    responseIds = listOf(decision.value),
                )

            is SimDecision.OptionalAction ->
                base("optional_action", promptType, seat).copy(accept = decision.accept)

            is SimDecision.DeclareAttackers ->
                base("attack", promptType, seat).copy(
                    targets = decision.attackerInstanceIds.map(resolve::resolve),
                    responseIds = decision.attackerInstanceIds,
                )

            SimDecision.DeclareAllAttackers -> base("attack_all", promptType, seat)

            is SimDecision.DeclareBlockers ->
                base("block", promptType, seat).copy(
                    blocks =
                        decision.assignments.entries.map { (blocker, attacker) ->
                            BlockAssignment(resolve.resolve(blocker), resolve.resolve(attacker))
                        },
                    responseIds = decision.assignments.keys.toList(),
                )

            SimDecision.DeclareNoBlockers -> base("block", promptType, seat)

            is SimDecision.UndeclareBlocker ->
                base("unblock", promptType, seat).copy(
                    targets = listOf(resolve.resolve(decision.blockerInstanceId)),
                    responseIds = listOf(decision.blockerInstanceId),
                )

            SimDecision.SubmitAttackers -> base("submit_attackers", promptType, seat)

            SimDecision.SubmitBlockers -> base("submit_blockers", promptType, seat)

            SimDecision.PassPriority -> base("pass", promptType, seat)

            is SimDecision.GroupTop -> base("group", promptType, seat).copy(responseIds = decision.instanceIds)

            is SimDecision.GroupAway -> base("group", promptType, seat).copy(responseIds = decision.awayInstanceIds)

            is SimDecision.AssignDamage ->
                base("assign_damage", promptType, seat).copy(responseIds = decision.assigners.map { it.instanceId })

            is SimDecision.Order -> base("order", promptType, seat).copy(responseIds = decision.orderedInstanceIds)

            is SimDecision.Distribution -> distribution(decision, promptType, seat)

            is SimDecision.Search -> base("search", promptType, seat).copy(responseIds = decision.itemsFound)

            is SimDecision.GroupedSearch -> base("search", promptType, seat).copy(responseIds = decision.itemsFound)

            // Complete replacement rows are submitted through ResponseBuilder;
            // no live-client gesture executor owns this identity-rich response.
            is SimDecision.SelectReplacement -> unrealizable(promptType, seat, "select-replacement is not an autoplay intent")

            SimDecision.CancelAction -> base("cancel", promptType, seat)

            SimDecision.RetirePrompt,
            SimDecision.WaitForEngine,
            SimDecision.Terminal,
            -> unrealizable(promptType, seat, "decision kind '${decision.kind}' has no autoplay executor yet")
        }

    private fun fromAction(
        action: Action,
        promptType: GREMessageType,
        seat: Int,
        resolve: EntityResolver,
    ): CopilotProposal {
        val intent =
            when {
                action.actionType == ActionType.Play_add3 -> "play_land"
                action.actionType == ActionType.CastAdventure -> "cast_adventure"
                action.actionType == ActionType.CastOmen -> "cast_omen"
                action.actionType == ActionType.Cast && action.alternativeGrpId != 0 -> "cast_mdfc"
                action.actionType == ActionType.Cast -> "cast"
                action.actionType == ActionType.Activate_add3 -> "activate"
                else -> "perform"
            }
        return base(intent, promptType, seat).copy(
            card = resolve.resolve(action.instanceId),
            abilityGrpId = action.abilityGrpId.takeIf { it != 0 },
            alternativeGrpId = action.alternativeGrpId.takeIf { it != 0 },
            responseIds = listOf(action.instanceId),
        )
    }

    private fun distribution(
        decision: SimDecision.Distribution,
        promptType: GREMessageType,
        seat: Int,
    ): CopilotProposal =
        base("distribute", promptType, seat).copy(
            distribution =
                decision.amountsByInstanceId.map { (instanceId, amount) ->
                    DistributionAmount(instanceId, amount)
                },
            responseIds = decision.amountsByInstanceId.keys.toList(),
        )

    private fun base(
        intent: String,
        promptType: GREMessageType,
        seat: Int,
    ): CopilotProposal = CopilotProposal(intent = intent, promptType = promptType.name, seat = seat)
}
