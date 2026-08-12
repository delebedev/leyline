package leyline.game.state

import forge.game.card.Card
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
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
 * mutable tracker; its frozen value commits with the accepted frame.
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
        sourceInstanceId: InstanceId,
        sourceCardGrpId: Int,
        sourceAbilityGrpId: Int,
        resolvingInstanceId: InstanceId,
        targetCards: List<Card>,
        targetInstanceId: (ForgeCardId) -> InstanceId,
        nextEffectId: () -> Int,
    ) {
        val resolvedSourceAbilityGrpId = sourceAbilityGrpId.takeIf { it != 0 } ?: sourceCardGrpId
        if (resolvedSourceAbilityGrpId == 0) return

        for (target in targetCards) {
            val signature = signatureFor(target) ?: continue
            val targetForgeId = ForgeCardId(target.id)
            val old = activeByTarget[targetForgeId]
            if (old?.signature == signature) continue
            if (old != null) pendingDestroyedLayerIds.addAll(old.layers.all)

            val active =
                Active(
                    targetForgeCardId = targetForgeId,
                    targetInstanceId = targetInstanceId(targetForgeId).value,
                    sourceInstanceId = sourceInstanceId.value,
                    sourceCardGrpId = sourceCardGrpId,
                    sourceAbilityGrpId = resolvedSourceAbilityGrpId,
                    resolvingInstanceId = resolvingInstanceId.value,
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

    fun projectionFor(card: Card): EarthbendProjection? {
        val active = activeByTarget[ForgeCardId(card.id)] ?: return null
        if (signatureFor(card) != active.signature) return null
        return EarthbendProjection(
            sourceCardGrpId = active.sourceCardGrpId,
            hasteAbilityGrpId = KeywordAbilityIds.HASTE,
            uniqueAbilityId = active.uniqueAbilityId,
        )
    }

    fun isEarthbendHasteKeyword(
        card: Card,
        timestamp: Long,
        staticId: Long,
    ): Boolean {
        val active = activeByTarget[ForgeCardId(card.id)] ?: return false
        return active.signature == Signature(timestamp, staticId)
    }

    fun drainFrame(battlefieldCards: Iterable<Card>): Frame {
        val currentByForgeId = battlefieldCards.associateBy { ForgeCardId(it.id) }
        for ((forgeId, active) in activeByTarget.toList()) {
            val card = currentByForgeId[forgeId]
            if (card == null || signatureFor(card) != active.signature) {
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

    private fun signatureFor(card: Card): Signature? {
        if (!card.isInZone(ZoneType.Battlefield)) return null
        if (!card.type.isLand || !card.type.isCreature) return null

        val hasteKeys =
            card.changedCardKeywords.cellSet().mapNotNullTo(mutableSetOf()) { cell ->
                val hasHaste = cell.value.keywords.any { it.keyword.toString() == "Haste" }
                if (hasHaste) Signature(cell.rowKey, cell.columnKey) else null
            }
        if (hasteKeys.isEmpty()) return null

        return card.setPTTable
            .cellSet()
            .asSequence()
            .mapNotNull { cell ->
                val value = cell.value
                if (value.left == 0 && value.right == 0) Signature(cell.rowKey, cell.columnKey) else null
            }.filter { it in hasteKeys }
            .maxWithOrNull(compareBy<Signature> { it.timestamp }.thenBy { it.staticId })
    }

    companion object {
        private const val INITIAL_UNIQUE_ABILITY_ID = 200
    }
}
