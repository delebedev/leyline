package leyline.game.mapping

import leyline.bridge.types.RevealZone
import leyline.bridge.types.SeatId

fun revealZoneId(
    zone: RevealZone,
    ownerSeat: SeatId,
): Int =
    when (zone) {
        RevealZone.HAND -> ZoneIds.handOf(ownerSeat.value)
        RevealZone.LIBRARY -> ZoneIds.libraryOf(ownerSeat.value)
        RevealZone.SIDEBOARD -> ZoneIds.sideboardOf(ownerSeat.value)
        RevealZone.GRAVEYARD -> ZoneIds.graveyardOf(ownerSeat.value)
        RevealZone.BATTLEFIELD -> ZoneIds.BATTLEFIELD
        RevealZone.EXILE -> ZoneIds.EXILE
        RevealZone.COMMAND -> ZoneIds.COMMAND
        RevealZone.STACK -> ZoneIds.STACK
    }
