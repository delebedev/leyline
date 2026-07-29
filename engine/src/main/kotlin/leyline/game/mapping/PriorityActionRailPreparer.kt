package leyline.game.mapping

import forge.card.CardStateName
import forge.game.ability.ApiType
import forge.game.ability.effects.CharmEffect
import forge.game.card.Card
import forge.game.keyword.Keyword
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import leyline.bridge.types.ForgeCardId
import leyline.game.ManaRequirementValue
import leyline.game.PriorityActionValue
import leyline.game.PriorityCastKind
import leyline.game.PriorityPlayKind
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.snapshot.AltCostBinding
import leyline.game.snapshot.CardSnapshot
import leyline.game.state.AbilityRegistry
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import forge.game.zone.ZoneType as ForgeZoneType

internal object PriorityActionRailPreparer {
    data class Interaction(
        val action: PriorityActionValue,
        val abilityIndex: Int,
        val ability: SpellAbility,
        val active: Boolean,
    )

    sealed interface Command {
        data class Cast(
            val index: Int,
            val ability: SpellAbility,
        ) : Command

        data object PlayLand : Command
    }

    data class Mdfc(
        val action: PriorityActionValue,
        val active: Boolean,
        val command: Command,
    )

    fun prepareRoomCasts(
        card: Card,
        player: Player,
        cardId: ForgeCardId,
        castable: List<SpellAbility>,
    ): List<Interaction> =
        card.lockedRooms.mapNotNull { state ->
            val descriptor = RoomDoorCastDescriptors.forState(state) ?: return@mapNotNull null
            val abilityIndex = castable.indexOfFirst { it.cardStateName == state }
            val ability = castable.getOrNull(abilityIndex) ?: return@mapNotNull null
            ability.setActivatingPlayer(player)
            val active = canPlayAndPayManaCost(ability, player)
            Interaction(
                action =
                    PriorityActionValue.Cast(
                        kind = descriptor.actionType.toPriorityCastKind(),
                        cardId = cardId,
                        manaCost = CastDisplayCost.requirementValues(ability, player, null),
                        shouldStop = ShouldStopEvaluator.shouldStop(descriptor.actionType),
                    ),
                abilityIndex = abilityIndex,
                ability = ability,
                active = active,
            )
        }

    @Suppress("LongParameterList")
    fun prepareTurnFaceUp(
        card: Card,
        player: Player,
        cardId: ForgeCardId,
        cardData: CardData?,
        fallbackAlternativeGrpId: Int,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
        abilities: List<SpellAbility>,
    ): Interaction? {
        val abilityIndex = abilities.indexOfFirst { it.isTurnFaceUp }
        val ability = abilities.getOrNull(abilityIndex) ?: return null
        ability.setActivatingPlayer(player)
        val alternativeGrpId =
            abilityRegistryLookup(card, cardData)?.forSpellAbility(ability.definitionId)
                ?: fallbackAlternativeGrpId
        if (alternativeGrpId == 0) return null
        val active = canPayManaCost(ability, player)
        return Interaction(
            action =
                PriorityActionValue.TurnFaceUp(
                    cardId = cardId,
                    alternativeGrpId = alternativeGrpId,
                    manaCost = CastDisplayCost.requirementValues(ability, player, null, alternativeGrpId),
                    shouldStop = ShouldStopEvaluator.shouldStop(ActionType.SpecialTurnFaceUp_add3),
                ),
            abilityIndex = abilityIndex,
            ability = ability,
            active = active,
        )
    }

    fun prepareHandAltCostCasts(
        player: Player,
        cardId: ForgeCardId,
        grpId: Int,
        altCosts: List<AltCostBinding>,
        castable: List<SpellAbility>,
    ): List<Interaction> {
        val emitted = mutableSetOf<Pair<Int, List<Pair<ManaColor, Int>>>>()
        return castable.mapIndexedNotNull { abilityIndex, ability ->
            val rail = CastRails.handWithAltCost.firstOrNull { it.saPredicate(ability) } ?: return@mapIndexedNotNull null
            if (rail.kind == AltCostKind.MUTATE && hasUnmetTargeting(ability)) return@mapIndexedNotNull null
            val (payCostPairs, alternativeGrpId) = effectiveCostForOffer(rail, ability, player, altCosts)
            if (rail.kind == AltCostKind.EMERGE && alternativeGrpId <= 0) return@mapIndexedNotNull null
            val canPay =
                if (rail.kind == AltCostKind.EMERGE) {
                    canPayEmerge(payCostPairs, player)
                } else {
                    canPayManaCost(ability, player)
                }
            if (!canPay || alternativeGrpId <= 0 || !emitted.add(alternativeGrpId to payCostPairs)) {
                return@mapIndexedNotNull null
            }
            Interaction(
                action =
                    PriorityActionValue.Cast(
                        kind = PriorityCastKind.CAST,
                        cardId = cardId,
                        grpId = grpId,
                        alternativeGrpId = alternativeGrpId,
                        manaCost =
                            payCostPairs.map { (color, count) ->
                                ManaRequirementValue(listOf(color.number), count, alternativeGrpId)
                            },
                        shouldStop = ShouldStopEvaluator.shouldStop(ActionType.Cast),
                    ),
                abilityIndex = abilityIndex,
                ability = ability,
                active = true,
            )
        }
    }

    fun prepareSecondaryFaceCasts(
        card: Card,
        player: Player,
        cardId: ForgeCardId,
        grpId: Int,
        cardSnap: CardSnapshot,
        castable: List<SpellAbility>,
    ): List<Interaction> =
        buildList {
            if (cardSnap.isAdventureCard) {
                prepareSecondaryFace(
                    card,
                    player,
                    cardId,
                    grpId,
                    castable,
                    SpellAbility::isAdventure,
                    PriorityCastKind.ADVENTURE,
                    ActionType.CastAdventure,
                )?.let(::add)
            }
            if (cardSnap.isOmenCard) {
                prepareSecondaryFace(
                    card,
                    player,
                    cardId,
                    null,
                    castable,
                    SpellAbility::isOmen,
                    PriorityCastKind.OMEN,
                    ActionType.CastOmen,
                )?.let(::add)
            }
        }

    private fun prepareSecondaryFace(
        card: Card,
        player: Player,
        cardId: ForgeCardId,
        grpId: Int?,
        castable: List<SpellAbility>,
        predicate: (SpellAbility) -> Boolean,
        kind: PriorityCastKind,
        actionType: ActionType,
    ): Interaction? {
        val ability = castable.firstOrNull(predicate)
        val abilityIndex = castable.indexOfFirst { it === ability }
        val active =
            ability
                ?.also { it.setActivatingPlayer(player) }
                ?.let { canPlayAndPayManaCost(it, player) } == true
        val displayAbility =
            ability
                ?: card
                    .getState(CardStateName.Secondary)
                    ?.nonManaAbilities
                    ?.firstOrNull()
                    ?.also { it.setActivatingPlayer(player) }
                    ?.takeIf { it.canPlay() }
                ?: return null
        return Interaction(
            PriorityActionValue.Cast(
                kind = kind,
                cardId = cardId,
                grpId = grpId,
                manaCost = CastDisplayCost.requirementValues(displayAbility, player, null),
                shouldStop = active && ShouldStopEvaluator.shouldStop(actionType),
            ),
            abilityIndex,
            displayAbility,
            active,
        )
    }

    @Suppress("LongParameterList")
    fun prepareMdfcFaces(
        player: Player,
        cardId: ForgeCardId,
        parentGrpId: Int,
        cardRepository: CardRepository?,
        castable: List<SpellAbility>,
        mdfcLandAbility: LandAbility?,
    ): List<Mdfc> =
        buildList {
            val backSpell = castable.firstOrNull(::isMdfcBackSpell)
            if (backSpell != null && !hasUnmetTargeting(backSpell) && !hasNoLegalCharmModes(backSpell)) {
                backSpell.setActivatingPlayer(player)
                val active = canPlayAndPayManaCost(backSpell, player)
                val abilityGrpId = resolveMdfcBackAbilityGrpId(backSpell, parentGrpId, cardRepository)
                add(
                    Mdfc(
                        PriorityActionValue.Cast(
                            kind = PriorityCastKind.MDFC,
                            cardId = cardId,
                            abilityGrpId = abilityGrpId,
                            sourceCardId = cardId,
                            manaCost =
                                CastDisplayCost.requirementValues(
                                    backSpell,
                                    player,
                                    null,
                                    abilityGrpId.takeIf { it != 0 },
                                ),
                            shouldStop = ShouldStopEvaluator.shouldStop(ActionType.CastMdfc),
                        ),
                        active,
                        Command.Cast(castable.indexOfFirst { it === backSpell }, backSpell),
                    ),
                )
            }
            if (mdfcLandAbility != null) {
                mdfcLandAbility.activatingPlayer = player
                val active = canPlay(mdfcLandAbility)
                add(
                    Mdfc(
                        PriorityActionValue.PlayLand(
                            kind = PriorityPlayKind.MDFC,
                            cardId = cardId,
                            shouldStop = ShouldStopEvaluator.shouldStop(ActionType.PlayMdfc),
                        ),
                        active,
                        Command.PlayLand,
                    ),
                )
            }
        }

    fun canPayManaCost(
        ability: SpellAbility,
        player: Player,
    ): Boolean = ActionManaCosts.canPayManaCost(ability, player)

    fun canPlayAndPayManaCost(
        ability: SpellAbility,
        player: Player,
    ): Boolean = ActionManaCosts.canPlayAndPayManaCost(ability, player)

    fun isMdfcBackSpell(ability: SpellAbility): Boolean =
        ability.hostCard?.isModal == true && ability.cardStateName == CardStateName.Backside

    private fun resolveMdfcBackAbilityGrpId(
        ability: SpellAbility,
        parentGrpId: Int,
        cardRepository: CardRepository?,
    ): Int {
        if (cardRepository == null) return 0
        val backName = ability.cardState?.name
        val backGrpId =
            backName?.let(cardRepository::findGrpIdByNameAnyFace)
                ?: cardRepository.findLinkedFaces(parentGrpId).firstOrNull { it != parentGrpId }
                ?: return 0
        return cardRepository
            .findByGrpId(backGrpId)
            ?.abilityIds
            ?.firstOrNull()
            ?.first ?: 0
    }

    private fun canPlay(ability: SpellAbility): Boolean =
        try {
            ability.canPlay()
        } catch (_: Exception) {
            false
        }

    fun hasUnmetTargeting(ability: SpellAbility): Boolean {
        val game = ability.hostCard?.game ?: return false
        var node: SpellAbility? = ability
        while (node != null) {
            val restrictions = node.targetRestrictions
            if (restrictions != null) {
                if (restrictions.zone.contains(ForgeZoneType.Stack)) {
                    if (game.stack.isEmpty) return true
                } else if (!restrictions.hasCandidates(node)) {
                    return true
                }
            }
            node = node.subAbility
        }
        return false
    }

    fun hasNoLegalCharmModes(ability: SpellAbility): Boolean =
        ability.api == ApiType.Charm && CharmEffect.makePossibleOptions(ability).isEmpty()

    fun usesPaymentSourceReducer(ability: SpellAbility): Boolean {
        val host = ability.hostCard ?: return false
        return host.hasKeyword(Keyword.CONVOKE) || host.hasKeyword(Keyword.IMPROVISE)
    }

    fun canPayWithPaymentSourceReducer(
        ability: SpellAbility,
        player: Player,
    ): Boolean {
        val host = ability.hostCard ?: return false
        val usesConvoke = host.hasKeyword(Keyword.CONVOKE)
        val usesImprovise = host.hasKeyword(Keyword.IMPROVISE)
        if (!usesConvoke && !usesImprovise) return false
        return ActionManaCosts.canPayWithPaymentSourceReducer(
            ability,
            player,
            artifacts = usesImprovise,
            creatures = usesConvoke,
        )
    }

    fun effectiveCostForOffer(
        rail: HandWithAltCost,
        ability: SpellAbility,
        player: Player,
        altCosts: List<AltCostBinding>,
    ): Pair<List<Pair<ManaColor, Int>>, Int> {
        if (rail.kind == AltCostKind.EMERGE) {
            val alternativeGrpId = resolveAltGrpId(rail, altCosts, emptyList())
            val payCostPairs = altCosts.firstOrNull { it.abilityGrpId == alternativeGrpId }?.manaCost.orEmpty()
            return payCostPairs to alternativeGrpId
        }
        val effectiveCost = ActionManaCosts.computeEffectiveCost(ability, player)
        val payCostPairs =
            effectiveCost
                ?.takeIf { !it.isNoCost }
                ?.let(ActionManaCosts::forgeManaCostToPairs)
                .orEmpty()
        return payCostPairs to resolveAltGrpId(rail, altCosts, payCostPairs)
    }

    fun canPayEmerge(
        cost: List<Pair<ManaColor, Int>>,
        player: Player,
    ): Boolean {
        val maxReduction =
            player
                .getCardsIn(ForgeZoneType.Battlefield)
                .filter { it.isCreature }
                .maxOfOrNull { it.getCMC() }
                ?: return false
        return ActionManaCosts.canPayManaCostPairsWithGenericReduction(cost, player, maxReduction)
    }
}
