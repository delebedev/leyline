package leyline.game.annotations

import leyline.bridge.types.EffectId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.EarthbendTracker
import leyline.game.state.EffectProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Spine-called annotation emitters that deliberately stay off the
 * [AnnotationContributor] registry.
 *
 * - [EarthbendEmitter] is effect-diff-channel coupled: a single immutable
 *   Earthbend frame drain feeds both contributor-shaped output
 *   (transient layer annotations, ManaCreatureDesignation persistent) and
 *   spine-shaped output (persistent layer annotations merged into the
 *   effect-layer persistent channel, and destroyed layer ids consumed by the
 *   retained diff patch). Splitting that drain across the contributor boundary
 *   isn't possible without either double-draining or widening [Contribution]
 *   for one mechanic, so the spine calls the emitter directly.
 * Helper builders are pure after the frame drain; behavior is unchanged from
 * the StateMapper originals.
 */

internal object EarthbendEmitter {
    data class Result(
        val destroyed: List<AnnotationInfo>,
        val created: List<AnnotationInfo>,
        val powerToughnessMods: List<AnnotationInfo>,
        val designations: List<AnnotationInfo>,
        val effectPersistent: List<AnnotationInfo>,
        val destroyedLayerIds: List<Int>,
    )

    fun emit(
        earthbend: EarthbendTracker,
        facts: EffectProjectionFacts,
        snap: GsmSnapshot,
    ): Result {
        val frame = earthbend.drainFrame(facts.battlefieldEarthbendSignatures)
        return Result(
            destroyed =
                frame.destroyedLayerIds.map {
                    AnnotationBuilder.layeredEffectDestroyed(EffectId(it))
                },
            created = createdAnnotations(frame.created),
            powerToughnessMods = powerToughnessMods(frame.created, snap),
            designations = designationAnnotations(frame.active, snap),
            effectPersistent = persistentAnnotations(frame.created),
            destroyedLayerIds = frame.destroyedLayerIds,
        )
    }

    private fun createdAnnotations(created: List<EarthbendTracker.Active>): List<AnnotationInfo> =
        created.flatMap { active ->
            val affector = InstanceId(active.resolvingInstanceId)
            listOf(
                AnnotationBuilder.layeredEffectCreated(EffectId(active.layers.type), affector),
                AnnotationBuilder.layeredEffectCreated(EffectId(active.layers.haste), affector),
                AnnotationBuilder.layeredEffectCreated(EffectId(active.layers.power), affector),
                AnnotationBuilder.layeredEffectCreated(EffectId(active.layers.toughness), affector),
            )
        }

    private fun persistentAnnotations(created: List<EarthbendTracker.Active>): List<AnnotationInfo> =
        created.flatMap { active ->
            val target = InstanceId(active.targetInstanceId)
            val source = InstanceId(active.sourceInstanceId)
            val sourceAbility = GrpId(active.sourceAbilityGrpId)
            listOf(
                AnnotationBuilder.earthbendModifiedTypeLayeredEffect(
                    instanceId = target,
                    affectorId = source,
                    effectId = EffectId(active.layers.type),
                    sourceAbilityGrpId = sourceAbility,
                ),
                AnnotationBuilder.earthbendAddHasteLayeredEffect(
                    instanceId = target,
                    affectorId = source,
                    effectId = EffectId(active.layers.haste),
                    sourceAbilityGrpId = sourceAbility,
                    uniqueAbilityId = active.uniqueAbilityId,
                    originalAbilityObjectZcid = active.sourceInstanceId,
                    hasteGrpId = GrpId(KeywordAbilityIds.HASTE),
                ),
                AnnotationBuilder.earthbendModifiedPowerLayeredEffect(
                    instanceId = target,
                    affectorId = source,
                    effectId = EffectId(active.layers.power),
                    sourceAbilityGrpId = sourceAbility,
                ),
                AnnotationBuilder.earthbendModifiedToughnessLayeredEffect(
                    instanceId = target,
                    affectorId = source,
                    effectId = EffectId(active.layers.toughness),
                    sourceAbilityGrpId = sourceAbility,
                ),
            )
        }

    private fun designationAnnotations(
        active: List<EarthbendTracker.Active>,
        snap: GsmSnapshot,
    ): List<AnnotationInfo> =
        active.mapNotNull { state ->
            val controller = snap.objects[state.targetForgeCardId]?.controller ?: return@mapNotNull null
            AnnotationBuilder.manaCreatureDesignation(InstanceId(state.targetInstanceId), controller)
        }

    private fun powerToughnessMods(
        created: List<EarthbendTracker.Active>,
        snap: GsmSnapshot,
    ): List<AnnotationInfo> =
        created.mapNotNull { state ->
            val card = snap.objects[state.targetForgeCardId] ?: return@mapNotNull null
            val power = card.netPower ?: return@mapNotNull null
            val toughness = card.netToughness ?: return@mapNotNull null
            val target = InstanceId(state.targetInstanceId)
            AnnotationBuilder.powerToughnessModCreated(target, power, toughness, affectorId = target)
        }
}
