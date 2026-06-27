package leyline.game.mapping

import forge.card.CardStateName
import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.pickRoomDoorSa
import wotc.mtgo.gre.external.messaging.Messages.ActionType

internal data class RoomDoorCastDescriptor(
    val state: CardStateName,
    val actionType: ActionType,
) {
    fun pickSpellAbility(card: Card): SpellAbility? = pickRoomDoorSa(card, state)

    fun resolveAbilityIndex(
        card: Card,
        player: Player,
    ): Int? {
        val selected = pickSpellAbility(card) ?: return null
        return getAllCastableAbilities(card, player)
            .indexOfFirst { it === selected }
            .takeIf { it >= 0 }
    }
}

internal object RoomDoorCastDescriptors {
    private val all =
        listOf(
            RoomDoorCastDescriptor(CardStateName.LeftSplit, ActionType.CastLeftRoom),
            RoomDoorCastDescriptor(CardStateName.RightSplit, ActionType.CastRightRoom),
        )

    fun forState(state: CardStateName): RoomDoorCastDescriptor? = all.firstOrNull { it.state == state }

    fun forActionType(actionType: ActionType): RoomDoorCastDescriptor? = all.firstOrNull { it.actionType == actionType }
}
