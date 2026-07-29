package leyline.game.mapping

import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import leyline.game.ManaRequirementValue
import leyline.game.PriorityActionValue
import leyline.game.PriorityCastKind
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.BoundCard
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.ActionType

internal object PriorityActionZonePreparer {
    data class Prepared(
        val action: PriorityActionValue,
        val command: PlayerAction?,
        val stackAbilityGrpId: Int? = null,
        val forgeAbilityId: Int? = null,
        val spellGrpId: Int? = null,
    ) {
        val active: Boolean
            get() = command != null
    }

    fun prepareZoneCasts(
        seatId: Int,
        snapshot: GsmSnapshot,
        bridge: GameBridge,
        candidates: PriorityActionCandidates,
    ): List<Prepared> {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return emptyList()
        return buildList {
            for ((zoneId, rails) in zoneRailBuckets) {
                val zone = snapshot.zones[zoneId] ?: continue
                for (cardId in zone.contents) {
                    val card = bridge.findCard(cardId) ?: continue
                    val castable = candidates.forCard(card).casts
                    val ability = castable.firstOrNull() ?: continue
                    val cardSnapshot = snapshot.objects[cardId]
                    val sourceGrpId =
                        cardSnapshot?.grpId
                            ?: snapshot.boundCards[cardId]?.data?.grpId
                            ?: bridge.resolveGrpId(card)
                    val rail = rails.firstOrNull { it.saPredicate(ability) }
                    val canPay = PriorityActionRailPreparer.canPayManaCost(ability, player)
                    val omitIdentity = rail?.omitGrpIdAndFacetId == true
                    val grpId =
                        when (rail?.grpIdMode) {
                            ZoneCastGrpIdMode.OtherSide ->
                                cardSnapshot?.othersideGrpId?.takeIf { it > 0 } ?: sourceGrpId
                            ZoneCastGrpIdMode.Source,
                            null,
                            -> sourceGrpId
                        }.takeUnless { omitIdentity }
                    val facetCardId =
                        if (rail?.grpIdMode == ZoneCastGrpIdMode.OtherSide &&
                            cardSnapshot?.othersideGrpId?.takeIf { it > 0 } != null
                        ) {
                            FrameIdResolver.disturbBackForgeId(cardId)
                        } else {
                            cardId
                        }
                    val shape = prepareZoneCastShape(ability, rail, snapshot.boundCards[cardId], player)
                    add(
                        Prepared(
                            action =
                                PriorityActionValue.Cast(
                                    kind = PriorityCastKind.CAST,
                                    cardId = cardId,
                                    grpId = grpId,
                                    facetCardId = facetCardId,
                                    abilityGrpId = shape.abilityGrpId,
                                    alternativeGrpId = shape.alternativeGrpId,
                                    manaCost = shape.manaCost,
                                    shouldStop = canPay && ShouldStopEvaluator.shouldStop(ActionType.Cast),
                                    alternativeSourceCardId =
                                        cardId.takeIf { rail?.emitAlternativeSourceZcid == true },
                                ),
                            command =
                                PlayerAction
                                    .CastSpell(
                                        cardId,
                                        castable.indexOfFirst { it === ability },
                                        ability = ability,
                                    ).takeIf { canPay },
                        ),
                    )
                }
            }
        }
    }

    fun prepareGraveyardActivations(
        seatId: Int,
        snapshot: GsmSnapshot,
        bridge: GameBridge,
        candidates: PriorityActionCandidates,
    ): List<Prepared> {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return emptyList()
        val zoneId =
            when (seatId) {
                1 -> ZoneIds.P1_GRAVEYARD
                2 -> ZoneIds.P2_GRAVEYARD
                else -> return emptyList()
            }
        return buildList {
            for (cardId in snapshot.zones[zoneId]?.contents.orEmpty()) {
                val cardSnapshot = snapshot.objects[cardId] ?: continue
                if (!cardSnapshot.hasNonManaActivatedAbilities) continue
                val card = bridge.findCard(cardId) ?: continue
                val cardData = snapshot.boundCards[cardId]?.data
                for ((abilityIndex, ability) in candidates.forCard(card).activations.withIndex()) {
                    if (!ability.canPlay()) continue
                    val abilityGrpId =
                        bridge
                            .abilityRegistryFor(card, cardData)
                            ?.forSpellAbility(ability.definitionId)
                            ?: 0
                    val canPay = PriorityActionRailPreparer.canPayManaCost(ability, player)
                    add(
                        Prepared(
                            action =
                                ActivatedActionEmitter.prepareActivatedAbilityAction(
                                    cardId = cardId,
                                    grpId = null,
                                    abilityGrpId = abilityGrpId,
                                    uniqueAbilityId = ActivatedActionEmitter.uniqueAbilityIdFor(cardData, abilityGrpId),
                                    abilityCost = CastDisplayCost.of(ability, player) ?: ability.payCosts?.totalMana,
                                    canPay = canPay,
                                    envelope = ActivatedActionEmitter.Envelope.ABILITY_ONLY,
                                ),
                            command =
                                PlayerAction
                                    .ActivateAbility(cardId, abilityIndex, ability = ability)
                                    .takeIf { canPay },
                            stackAbilityGrpId = abilityGrpId.takeIf { it != 0 },
                            forgeAbilityId = ability.id,
                        ),
                    )
                }
            }
        }
    }

    private data class ZoneCastShape(
        val alternativeGrpId: Int,
        val abilityGrpId: Int,
        val manaCost: List<ManaRequirementValue>,
    )

    private fun prepareZoneCastShape(
        ability: SpellAbility,
        rail: ZoneCastRail?,
        bound: BoundCard?,
        player: Player,
    ): ZoneCastShape {
        if (rail == null) {
            val altCost = ability.alternativeCost
            val abilityGrpId =
                if (altCost == null) {
                    0
                } else {
                    KeywordAbilityIds
                        .fromForgeAltCostName(altCost.name)
                        ?.let { bound?.altCost(it)?.abilityGrpId }
                        ?: 0
                }
            return ZoneCastShape(
                alternativeGrpId = 0,
                abilityGrpId = abilityGrpId,
                manaCost =
                    if (altCost == null) {
                        CastDisplayCost.requirementValues(ability, player, bound?.data)
                    } else {
                        CastDisplayCost.requirementValues(ability, player, null)
                    },
            )
        }

        val altGrpIdSource = rail.altGrpIdSource
        val needsCostAware =
            altGrpIdSource is AltGrpIdSource.FromBoundCard &&
                altGrpIdSource.lookupMode == LookupMode.CostAware
        val payCostPairs =
            if (needsCostAware) {
                ActionManaCosts
                    .computeEffectiveCost(ability, player)
                    ?.takeIf { !it.isNoCost }
                    ?.let(ActionManaCosts::forgeManaCostToPairs)
                    .orEmpty()
            } else {
                emptyList()
            }
        val alternativeGrpId = resolveAltGrpId(rail, bound?.altCosts.orEmpty(), payCostPairs)
        val abilityGrpId =
            when (val mode = rail.abilityGrpIdMode) {
                AbilityGrpIdMode.None -> 0
                is AbilityGrpIdMode.FixedKeyword -> mode.baseId
                AbilityGrpIdMode.EchoAlternative -> alternativeGrpId
            }
        return ZoneCastShape(
            alternativeGrpId = alternativeGrpId,
            abilityGrpId = abilityGrpId,
            manaCost =
                if (rail.emitManaCost) {
                    CastDisplayCost.requirementValues(
                        ability,
                        player,
                        null,
                        alternativeGrpId.takeIf { rail.echoAlternativeOnMana && it > 0 },
                    )
                } else {
                    emptyList()
                },
        )
    }

    private val zoneRailBuckets: List<Pair<Int, List<ZoneCastRail>>> =
        listOf(
            ZoneIds.EXILE to CastRails.fromExile,
            ZoneIds.P1_GRAVEYARD to CastRails.fromGraveyard,
            ZoneIds.P2_GRAVEYARD to CastRails.fromGraveyard,
            ZoneIds.COMMAND to emptyList(),
        )
}
