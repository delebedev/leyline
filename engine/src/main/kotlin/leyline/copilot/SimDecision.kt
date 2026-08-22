package leyline.copilot

import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Backend-neutral description of the response the Forge-AI decision brain
 * ([ForgeAiPolicy]) would submit for a pending prompt. It carries just enough
 * to (a) submit through the headless harness (simclient) and (b) translate to
 * an autoplay intent proposal for the live client — without pinning either
 * consumer to the other's submit machinery.
 *
 * Lives in main (not the harness source set) because two consumers depend on
 * it: the simclient volume engine and the copilot proposal surface. The submit
 * side ([leyline.tooling.simclient.SimDecisionSubmitter]) stays in the harness.
 */
internal sealed interface SimDecision {
    val kind: String

    data class PerformAction(
        val action: Action,
    ) : SimDecision {
        override val kind: String = "perform:${action.actionType.name}"
    }

    data class SelectTargets(
        val targetInstanceIds: List<Int>,
        /** The prompt target group's targetIdx; a stricter host binds the pick to it. */
        val targetIdx: Int = 0,
    ) : SimDecision {
        override val kind: String = "select-targets"
    }

    /** Un-toggle already-committed targets (Unselect taps) in an iterative SelectTargetsReq. */
    data class UnselectTargets(
        val targetInstanceIds: List<Int>,
        val targetIdx: Int = 0,
    ) : SimDecision {
        override val kind: String = "unselect-targets"
    }

    /** Finalize target declaration — answers the re-prompt after selection. */
    data object SubmitTargets : SimDecision {
        override val kind: String = "submit-targets"
    }

    data class SelectN(
        val selectedInstanceIds: List<Int>,
    ) : SimDecision {
        override val kind: String = "select-n"
    }

    data class Order(
        val orderedInstanceIds: List<Int>,
    ) : SimDecision {
        override val kind: String = "order"
    }

    data class Search(
        val itemsFound: List<Int>,
    ) : SimDecision {
        override val kind: String = "search"
    }

    data class EffectCost(
        val selectedInstanceIds: List<Int>,
    ) : SimDecision {
        override val kind: String = "effect-cost"
    }

    /** Confirm one of the auto-tap mana-payment solutions a PayCostsReq offers. */
    data class AutoTapPayment(
        val solutionIndex: Int,
    ) : SimDecision {
        override val kind: String = "auto-tap-payment"
    }

    /** Keep the current hand at a MulliganReq (AcceptHand). */
    data object KeepHand : SimDecision {
        override val kind: String = "keep-hand"
    }

    data object ChooseStartingPlayer : SimDecision {
        override val kind: String = "choose-starting-player"
    }

    data class GroupTop(
        val instanceIds: List<Int>,
    ) : SimDecision {
        override val kind: String = "group-top"
    }

    data class GroupAway(
        val awayInstanceIds: List<Int>,
        val allInstanceIds: List<Int>,
        val context: GroupingContext,
    ) : SimDecision {
        override val kind: String = "group-away"
    }

    data class OptionalAction(
        val accept: Boolean,
    ) : SimDecision {
        override val kind: String = "optional-action"
    }

    data class OptionalCost(
        val ctoId: Int,
    ) : SimDecision {
        override val kind: String = "optional-cost"
    }

    data class CastingTimeX(
        val ctoId: Int,
        val value: Int,
    ) : SimDecision {
        override val kind: String = "casting-time-x"
    }

    data class ModalChoice(
        val ctoId: Int,
        val selectedGrpIds: List<Int>,
    ) : SimDecision {
        override val kind: String = "modal-choice"
    }

    /**
     * Required cast-time branch pick for an alternate additional cost
     * ("discard a card **or** pay 3 life"). [optionIndex] is the offered
     * `selectNReq` id, which indexes the branch — not a ctoId.
     */
    data class AlternateCost(
        val ctoId: Int,
        val optionIndex: Int,
    ) : SimDecision {
        override val kind: String = "alternate-cost"
    }

    data class ManaTypeChoices(
        val choicesByCtoId: List<Pair<Int, ManaColor>>,
    ) : SimDecision {
        override val kind: String = "mana-type"
    }

    data class NumericInput(
        val value: Int,
    ) : SimDecision {
        override val kind: String = "numeric-input"
    }

    data class Distribution(
        val amountsByInstanceId: Map<Int, Int>,
    ) : SimDecision {
        override val kind: String = "distribution"
    }

    data class AssignDamage(
        val assigners: List<DamageAssignerDecision>,
    ) : SimDecision {
        override val kind: String = "assign-damage"
    }

    data class DamageAssignerDecision(
        val instanceId: Int,
        val totalDamage: Int,
        val assignments: List<DamageAssignmentDecision>,
    )

    data class DamageAssignmentDecision(
        val instanceId: Int,
        val minDamage: Int,
        val maxDamage: Int,
        val assignedDamage: Int,
    )

    data object DeclareAllAttackers : SimDecision {
        override val kind: String = "declare-all-attackers"
    }

    data class DeclareAttackers(
        val attackerInstanceIds: List<Int>,
    ) : SimDecision {
        override val kind: String = "declare-attackers"
    }

    data class DeclareBlockers(
        val assignments: Map<Int, Int>,
    ) : SimDecision {
        override val kind: String = "declare-blockers"
    }

    data object DeclareNoBlockers : SimDecision {
        override val kind: String = "declare-no-blockers"
    }

    /** Un-toggle a committed blocker (empty selection entry in the resp). */
    data class UndeclareBlocker(
        val blockerInstanceId: Int,
    ) : SimDecision {
        override val kind: String = "undeclare-blocker"
    }

    /** Finalize attack declaration — answers the re-prompt after selection. */
    data object SubmitAttackers : SimDecision {
        override val kind: String = "submit-attackers"
    }

    /** Finalize block declaration — answers the re-prompt after selection. */
    data object SubmitBlockers : SimDecision {
        override val kind: String = "submit-blockers"
    }

    data object CancelAction : SimDecision {
        override val kind: String = "cancel-action"
    }

    data object PassPriority : SimDecision {
        override val kind: String = "pass-priority"
    }

    data object RetirePrompt : SimDecision {
        override val kind: String = "retire-prompt"
    }

    data object WaitForEngine : SimDecision {
        override val kind: String = "wait-for-engine"
    }

    data object Terminal : SimDecision {
        override val kind: String = "terminal"
    }
}
