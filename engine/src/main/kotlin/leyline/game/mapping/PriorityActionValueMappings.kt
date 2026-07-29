package leyline.game.mapping

import leyline.game.PriorityCastKind
import leyline.game.PriorityManaColor
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

@Suppress("ElseCaseInsteadOfExhaustiveWhen")
internal fun ManaColor.toPriorityManaColor(): PriorityManaColor =
    when (this) {
        ManaColor.White_afc9 -> PriorityManaColor.WHITE
        ManaColor.Blue_afc9 -> PriorityManaColor.BLUE
        ManaColor.Black_afc9 -> PriorityManaColor.BLACK
        ManaColor.Red_afc9 -> PriorityManaColor.RED
        ManaColor.Green_afc9 -> PriorityManaColor.GREEN
        ManaColor.Generic -> PriorityManaColor.GENERIC
        ManaColor.Colorless_afc9 -> PriorityManaColor.COLORLESS
        ManaColor.Snow_afc9 -> PriorityManaColor.SNOW
        ManaColor.TwoGeneric -> PriorityManaColor.TWO_GENERIC
        else -> error("Unsupported priority mana color: $this")
    }

@Suppress("ElseCaseInsteadOfExhaustiveWhen")
internal fun ActionType.toPriorityCastKind(): PriorityCastKind =
    when (this) {
        ActionType.Cast -> PriorityCastKind.CAST
        ActionType.CastAdventure -> PriorityCastKind.ADVENTURE
        ActionType.CastMdfc -> PriorityCastKind.MDFC
        ActionType.CastLeftRoom -> PriorityCastKind.LEFT_ROOM
        ActionType.CastRightRoom -> PriorityCastKind.RIGHT_ROOM
        ActionType.CastOmen -> PriorityCastKind.OMEN
        else -> error("Unsupported priority cast action type: $this")
    }
