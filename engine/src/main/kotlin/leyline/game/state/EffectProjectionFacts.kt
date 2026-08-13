package leyline.game.state

import leyline.bridge.types.ForgeCardId
import kotlin.ConsistentCopyVisibility

/**
 * Immutable synthetic-effect observation for one projection cut.
 *
 * The shell owns materialization. Projection receives only stable Forge ids,
 * values, and pre-resolved static metadata; instance/effect ids remain
 * tentative projection state so their allocation order stays unchanged.
 */
@ConsistentCopyVisibility
data class EffectProjectionFacts private constructor(
    val boostEntries: List<BoostEntry>,
    val keywordEntries: List<KeywordEntry>,
    val crewStates: List<CrewState>,
    val saddleStates: List<SaddleState>,
    val reconfigureStates: List<ReconfigureState>,
    val pendingEarthbendResolutions: List<PendingEarthbendResolution>,
    val battlefieldEarthbendSignatures: List<BattlefieldEarthbendSignature>,
) {
    companion object {
        operator fun invoke(
            boostEntries: List<BoostEntry> = emptyList(),
            keywordEntries: List<KeywordEntry> = emptyList(),
            crewStates: List<CrewState> = emptyList(),
            saddleStates: List<SaddleState> = emptyList(),
            reconfigureStates: List<ReconfigureState> = emptyList(),
            pendingEarthbendResolutions: List<PendingEarthbendResolution> = emptyList(),
            battlefieldEarthbendSignatures: List<BattlefieldEarthbendSignature> = emptyList(),
        ): EffectProjectionFacts =
            EffectProjectionFacts(
                unmodifiable(boostEntries),
                unmodifiable(keywordEntries),
                unmodifiable(crewStates),
                unmodifiable(saddleStates),
                unmodifiable(reconfigureStates),
                unmodifiable(pendingEarthbendResolutions),
                unmodifiable(battlefieldEarthbendSignatures),
            )

        private fun <T> unmodifiable(values: List<T>): List<T> = java.util.Collections.unmodifiableList(values.toList())
    }

    data class BoostEntry(
        val forgeCardId: ForgeCardId,
        val timestamp: Long,
        val staticId: Long,
        val power: Int,
        val toughness: Int,
    )

    data class KeywordEntry(
        val forgeCardId: ForgeCardId,
        val timestamp: Long,
        val staticId: Long,
        val keyword: String,
    )

    @ConsistentCopyVisibility
    data class CrewState private constructor(
        val vehicleForgeCardId: ForgeCardId,
        val crewSourceForgeCardIds: List<ForgeCardId>,
        val isCreature: Boolean,
        val crewAbilityGrpId: Int?,
    ) {
        companion object {
            operator fun invoke(
                vehicleForgeCardId: ForgeCardId,
                crewSourceForgeCardIds: List<ForgeCardId>,
                isCreature: Boolean,
                crewAbilityGrpId: Int?,
            ): CrewState =
                CrewState(
                    vehicleForgeCardId,
                    java.util.Collections.unmodifiableList(crewSourceForgeCardIds.toList()),
                    isCreature,
                    crewAbilityGrpId,
                )
        }
    }

    @ConsistentCopyVisibility
    data class SaddleState private constructor(
        val mountForgeCardId: ForgeCardId,
        val saddleSourceForgeCardIds: List<ForgeCardId>,
    ) {
        companion object {
            operator fun invoke(
                mountForgeCardId: ForgeCardId,
                saddleSourceForgeCardIds: List<ForgeCardId>,
            ): SaddleState =
                SaddleState(
                    mountForgeCardId,
                    java.util.Collections.unmodifiableList(saddleSourceForgeCardIds.toList()),
                )
        }
    }

    data class ReconfigureState(
        val forgeCardId: ForgeCardId,
        val isAttached: Boolean,
        val isCreature: Boolean,
        val attachAbilityGrpId: Int?,
    )

    @ConsistentCopyVisibility
    data class PendingEarthbendResolution private constructor(
        val version: Long,
        val sourceCardId: ForgeCardId,
        val sourceAbilityGrpId: Int,
        val abilityForgeId: Int,
        val targetCardIds: List<ForgeCardId>,
    ) {
        companion object {
            operator fun invoke(
                version: Long,
                sourceCardId: ForgeCardId,
                sourceAbilityGrpId: Int,
                abilityForgeId: Int,
                targetCardIds: List<ForgeCardId>,
            ): PendingEarthbendResolution =
                PendingEarthbendResolution(
                    version,
                    sourceCardId,
                    sourceAbilityGrpId,
                    abilityForgeId,
                    java.util.Collections.unmodifiableList(targetCardIds.toList()),
                )
        }
    }

    data class BattlefieldEarthbendSignature(
        val forgeCardId: ForgeCardId,
        val signature: EarthbendTracker.Signature,
    )

    init {
        require(pendingEarthbendResolutions.map { it.version }.distinct().size == pendingEarthbendResolutions.size) {
            "Earthbend resolution versions must be unique within one cut"
        }
    }
}
