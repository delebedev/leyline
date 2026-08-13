package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.game.codes.QualificationType
import kotlin.ConsistentCopyVisibility

/** Time-sensitive engine observations materialized for one persistent-feed cut. */
@ConsistentCopyVisibility
data class PersistentFeedFacts private constructor(
    val combatQualifications: List<CombatQualificationRow> = emptyList(),
    val collectEvidence: List<CollectEvidenceDisplay> = emptyList(),
    val endStepTokenSources: List<EndStepTokenSource> = emptyList(),
) {
    companion object {
        operator fun invoke(
            combatQualifications: List<CombatQualificationRow> = emptyList(),
            collectEvidence: List<CollectEvidenceDisplay> = emptyList(),
            endStepTokenSources: List<EndStepTokenSource> = emptyList(),
        ): PersistentFeedFacts =
            PersistentFeedFacts(
                unmodifiable(combatQualifications),
                unmodifiable(collectEvidence),
                unmodifiable(endStepTokenSources),
            )

        private fun <T> unmodifiable(values: List<T>): List<T> = java.util.Collections.unmodifiableList(values.toList())
    }

    @ConsistentCopyVisibility
    data class CombatQualificationRow private constructor(
        val affectorForgeId: ForgeCardId,
        val affectedForgeId: ForgeCardId,
        val sourceParentForgeId: ForgeCardId,
        val abilityGrpId: Int,
        val qualificationType: QualificationType,
        val cantBlockForgeIds: List<ForgeCardId> = emptyList(),
        val cantBeBlockedByForgeIds: List<ForgeCardId> = emptyList(),
    ) {
        companion object {
            operator fun invoke(
                affectorForgeId: ForgeCardId,
                affectedForgeId: ForgeCardId,
                sourceParentForgeId: ForgeCardId,
                abilityGrpId: Int,
                qualificationType: QualificationType,
                cantBlockForgeIds: List<ForgeCardId> = emptyList(),
                cantBeBlockedByForgeIds: List<ForgeCardId> = emptyList(),
            ): CombatQualificationRow =
                CombatQualificationRow(
                    affectorForgeId,
                    affectedForgeId,
                    sourceParentForgeId,
                    abilityGrpId,
                    qualificationType,
                    java.util.Collections.unmodifiableList(cantBlockForgeIds.toList()),
                    java.util.Collections.unmodifiableList(cantBeBlockedByForgeIds.toList()),
                )
        }
    }

    data class CollectEvidenceDisplay(
        val key: PromptFactKey,
        val sourceForgeCardId: ForgeCardId,
        val threshold: Int,
        val graveyardManaValue: Int,
        val abilityGrpId: Int,
    )

    /** A null source preserves the generic end-step cleanup projection. */
    data class EndStepTokenSource(
        val tokenForgeCardId: ForgeCardId,
        val sourceForgeCardId: ForgeCardId?,
    )
}
