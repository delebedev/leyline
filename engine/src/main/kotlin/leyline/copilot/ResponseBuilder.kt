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
import wotc.mtgo.gre.external.messaging.Messages.SearchResp
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectManaTypeResp
import wotc.mtgo.gre.external.messaging.Messages.SelectNResp
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsResp
import wotc.mtgo.gre.external.messaging.Messages.SubZoneType
import wotc.mtgo.gre.external.messaging.Messages.Target
import wotc.mtgo.gre.external.messaging.Messages.TargetSelection
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import java.util.Locale

/**
 * Builds the exact `ClientToGREMessage`s the client should send in answer to a
 * pending prompt, so the bridge can inject the bytes directly (Strategy A) —
 * no per-response-type client injection code. [gsId] must be the pending
 * prompt's `gameStateId`; the engine rejects a mismatched-gsId response.
 *
 * [respId] echoes the prompt's `msgId` into the response's `respId` field —
 * the correlation id the client normally supplies. A stricter host validates
 * it (rejecting a zero/stale value); the local engine ignores it, so stamping
 * it is safe on both paths.
 *
 * Combat is two-phase over two genuine round-trips: an iterative
 * `DeclareAttackers/BlockersResp` (one toggle) answers the current prompt; the
 * engine echoes a fresh prompt reflecting the committed set; a `Submit…Req`
 * answers *that* re-prompt with its msgId/gsId. Each `build` call therefore
 * answers exactly the pending prompt — the caller re-consults on every echo
 * until the committed set matches, then submits ([SimDecision.SubmitAttackers]
 * / [SimDecision.SubmitBlockers]).
 */
internal object ResponseBuilder {
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ElseCaseInsteadOfExhaustiveWhen")
    fun build(
        decision: SimDecision,
        gsId: Int,
        seatId: Int,
        respId: Int = 0,
    ): List<ClientToGREMessage> {
        fun base(type: ClientMessageType) =
            ClientToGREMessage
                .newBuilder()
                .setType(type)
                .setGameStateId(gsId)
                .setRespId(respId)
                .setSystemSeatId(seatId)

        return when (decision) {
            is SimDecision.PerformAction ->
                listOf(
                    base(ClientMessageType.PerformActionResp_097b)
                        .setPerformActionResp(PerformActionResp.newBuilder().addActions(decision.action))
                        .build(),
                )

            SimDecision.PassPriority ->
                listOf(
                    base(ClientMessageType.PerformActionResp_097b)
                        .setPerformActionResp(
                            PerformActionResp.newBuilder().addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                        ).build(),
                )

            is SimDecision.SelectTargets ->
                listOf(
                    base(ClientMessageType.SelectTargetsResp_097b)
                        .setSelectTargetsResp(
                            SelectTargetsResp.newBuilder().setTarget(
                                TargetSelection.newBuilder().setTargetIdx(decision.targetIdx).apply {
                                    decision.targetInstanceIds.forEach {
                                        addTargets(Target.newBuilder().setTargetInstanceId(it).setLegalAction(SelectAction.Select_a1ad))
                                    }
                                },
                            ),
                        ).build(),
                )

            // Un-toggle committed targets (Unselect taps) during iterative
            // target declaration; the caller re-consults after the echo.
            is SimDecision.UnselectTargets ->
                listOf(
                    base(ClientMessageType.SelectTargetsResp_097b)
                        .setSelectTargetsResp(
                            SelectTargetsResp.newBuilder().setTarget(
                                TargetSelection.newBuilder().setTargetIdx(decision.targetIdx).apply {
                                    decision.targetInstanceIds.forEach {
                                        addTargets(Target.newBuilder().setTargetInstanceId(it).setLegalAction(SelectAction.Unselect))
                                    }
                                },
                            ),
                        ).build(),
                )

            // Finalize target declaration — answers the pending (re-)prompt.
            SimDecision.SubmitTargets -> listOf(base(ClientMessageType.SubmitTargetsReq).build())

            // Selection toggle (XOR) — the engine echoes a fresh prompt; the
            // caller re-consults and eventually submits against the re-prompt.
            // Each attacker carries selectedDamageRecipient (the opponent
            // player): the host only commits an
            // attacker that names who it attacks — without it the attack never
            // registers and the prompt re-echoes forever.
            is SimDecision.DeclareAttackers -> {
                val opponent =
                    DamageRecipient
                        .newBuilder()
                        .setType(DamageRecType.Player_a0e5)
                        .setPlayerSystemSeatId(if (seatId == 1) 2 else 1)
                        .build()
                listOf(
                    base(ClientMessageType.DeclareAttackersResp_097b)
                        .setDeclareAttackersResp(
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
                        ).build(),
                )
            }

            // Blocker assignment toggle — same echo/re-consult contract.
            is SimDecision.DeclareBlockers ->
                listOf(
                    base(ClientMessageType.DeclareBlockersResp_097b)
                        .setDeclareBlockersResp(
                            DeclareBlockersResp.newBuilder().apply {
                                decision.assignments.forEach { (blocker, attacker) ->
                                    addSelectedBlockers(
                                        Blocker.newBuilder().setBlockerInstanceId(blocker).addSelectedAttackerInstanceIds(attacker),
                                    )
                                }
                            },
                        ).build(),
                )

            // Un-toggle: entry with no selectedAttackerInstanceIds unassigns.
            is SimDecision.UndeclareBlocker ->
                listOf(
                    base(ClientMessageType.DeclareBlockersResp_097b)
                        .setDeclareBlockersResp(
                            DeclareBlockersResp.newBuilder().addSelectedBlockers(
                                Blocker.newBuilder().setBlockerInstanceId(decision.blockerInstanceId),
                            ),
                        ).build(),
                )

            // Finalize — answers the pending (re-)prompt with its msgId/gsId.
            SimDecision.SubmitAttackers -> listOf(base(ClientMessageType.SubmitAttackersReq).build())

            SimDecision.SubmitBlockers -> listOf(base(ClientMessageType.SubmitBlockersReq).build())

            // No blocks: submit an empty selection against the current prompt.
            SimDecision.DeclareNoBlockers -> listOf(base(ClientMessageType.SubmitBlockersReq).build())

            // Scry/surveil "keep everything on top" — always legal, moves nothing.
            is SimDecision.GroupTop ->
                listOf(
                    base(ClientMessageType.GroupResp_097b)
                        .setGroupResp(
                            GroupResp
                                .newBuilder()
                                .addGroups(group(decision.instanceIds, ZoneType.Library, SubZoneType.Top))
                                .addGroups(group(emptyList(), ZoneType.Library, SubZoneType.Bottom))
                                .setGroupType(GroupType.Ordered),
                        ).build(),
                )

            // Scry: away = bottom of library. Surveil: away = graveyard.
            is SimDecision.GroupAway -> {
                val keepIds = decision.allInstanceIds.filter { it !in decision.awayInstanceIds }
                val surveil = decision.context == GroupingContext.Surveil
                listOf(
                    base(ClientMessageType.GroupResp_097b)
                        .setGroupResp(
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
                        ).build(),
                )
            }

            is SimDecision.OptionalAction ->
                listOf(
                    base(ClientMessageType.OptionalActionResp)
                        .setOptionalResp(
                            OptionalResp
                                .newBuilder()
                                .setResponse(if (decision.accept) OptionResponse.AllowYes else OptionResponse.CancelNo),
                        ).build(),
                )

            is SimDecision.SelectN ->
                listOf(
                    base(ClientMessageType.SelectNresp)
                        .setSelectNResp(SelectNResp.newBuilder().apply { decision.selectedInstanceIds.forEach { addIds(it) } })
                        .build(),
                )

            is SimDecision.EffectCost ->
                listOf(
                    base(ClientMessageType.EffectCostResp_097b)
                        .setEffectCostResp(
                            EffectCostResp.newBuilder().setCostSelection(
                                SelectNResp.newBuilder().apply { decision.selectedInstanceIds.forEach { addIds(it) } },
                            ),
                        ).build(),
                )

            // Keep the opening hand.
            SimDecision.KeepHand ->
                listOf(
                    base(ClientMessageType.MulliganResp_097b)
                        .setMulliganResp(MulliganResp.newBuilder().setDecision(MulliganOption.AcceptHand))
                        .build(),
                )

            // Confirm an offered auto-tap mana-payment solution by index — the
            // native answer to the "Auto-Pay to confirm" PayCostsReq the client
            // raises when a nonbasic manabase re-solves the tap.
            is SimDecision.AutoTapPayment ->
                listOf(
                    base(ClientMessageType.PerformAutoTapActionsResp_097b)
                        .setPerformAutoTapActionsResp(
                            PerformAutoTapActionsResp.newBuilder().setIndex(decision.solutionIndex),
                        ).build(),
                )

            is SimDecision.Order ->
                listOf(
                    base(ClientMessageType.OrderResp_097b)
                        .setOrderResp(
                            OrderResp
                                .newBuilder()
                                .addAllIds(decision.orderedInstanceIds)
                                .setOrdering(OrderingType.OrderAsIndicated),
                        ).build(),
                )

            is SimDecision.Search ->
                listOf(
                    base(ClientMessageType.SearchResp_097b)
                        .setSearchResp(SearchResp.newBuilder().addAllItemsFound(decision.itemsFound))
                        .build(),
                )

            // Modal "choose one/two" — CastingTimeOptionsReq with the picked grpIds.
            is SimDecision.ModalChoice ->
                listOf(
                    base(ClientMessageType.CastingTimeOptionsResp_097b)
                        .setCastingTimeOptionsResp(
                            CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                                CastingTimeOptionResp
                                    .newBuilder()
                                    .setCtoId(decision.ctoId)
                                    .setCastingTimeOptionType(CastingTimeOptionType.Modal_a7b4)
                                    .setChooseModalResp(
                                        ChooseModalResp.newBuilder().apply { decision.selectedGrpIds.forEach { addGrpIds(it) } },
                                    ),
                            ),
                        ).build(),
                )

            // Required alternate additional cost: the branch rides the inner
            // selectNResp id, not the ctoId.
            is SimDecision.AlternateCost ->
                listOf(
                    base(ClientMessageType.CastingTimeOptionsResp_097b)
                        .setCastingTimeOptionsResp(
                            CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                                CastingTimeOptionResp
                                    .newBuilder()
                                    .setCtoId(decision.ctoId)
                                    .setCastingTimeOptionType(CastingTimeOptionType.ChooseOrCost)
                                    .setSelectNResp(SelectNResp.newBuilder().addIds(decision.optionIndex)),
                            ),
                        ).build(),
                )

            // Optional cost (kicker/buyback): ctoId>0 pays it, 0 declines.
            // Casting-time optional cost. Declining (ctoId 0) must send the
            // required Done option by TYPE (no ctoId) — a strict host finalizes
            // only on Done and re-echoes a bare ctoId=0 forever. Accepting
            // carries the option's ctoId plus its Kicker type.
            is SimDecision.OptionalCost ->
                listOf(
                    base(ClientMessageType.CastingTimeOptionsResp_097b)
                        .setCastingTimeOptionsResp(
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
                        ).build(),
                )

            is SimDecision.CastingTimeX ->
                listOf(
                    base(ClientMessageType.CastingTimeOptionsResp_097b)
                        .setCastingTimeOptionsResp(
                            CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                                CastingTimeOptionResp
                                    .newBuilder()
                                    .setCtoId(decision.ctoId)
                                    .setCastingTimeOptionType(CastingTimeOptionType.ChooseX_a7b4)
                                    .setNumericInputResp(
                                        NumericInputResp.newBuilder().setNumericInputValue(decision.value),
                                    ),
                            ),
                        ).build(),
                )

            is SimDecision.ManaTypeChoices ->
                listOf(
                    base(ClientMessageType.CastingTimeOptionsResp_097b)
                        .setCastingTimeOptionsResp(
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
                        ).build(),
                )

            is SimDecision.NumericInput ->
                listOf(
                    base(ClientMessageType.NumericInputResp_097b)
                        .setNumericInputResp(NumericInputResp.newBuilder().setNumericInputValue(decision.value))
                        .build(),
                )

            is SimDecision.Distribution ->
                listOf(
                    base(ClientMessageType.DistributionResp_097b)
                        .setDistributionResp(
                            DistributionResp.newBuilder().apply {
                                decision.amountsByInstanceId.forEach { (instanceId, amount) ->
                                    addDistributions(Distribution.newBuilder().setInstanceId(instanceId).setAmount(amount))
                                }
                            },
                        ).build(),
                )

            // Combat damage assignment: per attacker, the (target, damage) pairs
            // (usually the engine's pre-filled lethal-to-blocker / overflow-to-player).
            is SimDecision.AssignDamage ->
                listOf(
                    base(ClientMessageType.AssignDamageResp_097b)
                        .setAssignDamageResp(
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
                        ).build(),
                )

            // Back out of an in-flight action (cost payment / targeting) the
            // copilot cannot complete, unwinding to a priority window instead of
            // leaving the game-loop parked. No payload — the type triggers the
            // host's cancel path (PayCostsInteractionHandler / TargetingHandler).
            SimDecision.CancelAction -> listOf(base(ClientMessageType.CancelActionReq_097b).build())

            else -> emptyList()
        }
    }

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
    fun hexMessages(msgs: List<ClientToGREMessage>): List<String> =
        msgs.map { msg -> msg.toByteArray().joinToString("") { "%02x".format(Locale.ROOT, it) } }
}
