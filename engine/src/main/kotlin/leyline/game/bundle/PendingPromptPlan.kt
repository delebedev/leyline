package leyline.game.bundle

import leyline.bridge.types.SeatId
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

internal object PendingPromptPlan {
    fun build(
        counter: MessageCounter,
        seatId: SeatId,
        promptMessageType: GREMessageType,
        configurePrompt: (GREToClientMessage.Builder) -> Unit,
    ): List<GREToClientMessage> {
        val link = counter.nextGameStateLink()
        val pendingGsm =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(link.gsId)
                .setPrevGameStateId(link.prevGsId)
                .setPendingMessageCount(1)
                .setUpdate(GameStateUpdate.SendAndRecord)
                .build()

        return listOf(
            gre(
                seatId,
                GREMessageType.GameStateMessage_695e,
                link.gsId,
                counter.nextMsgId(),
            ) {
                it.gameStateMessage = pendingGsm
            },
            gre(
                seatId,
                promptMessageType,
                link.gsId,
                counter.nextMsgId(),
                configurePrompt,
            ),
        )
    }

    private fun gre(
        seatId: SeatId,
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val builder =
            GREToClientMessage
                .newBuilder()
                .setType(type)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .addSystemSeatIds(seatId.value)
        configure(builder)
        return builder.build()
    }
}
