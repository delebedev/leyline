package leyline.game.annotations

import forge.game.spellability.SpellAbility
import forge.game.staticability.StaticAbilityExhaust
import leyline.DevCheck
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.EffectId
import leyline.bridge.types.ForgeCardId
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

/**
 * Build TargetSpec pAnns from pending target records.
 * Each card target gets a separate annotation with 1-based index per target group.
 * Pruned automatically by the registry-driven upsert pass (TargetSpecKind's
 * full-replacement semantics) when the spell resolves and leaves the stack.
 */
internal fun buildTargetSpecAnnotations(
    pending: List<InteractivePromptBridge.PendingTarget>,
    ctx: AnnotationContext,
): List<AnnotationInfo> {
    // Read target picks recorded during selectTargetsInteractively.
    // The spell may have already resolved by now (auto-pass), so we can't
    // rely on scanning game.getStack() — the stack is often empty.
    if (pending.isEmpty()) return emptyList()

    val frameIds = ctx.frameIds
    // promptId still needs per-ability prompt-shape mapping. Fall back to
    // 0 until a local mapping exists for the targeting prompt copy.
    return pending.mapNotNull { spec ->
        // Use the iid recorded at target-pick time for non-triggers (see
        // PendingTarget KDoc for the multi-target-spell rationale).
        // Triggers defer to emission-time resolution via the SA id —
        // TargetingCoordinator always populates spec.forgeAbilityId when
        // spec.isTriggeredAbility=true, so that branch's fallback is
        // structurally unreachable and crashes under DevCheck.strict.
        val affectorIid =
            if (spec.affectorInstanceIdAtRecord != 0) {
                InstanceId(spec.affectorInstanceIdAtRecord)
            } else if (spec.isTriggeredAbility) {
                if (spec.forgeAbilityId != 0) {
                    frameIds.triggerStackAbilityIid(spec.forgeAbilityId)
                } else {
                    DevCheck.fail {
                        "PendingTarget for ${spec.spellName} marked isTriggeredAbility but missing forgeAbilityId; " +
                            "every triggered-ability target spec must carry the SA id since stack-ability iids " +
                            "are SA-id-keyed"
                    }
                    // Emit 0 rather than the source-card-keyed iid — that
                    // would point at a non-existent stack object since
                    // ZoneMapper now mints via the SA-id-keyed surrogate.
                    // 0 surfaces visibly in invariant checks rather than
                    // routing the TargetSpec to a stale iid.
                    InstanceId(0)
                }
            } else {
                frameIds.cardIid(ForgeCardId(spec.spellForgeCardId))
            }
        val targetIid =
            when {
                spec.targetForgeCardId != null ->
                    frameIds.cardIid(ForgeCardId(spec.targetForgeCardId))
                // Player target: Arena uses seatId (1 or 2) as the iid for player entities.
                spec.targetSeatId != null -> InstanceId(spec.targetSeatId)
                else -> return@mapNotNull null
            }
        val abilityGrpId = ctx.targetSpecAbilityGrpId(spec)
        AnnotationBuilder.targetSpec(
            instanceId = targetIid,
            affectorId = affectorIid,
            abilityGrpId = GrpId(abilityGrpId),
            index = spec.index,
            promptId = spec.promptId ?: 0,
            promptParameters = affectorIid.value,
        )
    }
}

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
