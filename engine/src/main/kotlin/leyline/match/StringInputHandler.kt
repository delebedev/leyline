package leyline.match

import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.types.SeatId
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.FailureReason

/** Delivers one raw name to the engine thread; Forge validation stays there. */
class StringInputHandler(
    private val ctx: SessionContext,
    private val seatId: SeatId,
) {
    fun onStringInputResp(message: ClientToGREMessage): Boolean {
        val coordinator = ctx.bridge.cutCoordinator
        if (message.systemSeatId != seatId.value || seatId != ctx.bridge.seating.humanSeat) {
            coordinator.publishIllegalRequest(seatId, message, FailureReason.InvalidSeatId_a63a)
            return false
        }
        val pending =
            coordinator
                .currentBlockingInteraction()
                ?.takeIf { it.interaction is BlockingInteraction.CardName }
        if (pending == null || pending.gameStateId != message.gameStateId) {
            coordinator.publishIllegalRequest(seatId, message, FailureReason.ReqRespMismatch)
            return false
        }
        return coordinator.submitCardNameAnswer(pending.interactionId, message.gameStateId, message.stringInputResp.inputValue)
    }
}
