package leyline.tooling.simclient

import leyline.copilot.SimDecision
import leyline.tooling.headless.MatchFlowHarness
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext

internal sealed interface SimPromptResponseValue {
    val kind: String

    data class Decision(
        val decision: SimDecision,
    ) : SimPromptResponseValue {
        override val kind: String get() = decision.kind
    }

    data object RetirePrompt : SimPromptResponseValue {
        override val kind: String = "retire-prompt"
    }

    data object WaitForEngine : SimPromptResponseValue {
        override val kind: String = "wait-for-engine"
    }

    data object Terminal : SimPromptResponseValue {
        override val kind: String = "terminal"
    }
}

internal fun SimPromptResponseValue.auditDigest(prompt: ActivePrompt? = null): String =
    when (this) {
        is SimPromptResponseValue.Decision -> decision.auditDigest(prompt)
        SimPromptResponseValue.RetirePrompt,
        SimPromptResponseValue.WaitForEngine,
        SimPromptResponseValue.Terminal,
        -> kind
    }

internal fun SimDecision.auditDigest(prompt: ActivePrompt? = null): String =
    when (this) {
        is SimDecision.PerformAction ->
            listOf(
                "perform:${action.actionType.name}",
                "iid=${action.instanceId}",
                "grp=${action.grpId}",
                "ability=${action.abilityGrpId}",
                "alt=${action.alternativeGrpId}",
            ).joinToString(":")
        is SimDecision.SelectTargets ->
            "select-targets:" +
                targetGroups.entries
                    .sortedBy { it.key }
                    .joinToString("+") { (idx, ids) ->
                        "$idx=${ids.sorted().joinToString(",")}"
                    }
        is SimDecision.UnselectTargets -> "unselect-targets:${targetInstanceIds.sorted().joinToString("+")}"
        SimDecision.SubmitTargets -> "submit-targets"
        is SimDecision.SelectN -> "select-n:${selectedInstanceIds.sorted().joinToString("+")}"
        is SimDecision.Order -> "order:${orderedInstanceIds.joinToString("+")}"
        is SimDecision.Search -> "search:${itemsFound.sorted().joinToString("+")}"
        is SimDecision.GroupedSearch -> "grouped-search:$groupId:${itemsFound.sorted().joinToString("+")}"
        is SimDecision.SelectReplacement ->
            "select-replacement:${replacement.affectedObject}:${replacement.abilityGrpId}:${replacement.replacementEffectId}"
        is SimDecision.EffectCost -> "effect-cost:${selectedInstanceIds.sorted().joinToString("+")}"
        is SimDecision.AutoTapPayment -> "auto-tap-payment:$solutionIndex"
        SimDecision.KeepHand -> "keep-hand"
        is SimDecision.GroupTop -> "group-top:${instanceIds.joinToString("+")}"
        is SimDecision.GroupAway -> "group-away:${awayInstanceIds.sorted().joinToString("+")}:context=${context.name}"
        is SimDecision.OptionalAction -> "optional-action:${if (accept) "yes" else "no"}"
        is SimDecision.OptionalCost -> "optional-cost:$ctoId"
        is SimDecision.CastingTimeX -> "casting-time-x:$ctoId=$value"
        is SimDecision.AlternateCost -> "alternate-cost:$ctoId/$optionIndex"
        is SimDecision.ModalChoice -> "modal-choice:${selectedGrpIds.sorted().joinToString("+")}"
        is SimDecision.ManaTypeChoices -> "mana-type:${choicesByCtoId.joinToString("+") { (ctoId, color) -> "$ctoId=$color" }}"
        is SimDecision.NumericInput -> "numeric-input:$value"
        is SimDecision.Distribution ->
            "distribution:${amountsByInstanceId.entries.joinToString("+") { "${it.key}=${it.value}" }}"
        is SimDecision.AssignDamage -> {
            val assignmentDigest =
                assigners
                    .sortedBy { it.instanceId }
                    .joinToString("+") { assigner ->
                        "${assigner.instanceId}=${assigner.assignments.sortedBy { it.instanceId }}"
                    }
            "assign-damage:$assignmentDigest"
        }
        SimDecision.DeclareAllAttackers -> {
            val attackerIds =
                prompt
                    ?.msg
                    ?.declareAttackersReq
                    ?.attackersList
                    .orEmpty()
                    .map { it.attackerInstanceId }
                    .distinct()
                    .sorted()
                    .joinToString("+")
            "declare-attackers:$attackerIds"
        }
        is SimDecision.DeclareAttackers -> "declare-attackers:${attackerInstanceIds.sorted().joinToString("+")}"
        is SimDecision.DeclareBlockers -> {
            val assignmentDigest =
                assignments.entries
                    .sortedBy { it.key }
                    .joinToString("+") { "${it.key}->${it.value}" }
            "declare-blockers:$assignmentDigest"
        }
        SimDecision.DeclareNoBlockers -> "declare-blockers:"
        is SimDecision.UndeclareBlocker -> "undeclare-blocker:$blockerInstanceId"
        SimDecision.SubmitAttackers -> "submit-attackers"
        SimDecision.SubmitBlockers -> "submit-blockers"
        SimDecision.CancelAction -> "cancel-action"
        SimDecision.PassPriority -> "pass-priority"
    }

internal enum class SimSubmitResult {
    Submitted,
    NoPending,
    NotSubmitted,
}

internal data class SimPromptResponse(
    val value: SimPromptResponseValue,
    val markHandled: Boolean = true,
    val markAllHandledOfType: GREMessageType? = null,
    val aarActionFingerprint: String? = null,
) {
    constructor(
        decision: SimDecision,
        markHandled: Boolean = true,
        markAllHandledOfType: GREMessageType? = null,
        aarActionFingerprint: String? = null,
    ) : this(
        value = SimPromptResponseValue.Decision(decision),
        markHandled = markHandled,
        markAllHandledOfType = markAllHandledOfType,
        aarActionFingerprint = aarActionFingerprint,
    )
}

internal class SimDecisionSubmitter(
    private val harness: MatchFlowHarness,
) {
    fun submit(value: SimPromptResponseValue): SimSubmitResult =
        when (value) {
            is SimPromptResponseValue.Decision -> submitDecision(value.decision)
            SimPromptResponseValue.RetirePrompt,
            SimPromptResponseValue.WaitForEngine,
            SimPromptResponseValue.Terminal,
            -> SimSubmitResult.NotSubmitted
        }

    private fun submitDecision(decision: SimDecision): SimSubmitResult =
        when (decision) {
            is SimDecision.PerformAction -> submitPerformAction(decision.action)
            is SimDecision.SelectTargets -> submitted { harness.selectTargets(decision.targetGroups) }
            SimDecision.SubmitTargets -> submitted { harness.submitTargets() }
            // Consult/live-client path only; simclient uses full-list SelectTargets.
            is SimDecision.UnselectTargets -> SimSubmitResult.NotSubmitted
            is SimDecision.SelectN -> submitted { harness.respondToSelectN(decision.selectedInstanceIds) }
            is SimDecision.Order -> submitted { harness.respondToOrder(decision.orderedInstanceIds) }
            is SimDecision.Distribution -> submitted { harness.respondToDistribution(decision.amountsByInstanceId.toList()) }
            is SimDecision.Search -> submitted { harness.respondToSearch(decision.itemsFound) }
            is SimDecision.GroupedSearch ->
                submitted {
                    harness.respondToGroupedSearch(
                        decision.groupId,
                        decision.itemsFound,
                        decision.maxSelect,
                    )
                }
            is SimDecision.SelectReplacement -> submitted { harness.respondToSelectReplacement(decision.replacement) }
            is SimDecision.EffectCost -> submitted { harness.respondToEffectCost(decision.selectedInstanceIds) }
            // Consult/live-client path only; leyline's own server auto-resolves mana.
            is SimDecision.AutoTapPayment -> SimSubmitResult.NotSubmitted
            // Consult/live-client path only; scripted puzzles skip the mulligan.
            SimDecision.KeepHand -> SimSubmitResult.NotSubmitted
            is SimDecision.GroupTop ->
                submitted {
                    harness.respondToScry(
                        bottomInstanceIds = emptyList(),
                        allInstanceIds = decision.instanceIds,
                    )
                }
            is SimDecision.GroupAway ->
                submitted {
                    if (decision.context == GroupingContext.Surveil) {
                        harness.respondToGroupReq(decision.awayInstanceIds, decision.allInstanceIds)
                    } else {
                        harness.respondToScry(decision.awayInstanceIds, decision.allInstanceIds)
                    }
                }
            is SimDecision.OptionalAction -> submitted { harness.respondToOptionalAction(decision.accept) }
            is SimDecision.AlternateCost ->
                submitted { harness.respondToAlternateCost(decision.ctoId, decision.optionIndex) }
            is SimDecision.OptionalCost -> {
                runCatching { harness.respondToOptionalCost(decision.ctoId) }
                    .onFailure {
                        log.warn(
                            "respondCastingTimeOptions: ctoId={} failed ({}), falling back to passPriority",
                            decision.ctoId,
                            it::class.simpleName,
                        )
                        harness.passPriority()
                    }
                SimSubmitResult.Submitted
            }
            is SimDecision.CastingTimeX -> submitted { harness.respondToCastingTimeX(decision.ctoId, decision.value) }
            is SimDecision.ModalChoice -> submitted { harness.respondModalChoice(decision.selectedGrpIds) }
            is SimDecision.ManaTypeChoices -> submitted { harness.respondToManaTypeChoices(decision.choicesByCtoId) }
            is SimDecision.NumericInput -> submitted { harness.respondToNumericInput(decision.value) }
            is SimDecision.AssignDamage ->
                submitted {
                    harness.assignDamage(
                        decision.assigners.map { assigner ->
                            assigner.instanceId to
                                assigner.assignments.map { assignment ->
                                    assignment.instanceId to assignment.assignedDamage
                                }
                        },
                    )
                }
            SimDecision.DeclareAllAttackers ->
                submitted {
                    harness.declareAllAttackers()
                    harness.submitAttackers()
                }
            is SimDecision.DeclareAttackers -> submitted { harness.declareAttackers(decision.attackerInstanceIds) }
            is SimDecision.DeclareBlockers -> submitted { harness.declareBlockers(decision.assignments) }
            SimDecision.DeclareNoBlockers -> submitted { harness.declareNoBlockers() }
            SimDecision.SubmitAttackers -> submitted { harness.submitAttackers() }
            SimDecision.SubmitBlockers -> submitted { harness.submitBlockers() }
            // Consult-path only; the harness two-round-trip never emits it.
            is SimDecision.UndeclareBlocker -> SimSubmitResult.NotSubmitted
            SimDecision.CancelAction -> submitted { harness.cancelAction() }
            SimDecision.PassPriority ->
                if (harness.hasPendingAction()) {
                    submitted { harness.passPriority() }
                } else {
                    SimSubmitResult.NoPending
                }
        }

    private fun submitPerformAction(action: Action): SimSubmitResult {
        if (!harness.hasPendingAction()) return SimSubmitResult.NoPending
        harness.submitAction(action)
        return SimSubmitResult.Submitted
    }

    private fun submitted(block: () -> Unit): SimSubmitResult {
        block()
        return SimSubmitResult.Submitted
    }

    private companion object {
        val log = LoggerFactory.getLogger(SimDecisionSubmitter::class.java)
    }
}
