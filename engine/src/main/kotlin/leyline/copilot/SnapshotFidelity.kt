package leyline.copilot

import kotlinx.serialization.Serializable
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

@Serializable
data class SnapshotFidelityReport(
    val grade: String,
    val features: List<SnapshotFidelityFeature>,
    val delivery: String = "valid",
    val unavailableReasons: List<String> = emptyList(),
) {
    fun forPrompt(prompt: GREToClientMessage?): SnapshotFidelityReport {
        val scopedFeatures =
            features.filterNot { it.feature == "offered_action_state" } +
                listOfNotNull(prompt?.missingOfferedActionState())
        val family = prompt?.type?.name.orEmpty()
        val strategic = family.isStrategicPrompt()
        val relevantIds = prompt?.referencedInstanceIds().orEmpty()
        val relevantUnsafe =
            scopedFeatures.filter { it.isUnsafeForPrompt(family, strategic, relevantIds) }
        val hasMismatch = relevantUnsafe.any { it.status == "mismatch" }
        val hasMissing =
            relevantUnsafe.any { it.status == "missing" } ||
                (strategic && scopedFeatures.any { it.count > 0 && it.status == "approximated" })
        val promptConstrained = family.isConstrainedPrompt()
        val nextGrade =
            when {
                hasMismatch -> "unsafe"
                hasMissing && strategic -> "degraded"
                hasMissing || promptConstrained -> "prompt_safe"
                else -> "high_fidelity"
            }
        return copy(
            grade = nextGrade,
            features = scopedFeatures,
            delivery = if (relevantUnsafe.isEmpty()) "valid" else "unavailable",
            unavailableReasons = relevantUnsafe.map { "${it.feature}:${it.status}" },
        )
    }
}

private fun String.isStrategicPrompt(): Boolean =
    contains("ActionsAvailable") ||
        contains("DeclareAttackers") ||
        contains("DeclareBlockers") ||
        contains("SelectTargets")

private fun String.isConstrainedPrompt(): Boolean =
    contains("Mulligan") ||
        contains("SelectN") ||
        contains("Group") ||
        contains("CastingTimeOptions") ||
        contains("Order") ||
        contains("Distribution") ||
        contains("AssignDamage")

private fun SnapshotFidelityFeature.isUnsafeForPrompt(
    family: String,
    strategic: Boolean,
    relevantIds: Set<Int>,
): Boolean {
    if (count == 0 || status !in setOf("mismatch", "missing")) return false
    return when (feature) {
        "mana_pool" -> family.contains("ActionsAvailable") || family.contains("PayCosts")
        "stack", "phase", "combat_state", "dynamic_effects" -> strategic
        else -> strategic || instanceIds.any { it in relevantIds }
    }
}

private fun GREToClientMessage.missingOfferedActionState(): SnapshotFidelityFeature? {
    if (!hasActionsAvailableReq()) return null
    val sourceIds =
        actionsAvailableReq.actionsList
            .filter { action ->
                action.actionType == ActionType.Cast &&
                    action.grpId == 0 &&
                    (action.abilityGrpId > 0 || action.alternativeGrpId > 0)
            }.map { it.instanceId }
            .filter { it > 0 }
            .distinct()
    return sourceIds.takeIf { it.isNotEmpty() }?.let {
        SnapshotFidelityFeature(
            feature = "offered_action_state",
            status = "missing",
            count = it.size,
            detail = "the prompt offers a cast whose permission is not reconstructable from card identity alone",
            instanceIds = it,
        )
    }
}

private fun GREToClientMessage.referencedInstanceIds(): Set<Int> =
    when {
        hasActionsAvailableReq() -> actionsAvailableReq.actionsList.mapTo(mutableSetOf()) { it.instanceId }
        hasSelectTargetsReq() ->
            buildSet {
                add(selectTargetsReq.sourceId)
                selectTargetsReq.targetsList.forEach { group ->
                    group.targetsList.forEach { add(it.targetInstanceId) }
                }
            }
        hasSelectNReq() -> selectNReq.idsList.toSet()
        hasOrderReq() -> orderReq.idsList.toSet()
        hasSearchReq() -> searchReq.itemsSoughtList.toSet()
        hasGroupReq() -> groupReq.instanceIdsList.toSet() + groupReq.sourceId
        hasDistributionReq() ->
            distributionReq.targetIdsList.toSet() + distributionReq.validSelectedTargetIdsList
        hasAssignDamageReq() ->
            assignDamageReq.damageAssignersList.flatMapTo(mutableSetOf()) { assigner ->
                listOf(assigner.instanceId) + assigner.assignmentsList.map { it.instanceId }
            }
        hasDeclareAttackersReq() -> declareAttackersReq.attackersList.mapTo(mutableSetOf()) { it.attackerInstanceId }
        hasDeclareBlockersReq() ->
            declareBlockersReq.blockersList.flatMapTo(mutableSetOf()) { blocker ->
                listOf(blocker.blockerInstanceId) + blocker.selectedAttackerInstanceIdsList
            }
        else -> emptySet()
    }.filterTo(mutableSetOf()) { it > 0 }

@Serializable
data class SnapshotFidelityFeature(
    val feature: String,
    val status: String,
    val count: Int,
    val detail: String? = null,
    val instanceIds: List<Int> = emptyList(),
)

data class HydratedSnapshot(
    val bridge: leyline.game.state.GameBridge,
    val fidelity: SnapshotFidelityReport,
)
