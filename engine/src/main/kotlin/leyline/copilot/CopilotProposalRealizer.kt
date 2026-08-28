package leyline.copilot

import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AssignDamageResp
import wotc.mtgo.gre.external.messaging.Messages.Attacker
import wotc.mtgo.gre.external.messaging.Messages.Blocker
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionResp
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsResp
import wotc.mtgo.gre.external.messaging.Messages.ChooseModalResp
import wotc.mtgo.gre.external.messaging.Messages.ChooseStartingPlayerResp
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.DamageAssigner
import wotc.mtgo.gre.external.messaging.Messages.DamageAssignment
import wotc.mtgo.gre.external.messaging.Messages.DamageRecType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.DeclareAttackersResp
import wotc.mtgo.gre.external.messaging.Messages.DeclareBlockersResp
import wotc.mtgo.gre.external.messaging.Messages.Distribution
import wotc.mtgo.gre.external.messaging.Messages.DistributionResp
import wotc.mtgo.gre.external.messaging.Messages.EffectCostResp
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.Group
import wotc.mtgo.gre.external.messaging.Messages.GroupResp
import wotc.mtgo.gre.external.messaging.Messages.GroupType
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.MulliganOption
import wotc.mtgo.gre.external.messaging.Messages.MulliganResp
import wotc.mtgo.gre.external.messaging.Messages.NumericInputResp
import wotc.mtgo.gre.external.messaging.Messages.OptionResponse
import wotc.mtgo.gre.external.messaging.Messages.OptionalResp
import wotc.mtgo.gre.external.messaging.Messages.OrderResp
import wotc.mtgo.gre.external.messaging.Messages.OrderingType
import wotc.mtgo.gre.external.messaging.Messages.PerformActionResp
import wotc.mtgo.gre.external.messaging.Messages.PerformAutoTapActionsResp
import wotc.mtgo.gre.external.messaging.Messages.SearchFromGroupsResp
import wotc.mtgo.gre.external.messaging.Messages.SearchResp
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectManaTypeResp
import wotc.mtgo.gre.external.messaging.Messages.SelectNResp
import wotc.mtgo.gre.external.messaging.Messages.SelectReplacementResp
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsResp
import wotc.mtgo.gre.external.messaging.Messages.SubZoneType
import wotc.mtgo.gre.external.messaging.Messages.Target
import wotc.mtgo.gre.external.messaging.Messages.TargetSelection
import wotc.mtgo.gre.external.messaging.Messages.TeamType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import java.util.Locale

/** Resolves an instance id to a display-ready [EntityRef]. Bridge-backed live; faked in tests. */
internal fun interface EntityResolver {
    fun resolve(instanceId: Int): EntityRef
}

/**
 * Realizes one [SimDecision] into the Copilot proposal and its ordered native
 * responses at the same seam. Unsupported decisions are explicitly
 * unrealizable and never carry deliverable bytes.
 */
internal object CopilotProposalRealizer {
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

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun realize(
        decision: SimDecision,
        promptType: GREMessageType,
        seat: Int,
        resolve: EntityResolver = EntityResolver { instanceId -> EntityRef(instanceId) },
        gsId: Int = 0,
        respId: Int = 0,
    ): CopilotProposal =
        when (decision) {
            is SimDecision.PerformAction ->
                withResponses(
                    fromAction(decision.action, promptType, seat, resolve),
                    listOf(
                        message(ClientMessageType.PerformActionResp_097b, gsId, seat, respId) {
                            setPerformActionResp(PerformActionResp.newBuilder().addActions(decision.action))
                        },
                    ),
                )

            SimDecision.PassPriority ->
                withResponses(
                    base("pass", promptType, seat),
                    listOf(
                        message(ClientMessageType.PerformActionResp_097b, gsId, seat, respId) {
                            setPerformActionResp(
                                PerformActionResp.newBuilder().addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                            )
                        },
                    ),
                )

            is SimDecision.SelectTargets ->
                withResponses(
                    base("target", promptType, seat).copy(
                        targets = decision.targetInstanceIds.map(resolve::resolve),
                        targetGroups = decision.targetGroups.mapKeys { (targetIdx, _) -> targetIdx.toString() },
                        responseIds = decision.targetInstanceIds,
                    ),
                    listOf(
                        message(ClientMessageType.SelectTargetsResp_097b, gsId, seat, respId) {
                            setSelectTargetsResp(
                                SelectTargetsResp.newBuilder().setTarget(
                                    TargetSelection.newBuilder().setTargetIdx(decision.targetIdx).apply {
                                        decision.targetInstanceIds.forEach {
                                            addTargets(
                                                Target
                                                    .newBuilder()
                                                    .setTargetInstanceId(it)
                                                    .setLegalAction(SelectAction.Select_a1ad),
                                            )
                                        }
                                    },
                                ),
                            )
                        },
                    ),
                )

            is SimDecision.UnselectTargets ->
                withResponses(
                    base("untarget", promptType, seat).copy(
                        targets = decision.targetInstanceIds.map(resolve::resolve),
                        targetGroups = decision.targetGroups.mapKeys { (targetIdx, _) -> targetIdx.toString() },
                        responseIds = decision.targetInstanceIds,
                    ),
                    listOf(
                        message(ClientMessageType.SelectTargetsResp_097b, gsId, seat, respId) {
                            setSelectTargetsResp(
                                SelectTargetsResp.newBuilder().setTarget(
                                    TargetSelection.newBuilder().setTargetIdx(decision.targetIdx).apply {
                                        decision.targetInstanceIds.forEach {
                                            addTargets(
                                                Target
                                                    .newBuilder()
                                                    .setTargetInstanceId(it)
                                                    .setLegalAction(SelectAction.Unselect),
                                            )
                                        }
                                    },
                                ),
                            )
                        },
                    ),
                )

            SimDecision.SubmitTargets ->
                withResponses(
                    base("submit_targets", promptType, seat),
                    listOf(message(ClientMessageType.SubmitTargetsReq, gsId, seat, respId)),
                )

            is SimDecision.SelectN ->
                withResponses(
                    base("select_n", promptType, seat).copy(
                        targets = decision.selectedInstanceIds.map(resolve::resolve),
                        responseIds = decision.selectedInstanceIds,
                    ),
                    listOf(
                        message(ClientMessageType.SelectNresp, gsId, seat, respId) {
                            setSelectNResp(SelectNResp.newBuilder().apply { decision.selectedInstanceIds.forEach(::addIds) })
                        },
                    ),
                )

            is SimDecision.EffectCost ->
                withResponses(
                    base("pay_cost", promptType, seat).copy(
                        targets = decision.selectedInstanceIds.map(resolve::resolve),
                        responseIds = decision.selectedInstanceIds,
                    ),
                    listOf(
                        message(ClientMessageType.EffectCostResp_097b, gsId, seat, respId) {
                            setEffectCostResp(
                                EffectCostResp.newBuilder().setCostSelection(
                                    SelectNResp.newBuilder().apply { decision.selectedInstanceIds.forEach(::addIds) },
                                ),
                            )
                        },
                    ),
                )

            is SimDecision.AutoTapPayment ->
                withResponses(
                    base("auto_tap", promptType, seat).copy(ctoId = decision.solutionIndex),
                    listOf(
                        message(ClientMessageType.PerformAutoTapActionsResp_097b, gsId, seat, respId) {
                            setPerformAutoTapActionsResp(
                                PerformAutoTapActionsResp.newBuilder().setIndex(decision.solutionIndex),
                            )
                        },
                    ),
                )

            SimDecision.KeepHand ->
                withResponses(
                    base("keep_hand", promptType, seat),
                    listOf(
                        message(ClientMessageType.MulliganResp_097b, gsId, seat, respId) {
                            setMulliganResp(MulliganResp.newBuilder().setDecision(MulliganOption.AcceptHand))
                        },
                    ),
                )

            SimDecision.ChooseStartingPlayer ->
                withResponses(
                    base("choose_starting_player", promptType, seat),
                    listOf(
                        message(ClientMessageType.ChooseStartingPlayerResp_097b, gsId, seat, respId) {
                            setChooseStartingPlayerResp(
                                ChooseStartingPlayerResp
                                    .newBuilder()
                                    .setTeamType(TeamType.Individual)
                                    .setSystemSeatId(seat)
                                    .setTeamId(seat),
                            )
                        },
                    ),
                )

            is SimDecision.ModalChoice ->
                withResponses(
                    base("modal", promptType, seat).copy(
                        ctoId = decision.ctoId,
                        modalGrpIds = decision.selectedGrpIds,
                        responseIds = decision.selectedGrpIds,
                    ),
                    listOf(
                        message(ClientMessageType.CastingTimeOptionsResp_097b, gsId, seat, respId) {
                            setCastingTimeOptionsResp(
                                CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                                    CastingTimeOptionResp
                                        .newBuilder()
                                        .setCtoId(decision.ctoId)
                                        .setCastingTimeOptionType(CastingTimeOptionType.Modal_a7b4)
                                        .setChooseModalResp(
                                            ChooseModalResp.newBuilder().apply { decision.selectedGrpIds.forEach(::addGrpIds) },
                                        ),
                                ),
                            )
                        },
                    ),
                )

            is SimDecision.ManaTypeChoices ->
                withResponses(
                    base("mana_type", promptType, seat).copy(
                        manaTypes = decision.choicesByCtoId.map { (ctoId, color) -> ManaTypeChoice(ctoId, color.name) },
                        responseIds = decision.choicesByCtoId.map { it.first },
                    ),
                    listOf(
                        message(ClientMessageType.CastingTimeOptionsResp_097b, gsId, seat, respId) {
                            setCastingTimeOptionsResp(
                                CastingTimeOptionsResp.newBuilder().apply {
                                    decision.choicesByCtoId.forEach { (ctoId, color) ->
                                        addCastingTimeOptionResps(
                                            CastingTimeOptionResp
                                                .newBuilder()
                                                .setCtoId(ctoId)
                                                .setCastingTimeOptionType(CastingTimeOptionType.ManaType)
                                                .setSelectManaTypeResp(SelectManaTypeResp.newBuilder().setManaColor(color)),
                                        )
                                    }
                                },
                            )
                        },
                    ),
                )

            is SimDecision.OptionalCost ->
                withResponses(
                    base("optional_cost", promptType, seat).copy(ctoId = decision.ctoId, responseIds = listOf(decision.ctoId)),
                    listOf(
                        message(ClientMessageType.CastingTimeOptionsResp_097b, gsId, seat, respId) {
                            setCastingTimeOptionsResp(
                                CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                                    if (decision.ctoId == 0) {
                                        CastingTimeOptionResp.newBuilder().setCastingTimeOptionType(CastingTimeOptionType.Done)
                                    } else {
                                        CastingTimeOptionResp
                                            .newBuilder()
                                            .setCtoId(decision.ctoId)
                                            .setCastingTimeOptionType(CastingTimeOptionType.Kicker)
                                    },
                                ),
                            )
                        },
                    ),
                )

            is SimDecision.CastingTimeX ->
                withResponses(
                    base(
                        "numeric",
                        promptType,
                        seat,
                    ).copy(ctoId = decision.ctoId, numericValue = decision.value, responseIds = listOf(decision.value)),
                    listOf(
                        message(ClientMessageType.CastingTimeOptionsResp_097b, gsId, seat, respId) {
                            setCastingTimeOptionsResp(
                                CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                                    CastingTimeOptionResp
                                        .newBuilder()
                                        .setCtoId(decision.ctoId)
                                        .setCastingTimeOptionType(CastingTimeOptionType.ChooseX_a7b4)
                                        .setNumericInputResp(NumericInputResp.newBuilder().setNumericInputValue(decision.value)),
                                ),
                            )
                        },
                    ),
                )

            is SimDecision.AlternateCost ->
                withResponses(
                    base("alternate_cost", promptType, seat).copy(ctoId = decision.ctoId, responseIds = listOf(decision.optionIndex)),
                    listOf(
                        message(ClientMessageType.CastingTimeOptionsResp_097b, gsId, seat, respId) {
                            setCastingTimeOptionsResp(
                                CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                                    CastingTimeOptionResp
                                        .newBuilder()
                                        .setCtoId(decision.ctoId)
                                        .setCastingTimeOptionType(CastingTimeOptionType.ChooseOrCost)
                                        .setSelectNResp(SelectNResp.newBuilder().addIds(decision.optionIndex)),
                                ),
                            )
                        },
                    ),
                )

            is SimDecision.NumericInput ->
                withResponses(
                    base("numeric", promptType, seat).copy(numericValue = decision.value, responseIds = listOf(decision.value)),
                    listOf(
                        message(ClientMessageType.NumericInputResp_097b, gsId, seat, respId) {
                            setNumericInputResp(NumericInputResp.newBuilder().setNumericInputValue(decision.value))
                        },
                    ),
                )

            is SimDecision.OptionalAction ->
                withResponses(
                    base("optional_action", promptType, seat).copy(accept = decision.accept),
                    listOf(
                        message(ClientMessageType.OptionalActionResp, gsId, seat, respId) {
                            setOptionalResp(
                                OptionalResp
                                    .newBuilder()
                                    .setResponse(if (decision.accept) OptionResponse.AllowYes else OptionResponse.CancelNo),
                            )
                        },
                    ),
                )

            is SimDecision.DeclareAttackers -> {
                val opponent =
                    DamageRecipient
                        .newBuilder()
                        .setType(DamageRecType.Player_a0e5)
                        .setPlayerSystemSeatId(if (seat == 1) 2 else 1)
                        .build()
                withResponses(
                    base("attack", promptType, seat).copy(
                        targets = decision.attackerInstanceIds.map(resolve::resolve),
                        responseIds = decision.attackerInstanceIds,
                    ),
                    listOf(
                        message(ClientMessageType.DeclareAttackersResp_097b, gsId, seat, respId) {
                            setDeclareAttackersResp(
                                DeclareAttackersResp.newBuilder().apply {
                                    decision.attackerInstanceIds.forEach {
                                        addSelectedAttackers(
                                            Attacker
                                                .newBuilder()
                                                .setAttackerInstanceId(it)
                                                .setSelectedDamageRecipient(opponent),
                                        )
                                    }
                                },
                            )
                        },
                    ),
                )
            }

            SimDecision.DeclareAllAttackers ->
                unrealizable(promptType, seat, "declare-all-attackers has no autoplay response")

            is SimDecision.DeclareBlockers ->
                withResponses(
                    base("block", promptType, seat).copy(
                        blocks =
                            decision.assignments.entries.map { (blocker, attacker) ->
                                BlockAssignment(resolve.resolve(blocker), resolve.resolve(attacker))
                            },
                        responseIds = decision.assignments.keys.toList(),
                    ),
                    listOf(
                        message(ClientMessageType.DeclareBlockersResp_097b, gsId, seat, respId) {
                            setDeclareBlockersResp(
                                DeclareBlockersResp.newBuilder().apply {
                                    decision.assignments.forEach { (blocker, attacker) ->
                                        addSelectedBlockers(
                                            Blocker.newBuilder().setBlockerInstanceId(blocker).addSelectedAttackerInstanceIds(attacker),
                                        )
                                    }
                                },
                            )
                        },
                    ),
                )

            SimDecision.DeclareNoBlockers ->
                withResponses(
                    base("block", promptType, seat),
                    listOf(message(ClientMessageType.SubmitBlockersReq, gsId, seat, respId)),
                )

            is SimDecision.UndeclareBlocker ->
                withResponses(
                    base("unblock", promptType, seat).copy(
                        targets = listOf(resolve.resolve(decision.blockerInstanceId)),
                        responseIds = listOf(decision.blockerInstanceId),
                    ),
                    listOf(
                        message(ClientMessageType.DeclareBlockersResp_097b, gsId, seat, respId) {
                            setDeclareBlockersResp(
                                DeclareBlockersResp.newBuilder().addSelectedBlockers(
                                    Blocker.newBuilder().setBlockerInstanceId(decision.blockerInstanceId),
                                ),
                            )
                        },
                    ),
                )

            SimDecision.SubmitAttackers ->
                withResponses(
                    base("submit_attackers", promptType, seat),
                    listOf(message(ClientMessageType.SubmitAttackersReq, gsId, seat, respId)),
                )

            SimDecision.SubmitBlockers ->
                withResponses(
                    base("submit_blockers", promptType, seat),
                    listOf(message(ClientMessageType.SubmitBlockersReq, gsId, seat, respId)),
                )

            is SimDecision.GroupTop ->
                withResponses(
                    base("group", promptType, seat).copy(responseIds = decision.instanceIds),
                    listOf(
                        message(ClientMessageType.GroupResp_097b, gsId, seat, respId) {
                            setGroupResp(
                                GroupResp
                                    .newBuilder()
                                    .addGroups(group(decision.instanceIds, ZoneType.Library, SubZoneType.Top))
                                    .addGroups(group(emptyList(), ZoneType.Library, SubZoneType.Bottom))
                                    .setGroupType(GroupType.Ordered),
                            )
                        },
                    ),
                )

            is SimDecision.GroupAway -> {
                val keepIds = decision.allInstanceIds.filter { it !in decision.awayInstanceIds }
                val surveil = decision.context == GroupingContext.Surveil
                withResponses(
                    base("group", promptType, seat).copy(responseIds = decision.awayInstanceIds),
                    listOf(
                        message(ClientMessageType.GroupResp_097b, gsId, seat, respId) {
                            setGroupResp(
                                GroupResp
                                    .newBuilder()
                                    .addGroups(group(keepIds, ZoneType.Library, SubZoneType.Top))
                                    .addGroups(
                                        group(
                                            decision.awayInstanceIds,
                                            if (surveil) ZoneType.Graveyard else ZoneType.Library,
                                            if (surveil) SubZoneType.None_a455 else SubZoneType.Bottom,
                                        ),
                                    ).setGroupType(GroupType.Ordered),
                            )
                        },
                    ),
                )
            }

            is SimDecision.AssignDamage ->
                withResponses(
                    base("assign_damage", promptType, seat).copy(responseIds = decision.assigners.map { it.instanceId }),
                    listOf(
                        message(ClientMessageType.AssignDamageResp_097b, gsId, seat, respId) {
                            setAssignDamageResp(
                                AssignDamageResp.newBuilder().apply {
                                    decision.assigners.forEach { assigner ->
                                        addAssigners(
                                            DamageAssigner
                                                .newBuilder()
                                                .setInstanceId(assigner.instanceId)
                                                .setTotalDamage(assigner.totalDamage)
                                                .apply {
                                                    assigner.assignments.forEach { assignment ->
                                                        addAssignments(
                                                            DamageAssignment
                                                                .newBuilder()
                                                                .setInstanceId(assignment.instanceId)
                                                                .setMinDamage(assignment.minDamage)
                                                                .setMaxDamage(assignment.maxDamage)
                                                                .setAssignedDamage(assignment.assignedDamage),
                                                        )
                                                    }
                                                },
                                        )
                                    }
                                },
                            )
                        },
                    ),
                )

            is SimDecision.Order ->
                withResponses(
                    base("order", promptType, seat).copy(responseIds = decision.orderedInstanceIds),
                    listOf(
                        message(ClientMessageType.OrderResp_097b, gsId, seat, respId) {
                            setOrderResp(
                                OrderResp
                                    .newBuilder()
                                    .addAllIds(decision.orderedInstanceIds)
                                    .setOrdering(OrderingType.OrderAsIndicated),
                            )
                        },
                    ),
                )

            is SimDecision.Distribution ->
                withResponses(
                    base("distribute", promptType, seat).copy(
                        distribution =
                            decision.amountsByInstanceId.map { (instanceId, amount) -> DistributionAmount(instanceId, amount) },
                        responseIds = decision.amountsByInstanceId.keys.toList(),
                    ),
                    listOf(
                        message(ClientMessageType.DistributionResp_097b, gsId, seat, respId) {
                            setDistributionResp(
                                DistributionResp.newBuilder().apply {
                                    decision.amountsByInstanceId.forEach { (instanceId, amount) ->
                                        addDistributions(Distribution.newBuilder().setInstanceId(instanceId).setAmount(amount))
                                    }
                                },
                            )
                        },
                    ),
                )

            is SimDecision.Search ->
                withResponses(
                    base("search", promptType, seat).copy(responseIds = decision.itemsFound),
                    listOf(
                        message(ClientMessageType.SearchResp_097b, gsId, seat, respId) {
                            setSearchResp(SearchResp.newBuilder().addAllItemsFound(decision.itemsFound))
                        },
                    ),
                )

            is SimDecision.GroupedSearch ->
                withResponses(
                    base("search", promptType, seat).copy(responseIds = decision.itemsFound),
                    listOf(
                        message(ClientMessageType.SearchFromGroupsResp_097b, gsId, seat, respId) {
                            setSearchFromGroupsResp(
                                SearchFromGroupsResp.newBuilder().addGroups(
                                    Group
                                        .newBuilder()
                                        .setGroupId(decision.groupId)
                                        .setMaxSelect(decision.maxSelect)
                                        .addAllIds(decision.itemsFound),
                                ),
                            )
                        },
                    ),
                )

            is SimDecision.SelectReplacement ->
                withResponses(
                    base("select_replacement", promptType, seat).copy(
                        replacementEffectId = decision.replacement.replacementEffectId.takeIf { it != 0 },
                    ),
                    listOf(
                        message(ClientMessageType.SelectReplacementResp_097b, gsId, seat, respId) {
                            setSelectReplacementResp(
                                SelectReplacementResp.newBuilder().setReplacement(decision.replacement),
                            )
                        },
                    ),
                )

            SimDecision.CancelAction ->
                withResponses(
                    base("cancel", promptType, seat),
                    listOf(message(ClientMessageType.CancelActionReq_097b, gsId, seat, respId)),
                )

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

    private fun base(
        intent: String,
        promptType: wotc.mtgo.gre.external.messaging.Messages.GREMessageType,
        seat: Int,
    ): CopilotProposal = CopilotProposal(intent = intent, promptType = promptType.name, seat = seat)

    private fun withResponses(
        proposal: CopilotProposal,
        responses: List<ClientToGREMessage>,
    ): CopilotProposal = proposal.copy(responses = hexMessages(responses))

    private fun message(
        type: ClientMessageType,
        gsId: Int,
        seatId: Int,
        respId: Int,
        configure: ClientToGREMessage.Builder.() -> Unit = {},
    ): ClientToGREMessage =
        ClientToGREMessage
            .newBuilder()
            .setType(type)
            .setGameStateId(gsId)
            .setRespId(respId)
            .setSystemSeatId(seatId)
            .apply(configure)
            .build()

    private fun group(
        ids: List<Int>,
        zone: ZoneType,
        subZone: SubZoneType,
    ) = Group
        .newBuilder()
        .addAllIds(ids)
        .setZoneType(zone)
        .setSubZoneType(subZone)

    /** Hex-encoded messages, preserved in protocol delivery order. */
    private fun hexMessages(msgs: List<ClientToGREMessage>): List<String> =
        msgs.map { msg -> msg.toByteArray().joinToString("") { "%02x".format(Locale.ROOT, it) } }
}
