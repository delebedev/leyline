package leyline.game.mapping

import forge.card.CardStateName
import forge.game.card.Card
import forge.game.player.Player
import leyline.bridge.chooseCastAbility
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.getPlayableManaAbilities
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.NaiveGsmAction
import leyline.game.NaiveGsmActionKind
import leyline.game.state.GameBridge
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Reads the engine once at a playback cut and returns protocol-neutral action
 * facts. Owner-side compilation never dereferences these cards again.
 */
internal object NaiveGsmActionCapture {
    fun materialize(
        seatId: Int,
        bridge: GameBridge,
    ): List<NaiveGsmAction> {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return terminalActions()
        val out = mutableListOf<NaiveGsmAction>()
        appendManaActions(out, player, bridge)

        val hand = player.getZone(ForgeZoneType.Hand).cards
        hand.filterNot { it.type.isLand }.forEach { card ->
            appendBaseCast(out, card, player, bridge)
            appendAdventure(out, card, player)
        }
        hand.forEach { card -> appendMdfc(out, card, player, bridge) }
        out += terminalActions()
        return out
    }

    private fun appendManaActions(
        out: MutableList<NaiveGsmAction>,
        player: Player,
        bridge: GameBridge,
    ) {
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (card.isTapped) continue
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val grpId = bridge.resolveGrpId(card, instanceId)
            val data = bridge.cardRepository.findByGrpId(grpId)
            val registry = bridge.abilityRegistryFor(card, data)
            val basicLandAbilityGrpId = ActivatedActionEmitter.basicLandAbilityGrpId(card)
            for (ability in getPlayableManaAbilities(card, player)) {
                if (ActivatedActionEmitter.producedManaColors(ability).isEmpty()) continue
                out +=
                    NaiveGsmAction(
                        kind = NaiveGsmActionKind.ACTIVATE_MANA,
                        forgeCardId = ForgeCardId(card.id),
                        abilityGrpId = registry?.forSpellAbility(ability.definitionId) ?: basicLandAbilityGrpId,
                    )
            }
        }
    }

    private fun appendBaseCast(
        out: MutableList<NaiveGsmAction>,
        card: Card,
        player: Player,
        bridge: GameBridge,
    ) {
        val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
        val grpId = bridge.resolveGrpId(card, instanceId)
        val data = bridge.cardRepository.findByGrpId(grpId)
        val ability = chooseCastAbility(card, player, checkTiming = false)
        out +=
            NaiveGsmAction(
                kind = NaiveGsmActionKind.CAST,
                forgeCardId = ForgeCardId(card.id),
                manaCost = CastDisplayCost.requirementValues(ability, player, data),
            )
    }

    private fun appendAdventure(
        out: MutableList<NaiveGsmAction>,
        card: Card,
        player: Player,
    ) {
        if (!card.isAdventureCard) return
        val ability = card.getState(CardStateName.Secondary)?.nonManaAbilities?.firstOrNull() ?: return
        out +=
            NaiveGsmAction(
                kind = NaiveGsmActionKind.CAST_ADVENTURE,
                forgeCardId = ForgeCardId(card.id),
                manaCost = CastDisplayCost.requirementValues(ability, player, null),
            )
    }

    private fun appendMdfc(
        out: MutableList<NaiveGsmAction>,
        card: Card,
        player: Player,
        bridge: GameBridge,
    ) {
        if (!card.isModal || !card.hasState(CardStateName.Backside)) return
        val ability =
            getAllCastableAbilities(card, player, checkTiming = false)
                .firstOrNull { it.hostCard?.isModal == true && it.cardStateName == CardStateName.Backside }
                ?: return
        val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
        val parentGrpId = bridge.resolveGrpId(card, instanceId)
        val backName = ability.cardState?.name
        val backGrpId =
            backName?.let(bridge.cardRepository::findGrpIdByNameAnyFace)
                ?: bridge.cardRepository.findLinkedFaces(parentGrpId).firstOrNull { it != parentGrpId }
        val abilityGrpId =
            backGrpId
                ?.let(bridge.cardRepository::findByGrpId)
                ?.abilityIds
                ?.firstOrNull()
                ?.first
                ?: 0
        out +=
            NaiveGsmAction(
                kind = NaiveGsmActionKind.CAST_MDFC,
                forgeCardId = ForgeCardId(card.id),
                sourceForgeCardId = ForgeCardId(card.id),
                abilityGrpId = abilityGrpId,
                manaCost =
                    CastDisplayCost.requirementValues(
                        ability,
                        player,
                        printed = null,
                        abilityGrpId = abilityGrpId.takeIf { it != 0 },
                    ),
            )
    }

    private fun terminalActions(): List<NaiveGsmAction> =
        listOf(
            NaiveGsmAction(NaiveGsmActionKind.PASS),
            NaiveGsmAction(NaiveGsmActionKind.FLOAT_MANA),
        )
}
