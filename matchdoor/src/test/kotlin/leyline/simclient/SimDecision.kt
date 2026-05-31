package leyline.simclient

import leyline.testkit.MatchFlowHarness
import leyline.testkit.performAction
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

internal sealed interface SimDecision {
    val kind: String

    data class PerformAction(
        val action: Action,
    ) : SimDecision {
        override val kind: String = "perform:${action.actionType.name}"
    }

    data class SelectTargets(
        val targetInstanceIds: List<Int>,
    ) : SimDecision {
        override val kind: String = "select-targets"
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

    data class GroupTop(
        val instanceIds: List<Int>,
    ) : SimDecision {
        override val kind: String = "group-top"
    }

    data class OptionalCost(
        val ctoId: Int,
    ) : SimDecision {
        override val kind: String = "optional-cost"
    }

    data class ModalChoice(
        val selectedGrpIds: List<Int>,
    ) : SimDecision {
        override val kind: String = "modal-choice"
    }

    data class NumericInput(
        val value: Int,
    ) : SimDecision {
        override val kind: String = "numeric-input"
    }

    data class AssignDamage(
        val assigners: List<Pair<Int, List<Pair<Int, Int>>>>,
    ) : SimDecision {
        override val kind: String = "assign-damage"
    }

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
    private val harness: MatchFlowHarness,
) {
    fun submit(decision: SimDecision): SimSubmitResult =
        when (decision) {
            is SimDecision.PerformAction -> submitPerformAction(decision.action)
            is SimDecision.SelectTargets -> submitted { harness.selectTargets(decision.targetInstanceIds) }
            is SimDecision.SelectN -> submitted { harness.respondToSelectN(decision.selectedInstanceIds) }
            is SimDecision.Order -> submitted { harness.respondToOrder(decision.orderedInstanceIds) }
            is SimDecision.Search -> submitted { harness.respondToSearch(decision.itemsFound) }
            is SimDecision.EffectCost -> submitted { harness.respondToEffectCost(decision.selectedInstanceIds) }
            is SimDecision.GroupTop ->
                submitted {
                    harness.respondToScry(
                        bottomInstanceIds = emptyList(),
                        allInstanceIds = decision.instanceIds,
                    )
                }
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
            is SimDecision.ModalChoice -> submitted { harness.respondModalChoice(decision.selectedGrpIds) }
            is SimDecision.NumericInput -> submitted { harness.respondToNumericInput(decision.value) }
            is SimDecision.AssignDamage -> submitted { harness.assignDamage(decision.assigners) }
            SimDecision.DeclareAllAttackers ->
                submitted {
                    harness.declareAllAttackers()
                    harness.submitAttackers()
                }
            is SimDecision.DeclareAttackers -> submitted { harness.declareAttackers(decision.attackerInstanceIds) }
            is SimDecision.DeclareBlockers -> submitted { harness.declareBlockers(decision.assignments) }
            SimDecision.DeclareNoBlockers -> submitted { harness.declareNoBlockers() }
            SimDecision.CancelAction -> submitted { harness.cancelAction() }
            SimDecision.PassPriority ->
                if (harness.hasPendingAction()) {
                    submitted { harness.passPriority() }
                } else {
                    SimSubmitResult.NoPending
                }
            SimDecision.RetirePrompt,
            SimDecision.WaitForEngine,
            SimDecision.Terminal,
            -> SimSubmitResult.NotSubmitted
        }

    private fun submitPerformAction(action: Action): SimSubmitResult {
        if (!harness.hasPendingAction()) return SimSubmitResult.NoPending
        val response =
            performAction {
                actionType = action.actionType
                instanceId = action.instanceId
                grpId = action.grpId
                abilityGrpId = action.abilityGrpId
                alternativeGrpId = action.alternativeGrpId
            }
        harness.session.onPerformAction(harness.submitWithGsId(response))
        harness.drainSink()
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
