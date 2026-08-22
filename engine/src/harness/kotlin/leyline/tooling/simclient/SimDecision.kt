package leyline.tooling.simclient

import leyline.copilot.SimDecision
import leyline.tooling.headless.ActionKind
import leyline.tooling.headless.ActionSelection
import leyline.tooling.headless.CombatAction
import leyline.tooling.headless.ControlAction
import leyline.tooling.headless.HeadlessMatch
import leyline.tooling.headless.MatchIntent
import leyline.tooling.headless.PlayAction
import leyline.tooling.headless.PromptResponse
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

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
        is SimDecision.SelectTargets -> "select-targets:${targetInstanceIds.sorted().joinToString("+")}"
        is SimDecision.UnselectTargets -> "unselect-targets:${targetInstanceIds.sorted().joinToString("+")}"
        SimDecision.SubmitTargets -> "submit-targets"
        is SimDecision.SelectN -> "select-n:${selectedInstanceIds.sorted().joinToString("+")}"
        is SimDecision.Order -> "order:${orderedInstanceIds.joinToString("+")}"
        is SimDecision.Search -> "search:${itemsFound.sorted().joinToString("+")}"
        is SimDecision.EffectCost -> "effect-cost:${selectedInstanceIds.sorted().joinToString("+")}"
        is SimDecision.AutoTapPayment -> "auto-tap-payment:$solutionIndex"
        SimDecision.KeepHand -> "keep-hand"
        is SimDecision.GroupTop -> "group-top:${instanceIds.joinToString("+")}"
        is SimDecision.GroupAway -> "group-away:${awayInstanceIds.sorted().joinToString("+")}:context=${context.name}"
        is SimDecision.OptionalAction -> "optional-action:${if (accept) "yes" else "no"}"
        is SimDecision.OptionalCost -> "optional-cost:$ctoId"
        is SimDecision.AlternateCost -> "alternate-cost:$ctoId/$optionIndex"
        is SimDecision.ModalChoice -> "modal-choice:${selectedGrpIds.sorted().joinToString("+")}"
        is SimDecision.ManaTypeChoices -> "mana-type:${choicesByCtoId.joinToString("+") { (ctoId, color) -> "$ctoId=$color" }}"
        is SimDecision.NumericInput -> "numeric-input:$value"
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
        SimDecision.RetirePrompt -> "retire-prompt"
        SimDecision.WaitForEngine -> "wait-for-engine"
        SimDecision.Terminal -> "terminal"
    }

internal enum class SimSubmitResult {
    Submitted,
    NoPending,
    NotSubmitted,
}

internal data class SimPromptResponse(
    val decision: SimDecision,
    val markHandled: Boolean = true,
    val markAllHandledOfType: GREMessageType? = null,
    val aarActionFingerprint: String? = null,
)

internal class SimDecisionSubmitter(
    private val harness: HeadlessMatch,
) {
    fun submit(decision: SimDecision): SimSubmitResult =
        when (decision) {
            is SimDecision.PerformAction -> submitPerformAction(decision.action)
            is SimDecision.SelectTargets ->
                submitted {
                    harness.submit(
                        MatchIntent.Prompt(PromptResponse.Targets(decision.targetInstanceIds)),
                    )
                }
            SimDecision.SubmitTargets -> submitted { harness.submit(MatchIntent.Prompt(PromptResponse.SubmitTargets)) }
            // Consult/live-client path only; simclient uses full-list SelectTargets.
            is SimDecision.UnselectTargets -> SimSubmitResult.NotSubmitted
            is SimDecision.SelectN -> submitted { harness.submit(MatchIntent.Prompt(PromptResponse.SelectN(decision.selectedInstanceIds))) }
            is SimDecision.Order -> submitted { harness.submit(MatchIntent.Prompt(PromptResponse.Order(decision.orderedInstanceIds))) }
            is SimDecision.Search -> submitted { harness.submit(MatchIntent.Prompt(PromptResponse.Search(decision.itemsFound))) }
            is SimDecision.EffectCost ->
                submitted {
                    harness.submit(
                        MatchIntent.Prompt(PromptResponse.EffectCost(decision.selectedInstanceIds)),
                    )
                }
            // Consult/live-client path only; leyline's own server auto-resolves mana.
            is SimDecision.AutoTapPayment -> SimSubmitResult.NotSubmitted
            // Consult/live-client path only; scripted puzzles skip the mulligan.
            SimDecision.KeepHand -> SimSubmitResult.NotSubmitted
            is SimDecision.GroupTop ->
                submitted {
                    harness.submit(MatchIntent.Prompt(PromptResponse.Scry(emptyList(), decision.instanceIds)))
                }
            is SimDecision.GroupAway ->
                submitted {
                    if (decision.context == GroupingContext.Surveil) {
                        harness.submit(MatchIntent.Prompt(PromptResponse.Group(decision.awayInstanceIds, decision.allInstanceIds)))
                    } else {
                        harness.submit(MatchIntent.Prompt(PromptResponse.Scry(decision.awayInstanceIds, decision.allInstanceIds)))
                    }
                }
            is SimDecision.OptionalAction ->
                submitted {
                    harness.submit(
                        MatchIntent.Prompt(PromptResponse.OptionalAction(decision.accept)),
                    )
                }
            is SimDecision.AlternateCost ->
                submitted { harness.submit(MatchIntent.Prompt(PromptResponse.AlternateCost(decision.ctoId, decision.optionIndex))) }
            is SimDecision.OptionalCost -> {
                runCatching { harness.submit(MatchIntent.Prompt(PromptResponse.OptionalCost(decision.ctoId))) }
                    .onFailure {
                        log.warn(
                            "respondCastingTimeOptions: ctoId={} failed ({}), falling back to passPriority",
                            decision.ctoId,
                            it::class.simpleName,
                        )
                        harness.submit(MatchIntent.Control(ControlAction.PassPriority))
                    }
                SimSubmitResult.Submitted
            }
            is SimDecision.ModalChoice ->
                submitted {
                    harness.submit(
                        MatchIntent.Prompt(PromptResponse.ModalChoice(decision.selectedGrpIds)),
                    )
                }
            is SimDecision.ManaTypeChoices ->
                submitted {
                    harness.submit(
                        MatchIntent.Prompt(
                            PromptResponse.ManaTypeChoices(
                                decision.choicesByCtoId.map {
                                    it.first to
                                        it.second.toSemanticColor()
                                },
                            ),
                        ),
                    )
                }
            is SimDecision.NumericInput -> submitted { harness.submit(MatchIntent.Prompt(PromptResponse.NumericInput(decision.value))) }
            is SimDecision.AssignDamage ->
                submitted {
                    harness.submit(
                        MatchIntent.Combat(
                            CombatAction.DamageAssignment(
                                decision.assigners.map { assigner ->
                                    assigner.instanceId to
                                        assigner.assignments.map { assignment ->
                                            assignment.instanceId to assignment.assignedDamage
                                        }
                                },
                            ),
                        ),
                    )
                }
            SimDecision.DeclareAllAttackers ->
                submitted {
                    harness.submit(MatchIntent.Combat(CombatAction.AllAttackers))
                    harness.submit(MatchIntent.Combat(CombatAction.SubmitAttackers))
                }
            is SimDecision.DeclareAttackers ->
                submitted {
                    harness.submit(
                        MatchIntent.Combat(CombatAction.Attackers(decision.attackerInstanceIds)),
                    )
                }
            is SimDecision.DeclareBlockers -> submitted { harness.submit(MatchIntent.Combat(CombatAction.Blockers(decision.assignments))) }
            SimDecision.DeclareNoBlockers -> submitted { harness.submit(MatchIntent.Combat(CombatAction.NoBlockers)) }
            SimDecision.SubmitAttackers -> submitted { harness.submit(MatchIntent.Combat(CombatAction.SubmitAttackers)) }
            SimDecision.SubmitBlockers -> submitted { harness.submit(MatchIntent.Combat(CombatAction.SubmitBlockers)) }
            // Consult-path only; the harness two-round-trip never emits it.
            is SimDecision.UndeclareBlocker -> SimSubmitResult.NotSubmitted
            SimDecision.CancelAction -> submitted { harness.submit(MatchIntent.Prompt(PromptResponse.Cancel)) }
            SimDecision.PassPriority ->
                if (harness.observe().pendingAction) {
                    submitted { harness.submit(MatchIntent.Control(ControlAction.PassPriority)) }
                } else {
                    SimSubmitResult.NoPending
                }
            SimDecision.RetirePrompt,
            SimDecision.WaitForEngine,
            SimDecision.Terminal,
            -> SimSubmitResult.NotSubmitted
        }

    private fun submitPerformAction(action: Action): SimSubmitResult {
        if (!harness.observe().pendingAction) return SimSubmitResult.NoPending
        harness.submit(
            MatchIntent.Play(
                PlayAction.Selection(
                    ActionSelection(
                        kind = action.kind(),
                        instanceId = action.instanceId,
                        abilityGrpId = action.abilityGrpId,
                        alternativeGrpId = action.alternativeGrpId,
                    ),
                ),
            ),
        )
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

private fun Action.kind(): ActionKind =
    when (actionType) {
        ActionType.Pass -> ActionKind.Pass
        ActionType.Cast -> ActionKind.Cast
        ActionType.Activate_add3 -> ActionKind.Activate
        ActionType.ActivateMana -> ActionKind.ActivateMana
        ActionType.Play_add3 -> ActionKind.PlayLand
        ActionType.PlayMdfc -> ActionKind.PlayMdfc
        ActionType.CastMdfc -> ActionKind.CastMdfc
        ActionType.CastAdventure -> ActionKind.CastAdventure
        ActionType.CastOmen -> ActionKind.CastOmen
        ActionType.SpecialTurnFaceUp_add3 -> ActionKind.TurnFaceUp
        else -> error("Unsupported semantic simclient action: $actionType")
    }

private fun ManaColor.toSemanticColor(): leyline.tooling.headless.ManaColorChoice =
    when (this) {
        ManaColor.White_afc9 -> leyline.tooling.headless.ManaColorChoice.White
        ManaColor.Blue_afc9 -> leyline.tooling.headless.ManaColorChoice.Blue
        ManaColor.Black_afc9 -> leyline.tooling.headless.ManaColorChoice.Black
        ManaColor.Red_afc9 -> leyline.tooling.headless.ManaColorChoice.Red
        ManaColor.Green_afc9 -> leyline.tooling.headless.ManaColorChoice.Green
        ManaColor.Colorless_afc9 -> leyline.tooling.headless.ManaColorChoice.Colorless
        ManaColor.Phyrexian_afc9 -> leyline.tooling.headless.ManaColorChoice.Phyrexian
        ManaColor.Generic -> leyline.tooling.headless.ManaColorChoice.Generic
        ManaColor.X -> leyline.tooling.headless.ManaColorChoice.X
        ManaColor.Y -> leyline.tooling.headless.ManaColorChoice.Y
        ManaColor.TwoGeneric -> leyline.tooling.headless.ManaColorChoice.TwoGeneric
        ManaColor.AnyColor -> leyline.tooling.headless.ManaColorChoice.AnyColor
        ManaColor.Snow_afc9 -> leyline.tooling.headless.ManaColorChoice.Snow
        else -> error("Unsupported semantic simclient mana color: $this")
    }
