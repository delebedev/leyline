package leyline.match

import leyline.bridge.findCard
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.getPlayableManaAbilities
import leyline.bridge.handoff.GameActionBridge.ActionOffer
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.pickMdfcBackSpellAbility
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.mapping.AbilityGrpIdMode
import leyline.game.mapping.AltGrpIdSource
import leyline.game.mapping.CastRail
import leyline.game.mapping.CastRails
import leyline.game.mapping.RoomDoorCastDescriptors
import leyline.game.mapping.ZoneCastRail
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq

/** Builds short-lived engine commands while the priority wait keeps Forge stable. */
object ActionOfferCatalog {
    fun build(
        actions: ActionsAvailableReq,
        bridge: GameBridge,
        seatId: Int,
    ): List<ActionOffer> =
        actions.actionsList.map { action ->
            offer(action, bridge, seatId) ?: ActionOffer(action, PlayerAction.PassPriority)
        }

    @Suppress("CyclomaticComplexMethod", "ElseCaseInsteadOfExhaustiveWhen", "ReturnCount")
    private fun offer(
        action: Action,
        bridge: GameBridge,
        seatId: Int,
    ): ActionOffer? {
        val cardId = bridge.getForgeCardId(InstanceId(action.instanceId))
        val game = bridge.getGame()
        val player = bridge.getPlayer(SeatId(seatId))

        fun cast(
            index: Int?,
            ability: forge.game.spellability.SpellAbility?,
        ): ActionOffer? = cardId?.let { ActionOffer(action, PlayerAction.CastSpell(it, index, ability = ability)) }
        return when (action.actionType) {
            ActionType.Pass, ActionType.FloatMana -> ActionOffer(action, PlayerAction.PassPriority)
            ActionType.Play_add3, ActionType.PlayMdfc -> cardId?.let { ActionOffer(action, PlayerAction.PlayLand(it)) }
            ActionType.Cast -> {
                val card = cardId?.let { game?.let { g -> findCard(g, it) } } ?: return null
                val p = player ?: return null
                val index =
                    if (action.alternativeGrpId == 0) {
                        resolveHandCastIndex(action, card, p, bridge)
                    } else {
                        resolveAltCostIndex(action, card, p, bridge)
                    }
                cast(index, getAllCastableAbilities(card, p).getOrNull(index ?: -1))
            }
            ActionType.Activate_add3, ActionType.SpecialTurnFaceUp_add3 -> {
                val card = cardId?.let { game?.let { g -> findCard(g, it) } } ?: return null
                val p = player ?: return null
                val abilities = getNonManaActivatedAbilities(card, p)
                val index =
                    if (action.actionType == ActionType.SpecialTurnFaceUp_add3) {
                        val turnFaceUp = card.spellAbilities.firstOrNull { it.isDisguiseUp }
                        abilities.indexOfFirst { it === turnFaceUp }
                    } else {
                        val data =
                            bridge.cardRepository.findByGrpId(
                                action.grpId.takeIf { it != 0 } ?: bridge.resolveGrpId(card, action.instanceId),
                            )
                        val registry = bridge.abilityRegistryFor(card, data)
                        if (action.abilityGrpId ==
                            0
                        ) {
                            0
                        } else {
                            abilities
                                .indexOfFirst { registry?.forSpellAbility(it.id) == action.abilityGrpId }
                                .takeIf { it >= 0 }
                                ?: 0
                        }
                    }
                abilities.getOrNull(index)?.let { ability ->
                    ActionOffer(
                        action,
                        PlayerAction.ActivateAbility(
                            cardId,
                            index,
                            ability = if (action.actionType == ActionType.SpecialTurnFaceUp_add3) null else ability,
                        ),
                        action.abilityGrpId.takeIf { it != 0 },
                        ability.id,
                    )
                }
            }
            ActionType.ActivateMana -> {
                val card = cardId?.let { game?.let { g -> findCard(g, it) } } ?: return null
                val p = player ?: return null
                val data = bridge.cardRepository.findByGrpId(bridge.resolveGrpId(card, action.instanceId))
                val registry = bridge.abilityRegistryFor(card, data)
                val manaAbilities = getPlayableManaAbilities(card, p)
                val index =
                    if (action.abilityGrpId ==
                        0
                    ) {
                        0
                    } else {
                        manaAbilities
                            .indexOfFirst { registry?.forSpellAbility(it.id) == action.abilityGrpId }
                            .takeIf { it >= 0 }
                            ?: 0
                    }
                cardId.takeIf { index >= 0 }?.let {
                    ActionOffer(
                        action,
                        PlayerAction.ActivateMana(it, index, ability = manaAbilities[index]),
                    )
                }
            }
            ActionType.CastMdfc, ActionType.CastAdventure, ActionType.CastOmen, ActionType.CastLeftRoom, ActionType.CastRightRoom -> {
                val card = cardId?.let { game?.let { g -> findCard(g, it) } } ?: return null
                val p = player ?: return null
                val candidates = getAllCastableAbilities(card, p)
                val index =
                    when (action.actionType) {
                        ActionType.CastMdfc -> pickMdfcBackSpellAbility(card)?.let(candidates::indexOf)
                        ActionType.CastAdventure -> candidates.indexOfFirst { it.isAdventure }
                        ActionType.CastOmen -> candidates.indexOfFirst { it.isOmen }
                        else -> RoomDoorCastDescriptors.forActionType(action.actionType)?.resolveAbilityIndex(card, p)
                    }?.takeIf { it >= 0 }
                cast(index, candidates.getOrNull(index ?: -1))
            }
            // Keep the prompt bound even if Leyline does not yet project this
            // GRE action. The response cannot execute an inferred command.
            else -> ActionOffer(action, PlayerAction.PassPriority)
        }
    }

    private fun resolveHandCastIndex(
        action: Action,
        card: forge.game.card.Card,
        player: forge.game.player.Player,
        bridge: GameBridge,
    ): Int? {
        val grpId = bridge.resolveGrpId(card, action.instanceId)
        val (candidates, _) =
            leyline.game.mapping.ActionMapper.buildIndexedHandCastActionsForCard(
                card = card,
                player = player,
                instanceId = action.instanceId,
                grpId = grpId,
                checkLegality = true,
                idResolver = { forgeId -> bridge.getOrAllocInstanceId(forgeId) },
                grpIdResolver = { candidate ->
                    val iid = bridge.getOrAllocInstanceId(ForgeCardId(candidate.id)).value
                    GrpId(bridge.resolveGrpId(candidate, iid))
                },
                cardDataLookup = { candidateGrpId -> bridge.cardRepository.findByGrpId(candidateGrpId.value) },
                abilityRegistryLookup = { candidate, cardData -> bridge.abilityRegistryFor(candidate, cardData) },
            )
        return candidates.firstOrNull { expected -> equivalentCastAction(expected.action, action) }?.abilityIndex
            ?: getAllCastableAbilities(card, player).indexOfFirst { it.isCastFaceDown }.takeIf { isFaceDownCastCost(action) && it >= 0 }
    }

    private fun resolveAltCostIndex(
        action: Action,
        card: forge.game.card.Card,
        player: forge.game.player.Player,
        bridge: GameBridge,
    ): Int? {
        val rails: List<CastRail> =
            if (action.alternativeGrpId == 149) {
                CastRails.all.filter { rail ->
                    rail is ZoneCastRail &&
                        rail.altGrpIdSource is AltGrpIdSource.Universal149 &&
                        (rail.abilityGrpIdMode as? AbilityGrpIdMode.FixedKeyword)?.baseId == action.abilityGrpId
                }
            } else {
                CastRails.all.filter { it.kind.keywordBaseId == action.alternativeGrpId }.ifEmpty {
                    val info = bridge.cardRepository.findAbilityInfo(action.alternativeGrpId) ?: return null
                    CastRails.all.filter { it.kind.keywordBaseId == info.baseId }
                }
            }
        return getAllCastableAbilities(card, player)
            .indexOfFirst { candidate ->
                rails.any { it.saPredicate(candidate) }
            }.takeIf { it >= 0 }
    }

    private fun equivalentCastAction(
        expected: Action,
        actual: Action,
    ): Boolean {
        val manaCostMatches = actual.manaCostCount == 0 || expected.manaCostList == actual.manaCostList
        val autoTapMatches = !actual.hasAutoTapSolution() || expected.autoTapSolution == actual.autoTapSolution
        return expected.actionType == actual.actionType &&
            expected.instanceId == actual.instanceId &&
            expected.grpId == actual.grpId &&
            expected.abilityGrpId == actual.abilityGrpId &&
            expected.alternativeGrpId == actual.alternativeGrpId &&
            manaCostMatches &&
            autoTapMatches
    }

    private fun isFaceDownCastCost(action: Action): Boolean =
        action.manaCostCount > 0 &&
            action.manaCostList.all { it.colorList == listOf(wotc.mtgo.gre.external.messaging.Messages.ManaColor.Generic) } &&
            action.manaCostList.sumOf { it.count } == 3
}
