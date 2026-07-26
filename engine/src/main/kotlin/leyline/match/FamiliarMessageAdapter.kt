package leyline.match

import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Visibility

internal object FamiliarMessageAdapter {
    fun adapt(
        messages: List<GREToClientMessage>,
        seatId: Int = 2,
    ): List<GREToClientMessage> =
        messages
            .filter { it.type != GREMessageType.CastingTimeOptionsReq_695e }
            .map { gre ->
                val builder = gre.toBuilder().clearSystemSeatIds().addSystemSeatIds(seatId)
                if (builder.hasGameStateMessage()) {
                    val gsm = builder.gameStateMessage.toBuilder()
                    val visibleObjects =
                        gsm.gameObjectsList.filter { obj ->
                            obj.visibility != Visibility.Private || obj.viewersList.contains(seatId)
                        }
                    gsm.clearGameObjects().addAllGameObjects(visibleObjects)
                    builder.setGameStateMessage(gsm.build())
                }
                builder.build()
            }
}
