package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import kotlin.ConsistentCopyVisibility

/** Immutable engine-thread request presented before a blocking interaction waits. */
sealed interface BlockingInteraction {
    data class Optional(
        val sourceId: ForgeCardId?,
        val forceSnapshotBeforePrompt: Boolean,
        val customPromptId: Int?,
        val commanderReturn: CommanderReturnPromptContext?,
    ) : BlockingInteraction

    data class Numeric(
        val sourceId: ForgeCardId?,
        val min: Int,
        val max: Int,
        val defaultValue: Int,
    ) : BlockingInteraction

    @ConsistentCopyVisibility
    data class Damage private constructor(
        val attackerId: ForgeCardId,
        val blockerIds: List<ForgeCardId>,
        val damageDealt: Int,
        val hasDeathtouch: Boolean,
        val hasTrample: Boolean,
        val hasDefender: Boolean,
    ) : BlockingInteraction {
        companion object {
            fun of(
                attackerId: ForgeCardId,
                blockerIds: List<ForgeCardId>,
                damageDealt: Int,
                hasDeathtouch: Boolean,
                hasTrample: Boolean,
                hasDefender: Boolean,
            ): Damage =
                Damage(
                    attackerId,
                    blockerIds.toList(),
                    damageDealt,
                    hasDeathtouch,
                    hasTrample,
                    hasDefender,
                )
        }
    }
}

/** Immutable declaration response values; the coordinator resolves engine actions. */
sealed interface DeclarationAnswer {
    @ConsistentCopyVisibility
    data class Attackers internal constructor(
        val attackerIds: List<ForgeCardId>,
        val attackAlternativeByAttacker: Map<ForgeCardId, Int>,
        val defender: Target?,
        val defenderByAttacker: Map<ForgeCardId, Target>,
    ) : DeclarationAnswer {
        companion object {
            fun of(
                attackerIds: List<ForgeCardId>,
                attackAlternativeByAttacker: Map<ForgeCardId, Int> = emptyMap(),
                defender: Target? = null,
                defenderByAttacker: Map<ForgeCardId, Target> = emptyMap(),
            ): Attackers =
                Attackers(
                    attackerIds.toList(),
                    attackAlternativeByAttacker.toMap(),
                    defender,
                    defenderByAttacker.toMap(),
                )
        }
    }

    @ConsistentCopyVisibility
    data class Blockers internal constructor(
        val blockAssignments: Map<ForgeCardId, ForgeCardId>,
    ) : DeclarationAnswer {
        companion object {
            fun of(blockAssignments: Map<ForgeCardId, ForgeCardId>): Blockers = Blockers(blockAssignments.toMap())
        }
    }
}
