package leyline.game.event

import leyline.bridge.types.ForgeCardId
import java.util.concurrent.ConcurrentHashMap

internal enum class PendingStackAbilityKind {
    Trigger,
    Activation,
}

internal data class PendingStackAbilityContext(
    val kind: PendingStackAbilityKind,
    val sourceCardId: ForgeCardId,
    val abilityGrpId: Int,
)

internal class PendingStackAbilityRegistry {
    private val pending = ConcurrentHashMap<Int, PendingStackAbilityContext>()

    fun recordTrigger(
        forgeAbilityId: Int,
        sourceCardId: ForgeCardId,
        abilityGrpId: Int,
    ) = record(forgeAbilityId, sourceCardId, abilityGrpId, PendingStackAbilityKind.Trigger)

    fun recordActivation(
        forgeAbilityId: Int,
        sourceCardId: ForgeCardId,
        abilityGrpId: Int,
    ) = record(forgeAbilityId, sourceCardId, abilityGrpId, PendingStackAbilityKind.Activation)

    fun isTriggerResolving(forgeAbilityId: Int): Boolean = pending[forgeAbilityId]?.kind == PendingStackAbilityKind.Trigger

    fun contextFor(forgeAbilityId: Int): PendingStackAbilityContext? = pending[forgeAbilityId]

    fun abilityIdFor(
        sourceCardId: ForgeCardId,
        abilityGrpId: Int,
        kind: PendingStackAbilityKind? = null,
    ): Int? =
        pending.entries
            .firstOrNull { (_, context) ->
                context.sourceCardId == sourceCardId &&
                    context.abilityGrpId == abilityGrpId &&
                    (kind == null || context.kind == kind)
            }?.key

    fun consume(forgeAbilityId: Int): PendingStackAbilityContext? = pending.remove(forgeAbilityId)

    private fun record(
        forgeAbilityId: Int,
        sourceCardId: ForgeCardId,
        abilityGrpId: Int,
        kind: PendingStackAbilityKind,
    ) {
        if (forgeAbilityId == 0) return
        pending[forgeAbilityId] =
            PendingStackAbilityContext(
                kind = kind,
                sourceCardId = sourceCardId,
                abilityGrpId = abilityGrpId,
            )
    }
}
