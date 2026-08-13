package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.EarthbendProjection

/**
 * Tracks Earthbend's client-visible layered-effect state.
 *
 * Forge models Earthbend as ordinary continuous effects on the target land:
 * set base P/T to 0/0, add Creature, add Haste, then add +1/+1 counters. The
 * client protocol represents that as four sibling LayeredEffect rows. Repeated
 * Earthbend on the same land refreshes those four rows, even though the engine
 * can keep equivalent older layer entries around. A projection planner owns the
 * mutable tracker; its frozen value commits with the accepted frame. Projection
 * consumes immutable Earthbend resolutions and battlefield signatures supplied
 * by [EffectProjectionFacts], never live Forge cards.
 */
class EarthbendTracker {
    data class Signature(
        val timestamp: Long,
        val staticId: Long,
    )

    data class LayerIds(
        val type: Int,
        val haste: Int,
        val power: Int,
        val toughness: Int,
    ) {
        val all: List<Int> get() = listOf(type, haste, power, toughness)
    }

    data class Active(
        val targetForgeCardId: ForgeCardId,
        val targetInstanceId: Int,
        val sourceInstanceId: Int,
        val sourceCardGrpId: Int,
        val sourceAbilityGrpId: Int,
        val resolvingInstanceId: Int,
        val signature: Signature,
        val layers: LayerIds,
        val uniqueAbilityId: Int,
    )

    data class Frame(
        val destroyedLayerIds: List<Int>,
        val created: List<Active>,
        val active: List<Active>,
    )

    /** Complete lifecycle value used by a tentative projection planner. */
    data class State(
        val activeByTarget: Map<ForgeCardId, Active>,
        val pendingDestroyedLayerIds: List<Int>,
        val pendingCreated: List<Active>,
        val nextUniqueAbilityId: Int,
    )

    private val activeByTarget = linkedMapOf<ForgeCardId, Active>()
    private val pendingDestroyedLayerIds = mutableListOf<Int>()
    private val pendingCreated = mutableListOf<Active>()
    private var nextUniqueAbilityId = INITIAL_UNIQUE_ABILITY_ID

    fun freeze(): State =
        State(
            activeByTarget = activeByTarget.toMap(),
            pendingDestroyedLayerIds = pendingDestroyedLayerIds.toList(),
            pendingCreated = pendingCreated.toList(),
            nextUniqueAbilityId = nextUniqueAbilityId,
        )

    fun load(state: State) {
        activeByTarget.clear()
        activeByTarget.putAll(state.activeByTarget)
        pendingDestroyedLayerIds.clear()
        pendingDestroyedLayerIds.addAll(state.pendingDestroyedLayerIds)
        pendingCreated.clear()
        pendingCreated.addAll(state.pendingCreated)
        nextUniqueAbilityId = state.nextUniqueAbilityId
    }

    fun resetAll() {
        activeByTarget.clear()
        pendingDestroyedLayerIds.clear()
        pendingCreated.clear()
        nextUniqueAbilityId = INITIAL_UNIQUE_ABILITY_ID
    }

    fun recordResolution(
        resolution: EffectProjectionFacts.PendingEarthbendResolution,
        sourceCardGrpId: Int,
        sourceInstanceId: Int,
        resolvingInstanceId: Int,
        battlefieldSignatures: List<EffectProjectionFacts.BattlefieldEarthbendSignature>,
        targetInstanceId: (ForgeCardId) -> Int,
        nextEffectId: () -> Int,
    ) {
        val resolvedSourceAbilityGrpId = resolution.sourceAbilityGrpId.takeIf { it != 0 } ?: sourceCardGrpId
        if (resolvedSourceAbilityGrpId == 0) return

        for (targetForgeId in resolution.targetCardIds) {
            val signature =
                battlefieldSignatures
                    .firstOrNull { it.forgeCardId == targetForgeId }
                    ?.signature
                    ?: continue
            val old = activeByTarget[targetForgeId]
            if (old?.signature == signature) continue
            if (old != null) pendingDestroyedLayerIds.addAll(old.layers.all)

            val active =
                Active(
                    targetForgeCardId = targetForgeId,
                    targetInstanceId = targetInstanceId(targetForgeId),
                    sourceInstanceId = sourceInstanceId,
                    sourceCardGrpId = sourceCardGrpId,
                    sourceAbilityGrpId = resolvedSourceAbilityGrpId,
                    resolvingInstanceId = resolvingInstanceId,
                    signature = signature,
                    layers =
                        LayerIds(
                            type = nextEffectId(),
                            haste = nextEffectId(),
                            power = nextEffectId(),
                            toughness = nextEffectId(),
                        ),
                    uniqueAbilityId = nextUniqueAbilityId++,
                )
            activeByTarget[targetForgeId] = active
            pendingCreated.add(active)
        }
    }

    fun projectionFor(
        forgeCardId: ForgeCardId,
        signature: Signature?,
    ): EarthbendProjection? {
        val active = activeByTarget[forgeCardId] ?: return null
        if (signature != active.signature) return null
        return EarthbendProjection(
            sourceCardGrpId = active.sourceCardGrpId,
            hasteAbilityGrpId = KeywordAbilityIds.HASTE,
            uniqueAbilityId = active.uniqueAbilityId,
        )
    }

    fun isEarthbendHasteKeyword(
        forgeCardId: ForgeCardId,
        timestamp: Long,
        staticId: Long,
    ): Boolean {
        val active = activeByTarget[forgeCardId] ?: return false
        return active.signature == Signature(timestamp, staticId)
    }

    fun drainFrame(battlefieldSignatures: List<EffectProjectionFacts.BattlefieldEarthbendSignature>): Frame {
        val currentByForgeId = battlefieldSignatures.associateBy { it.forgeCardId }
        for ((forgeId, active) in activeByTarget.toList()) {
            if (currentByForgeId[forgeId]?.signature != active.signature) {
                activeByTarget.remove(forgeId)
                pendingDestroyedLayerIds.addAll(active.layers.all)
            }
        }

        val stillActiveTargets = activeByTarget.keys
        val created = pendingCreated.filter { it.targetForgeCardId in stillActiveTargets }
        val frame =
            Frame(
                destroyedLayerIds = pendingDestroyedLayerIds.toList(),
                created = created,
                active = activeByTarget.values.sortedBy { it.targetInstanceId },
            )
        pendingDestroyedLayerIds.clear()
        pendingCreated.clear()
        return frame
    }

    companion object {
        private const val INITIAL_UNIQUE_ABILITY_ID = 200
    }
}
