package leyline.game.annotations

import forge.game.spellability.SpellAbility
import forge.game.staticability.StaticAbilityExhaust
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
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType

/**
 * Per-mechanic annotation emitters lifted out of StateMapper alongside the
 * [AnnotationPipeline] spine. These remain plain functions called directly by
 * `computeRemainingAnnotations`; the registry-driven [AnnotationContributor]
 * port is a later slice. Behavior is unchanged from the StateMapper originals.
 */

private const val INITIAL_UNIQUE_ABILITY_ID = 50

internal fun earthbendCreatedAnnotations(created: List<EarthbendTracker.Active>): List<AnnotationInfo> =
    created.flatMap { active ->
        val affector = InstanceId(active.resolvingInstanceId)
        listOf(
            AnnotationBuilder.layeredEffectCreated(EffectId(active.layers.type), affector),
            AnnotationBuilder.layeredEffectCreated(EffectId(active.layers.haste), affector),
            AnnotationBuilder.layeredEffectCreated(EffectId(active.layers.power), affector),
            AnnotationBuilder.layeredEffectCreated(EffectId(active.layers.toughness), affector),
        )
    }

internal fun earthbendPersistentAnnotations(created: List<EarthbendTracker.Active>): List<AnnotationInfo> =
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

internal fun earthbendDesignationAnnotations(
    active: List<EarthbendTracker.Active>,
    snap: GsmSnapshot,
): List<AnnotationInfo> =
    active.mapNotNull { state ->
        val controller = snap.objects[state.targetForgeCardId]?.controller ?: return@mapNotNull null
        AnnotationBuilder.manaCreatureDesignation(InstanceId(state.targetInstanceId), controller)
    }

internal fun earthbendPowerToughnessMods(
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

internal fun buildAbilityExhaustedAnnotations(
    snap: GsmSnapshot,
    bridge: GameBridge,
    frameIds: leyline.game.mapping.FrameIdResolver,
): List<AnnotationInfo> =
    snap.boundCards.values.flatMap { bound ->
        val card = bridge.findCard(bound.forgeCardId) ?: return@flatMap emptyList()
        val player = card.controller ?: return@flatMap emptyList()
        val registry = bridge.abilityRegistryFor(card, bound.data) ?: return@flatMap emptyList()
        val usesRemaining = if (StaticAbilityExhaust.anyWithExhaust(player)) 1 else 0
        val abilities =
            card.allSpellAbilities.orEmpty() +
                card.manaAbilities.orEmpty() +
                getNonManaActivatedAbilities(card, player)
        exhaustedAbilities(abilities).mapNotNull { ability ->
            val abilityGrpId = registry.forSpellAbility(ability.id).takeIf { it != 0 } ?: return@mapNotNull null
            AnnotationBuilder.abilityExhausted(
                instanceId = frameIds.cardIid(bound.forgeCardId),
                abilityGrpId = GrpId(abilityGrpId),
                usesRemaining = usesRemaining,
                uniqueAbilityId = uniqueAbilityIdFor(bound.data, abilityGrpId),
            )
        }
    }

private fun exhaustedAbilities(abilities: List<SpellAbility>): List<SpellAbility> =
    abilities
        .distinctBy { it.id }
        .filter { it.isExhaust && it.activationsThisGame > 0 }

private fun uniqueAbilityIdFor(
    cardData: CardData?,
    abilityGrpId: Int,
): Int =
    cardData
        ?.abilityIds
        .orEmpty()
        .indexOfFirst { (grpId, _) -> grpId == abilityGrpId }
        .takeIf { it >= 0 }
        ?.let { INITIAL_UNIQUE_ABILITY_ID + it }
        ?: INITIAL_UNIQUE_ABILITY_ID

internal fun buildManaDetailsAnnotations(snap: GsmSnapshot): List<AnnotationInfo> =
    snap.seats.flatMap { seat ->
        seat.manaPool.mapNotNull { mana ->
            if (ManaSpecType.DoesNotEmpty !in mana.specs) return@mapNotNull null
            AnnotationBuilder.manaDetails(
                sourceInstanceId = InstanceId(mana.srcInstanceId),
                manaId = mana.manaId,
            )
        }
    }
