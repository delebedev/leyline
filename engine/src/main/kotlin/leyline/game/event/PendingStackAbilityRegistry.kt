package leyline.game.event

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ResolvedAbilityIdentity
import java.util.concurrent.ConcurrentHashMap

internal enum class PendingStackAbilityKind {
    Trigger,
    Activation,
}

internal data class PendingStackAbilityContext(
    val kind: PendingStackAbilityKind,
    val sourceCardId: ForgeCardId,
    val identity: ResolvedAbilityIdentity?,
    val paradigmSourceCardId: ForgeCardId? = null,
) {
    val abilityGrpId: Int get() = identity?.abilityGrpId ?: 0
}

internal class PendingStackAbilityRegistry {
    private val pending = ConcurrentHashMap<Int, PendingStackAbilityContext>()

    fun recordTrigger(
        forgeAbilityId: Int,
        sourceCardId: ForgeCardId,
        identity: ResolvedAbilityIdentity?,
        paradigmSourceCardId: ForgeCardId? = null,
    ) = record(forgeAbilityId, sourceCardId, identity, PendingStackAbilityKind.Trigger, paradigmSourceCardId)

    fun recordActivation(
        forgeAbilityId: Int,
        sourceCardId: ForgeCardId,
        identity: ResolvedAbilityIdentity?,
    ) = record(forgeAbilityId, sourceCardId, identity, PendingStackAbilityKind.Activation)

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
        identity: ResolvedAbilityIdentity?,
        kind: PendingStackAbilityKind,
        paradigmSourceCardId: ForgeCardId? = null,
    ) {
        if (forgeAbilityId == 0) return
        pending[forgeAbilityId] =
            PendingStackAbilityContext(
                kind = kind,
                sourceCardId = sourceCardId,
                identity = identity,
                paradigmSourceCardId = paradigmSourceCardId,
            )
    }
}
