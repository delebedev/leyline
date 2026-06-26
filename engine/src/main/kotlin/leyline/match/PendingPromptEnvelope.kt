package leyline.match

import wotc.mtgo.gre.external.messaging.Messages.*

internal object PendingPromptEnvelope {
    fun sendBare(
        sink: GreMessageSink,
        counters: SessionCounters,
        promptMessageType: GREMessageType,
        configurePrompt: (GREToClientMessage.Builder) -> Unit,
    ) {
        val link = counters.counter.nextGameStateLink()
        val pendingGsm =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(link.gsId)
                .setPrevGameStateId(link.prevGsId)
                .setPendingMessageCount(1)
                .setUpdate(GameStateUpdate.SendAndRecord)
                .build()

        val gsmGre =
            sink.makeGRE(
                GREMessageType.GameStateMessage_695e,
                link.gsId,
                counters.counter.nextMsgId(),
            ) {
                it.gameStateMessage = pendingGsm
            }

        val promptGre =
            sink.makeGRE(
                promptMessageType,
                link.gsId,
                counters.counter.nextMsgId(),
                configurePrompt,
            )

        sink.sendBundledGRE(listOf(gsmGre, promptGre))
    }
}
