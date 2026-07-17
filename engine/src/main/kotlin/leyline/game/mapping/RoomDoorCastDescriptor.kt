package leyline.game.mapping

import forge.card.CardStateName
import wotc.mtgo.gre.external.messaging.Messages.ActionType

internal data class RoomDoorCastDescriptor(
    val state: CardStateName,
    val actionType: ActionType,
)

internal object RoomDoorCastDescriptors {
    private val all =
        listOf(
            RoomDoorCastDescriptor(CardStateName.LeftSplit, ActionType.CastLeftRoom),
            RoomDoorCastDescriptor(CardStateName.RightSplit, ActionType.CastRightRoom),
        )

    fun forState(state: CardStateName): RoomDoorCastDescriptor? = all.firstOrNull { it.state == state }
}
