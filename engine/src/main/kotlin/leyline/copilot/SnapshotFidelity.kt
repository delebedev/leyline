package leyline.copilot

import kotlinx.serialization.Serializable
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

@Serializable
data class SnapshotFidelityReport(
    val grade: String,
    val features: List<SnapshotFidelityFeature>,
) {
    fun forPrompt(prompt: GREToClientMessage?): SnapshotFidelityReport {
        val family = prompt?.type?.name.orEmpty()
        val strategic =
            family.contains("ActionsAvailable") ||
                family.contains("DeclareAttackers") ||
                family.contains("DeclareBlockers") ||
                family.contains("SelectTargets")
        val hasMismatch = features.any { it.status == "mismatch" && it.count > 0 }
        val hasMissing =
            features.any {
                it.count > 0 &&
                    (it.status == "missing" || it.status == "approximated") &&
                    (strategic || it.feature == "unresolved_cards")
            }
        val promptConstrained =
            family.contains("Mulligan") ||
                family.contains("SelectN") ||
                family.contains("Group") ||
                family.contains("CastingTimeOptions") ||
                family.contains("Order") ||
                family.contains("Distribution") ||
                family.contains("AssignDamage")
        val nextGrade =
            when {
                hasMismatch -> "unsafe"
                hasMissing && strategic -> "degraded"
                hasMissing || promptConstrained -> "prompt_safe"
                else -> "high_fidelity"
            }
        return copy(grade = nextGrade)
    }
}

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
