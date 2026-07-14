package leyline.game.annotations

import forge.game.spellability.SpellAbility
import forge.game.staticability.StaticAbilityAdditionalActivations
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.types.EffectId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.data.CardData
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.EarthbendTracker
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Spine-called annotation emitters that deliberately stay off the
 * [AnnotationContributor] registry.
 *
 * - [EarthbendEmitter] is effect-diff-channel coupled: a single
 *   `drainEarthbendFrame()` mutation feeds both contributor-shaped output
 *   (transient layer annotations, ManaCreatureDesignation persistent) and
 *   spine-shaped output (persistent layer annotations merged into the
 *   effect-layer persistent channel, and destroyed layer ids consumed by the
 *   retained diff patch). Splitting that drain across the contributor boundary
 *   isn't possible without either double-draining or widening [Contribution]
 *   for one mechanic, so the spine calls the emitter directly.
 * - [buildAbilityExhaustedAnnotations] is a per-card persistent scan, not one of
 *   the registry mechanics.
 *
 * Helper builders are pure after the frame drain; behavior is unchanged from
 * the StateMapper originals.
 */

private const val INITIAL_UNIQUE_ABILITY_ID = 50

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
        bridge: GameBridge,
        snap: GsmSnapshot,
    ): Result {
        val frame = bridge.drainEarthbendFrame()
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

internal fun buildAbilityExhaustedAnnotations(
    snap: GsmSnapshot,
    bridge: GameBridge,
    frameIds: leyline.game.mapping.FrameIdResolver,
): List<AnnotationInfo> =
    snap.boundCards.values.flatMap { bound ->
        val card = bridge.findCard(bound.forgeCardId) ?: return@flatMap emptyList()
        val player = card.controller ?: return@flatMap emptyList()
        val registry = bridge.abilityRegistryFor(card, bound.data) ?: return@flatMap emptyList()
        val abilities =
            card.allSpellAbilities.orEmpty() +
                card.manaAbilities.orEmpty() +
                getNonManaActivatedAbilities(card, player)
        exhaustedAbilities(abilities).mapNotNull { ability ->
            val abilityGrpId = registry.forSpellAbility(ability.definitionId).takeIf { it != 0 } ?: return@mapNotNull null
            val usesRemaining = remainingUses(card, ability, player)
            AnnotationBuilder.abilityExhausted(
                instanceId = frameIds.cardIid(bound.forgeCardId),
                abilityGrpId = GrpId(abilityGrpId),
                usesRemaining = usesRemaining,
                uniqueAbilityId = uniqueAbilityIdFor(bound.data, abilityGrpId, ability),
            )
        }
    }

private fun exhaustedAbilities(abilities: List<SpellAbility>): List<SpellAbility> =
    abilities
        .distinctBy { it.id }
        .filter { ability ->
            when {
                ability.isBoast -> ability.activationsThisTurn > 0
                ability.isExhaust -> ability.activationsThisGame > 0
                else -> false
            }
        }

private fun remainingUses(
    card: forge.game.card.Card,
    ability: SpellAbility,
    player: forge.game.player.Player,
): Int {
    val used = if (ability.isBoast) ability.activationsThisTurn else ability.activationsThisGame
    return (StaticAbilityAdditionalActivations.getLimit(card, ability, player) - used).coerceAtLeast(0)
}

private fun uniqueAbilityIdFor(
    cardData: CardData?,
    abilityGrpId: Int,
    ability: SpellAbility,
): Int {
    if (ability.isBoast) return BOAST_EXHAUSTED_UNIQUE_ABILITY_ID
    return cardData
        ?.abilityIds
        .orEmpty()
        .indexOfFirst { (grpId, _) -> grpId == abilityGrpId }
        .takeIf { it >= 0 }
        ?.let { INITIAL_UNIQUE_ABILITY_ID + it }
        ?: INITIAL_UNIQUE_ABILITY_ID
}

private const val BOAST_EXHAUSTED_UNIQUE_ABILITY_ID = 374
