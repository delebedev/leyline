package leyline.match

import leyline.game.bundle.MessageCounter
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.FailureReason
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.IllegalRequestMessage
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.PromptParameter

/** Client messages whose `respId` identifies the prompt they answer. */
internal val CORRELATED_CLIENT_MESSAGE_TYPES: Set<ClientMessageType> =
    setOf(
        ClientMessageType.PerformActionResp_097b,
        ClientMessageType.DeclareAttackersResp_097b,
        ClientMessageType.SubmitAttackersReq,
        ClientMessageType.DeclareBlockersResp_097b,
        ClientMessageType.SubmitBlockersReq,
        ClientMessageType.SelectTargetsResp_097b,
        ClientMessageType.SubmitTargetsReq,
        ClientMessageType.EffectCostResp_097b,
        ClientMessageType.GroupResp_097b,
        ClientMessageType.SelectNresp,
        ClientMessageType.OrderResp_097b,
        ClientMessageType.DistributionResp_097b,
        ClientMessageType.CastingTimeOptionsResp_097b,
        ClientMessageType.SearchResp_097b,
        ClientMessageType.SearchFromGroupsResp_097b,
        ClientMessageType.AssignDamageResp_097b,
        ClientMessageType.OptionalActionResp,
        ClientMessageType.NumericInputResp_097b,
        ClientMessageType.MulliganResp_097b,
        ClientMessageType.ChooseStartingPlayerResp_097b,
    )

/** Rejects a prompt response that does not identify the most recent prompt. */
internal object ResponseEnvelopeGuard {
    private val log = LoggerFactory.getLogger(ResponseEnvelopeGuard::class.java)

    fun rejectMismatch(
        message: ClientToGREMessage,
        counter: MessageCounter,
        sink: GreMessageSink,
    ): Boolean {
        if (message.type !in CORRELATED_CLIENT_MESSAGE_TYPES) return false
        val expectedRespId = counter.lastPromptMsgId()
        if (expectedRespId != 0 && message.respId == expectedRespId) {
            counter.markResponseAccepted(message.respId)
            return false
        }

        log.warn(
            "ResponseEnvelopeGuard: {} respId={} expected={}",
            message.type,
            message.respId,
            expectedRespId,
        )
        reject(message, FailureReason.ReqRespMismatch, counter, sink)
        return true
    }

    /** Emit a protocol rejection containing the invalid client message. */
    fun reject(
        message: ClientToGREMessage,
        reason: FailureReason,
        counter: MessageCounter,
        sink: GreMessageSink,
    ) {
        sink.sendBundledGRE(listOf(illegalRequest(message, reason, counter)))
    }

    private fun illegalRequest(
        invalid: ClientToGREMessage,
        reason: FailureReason,
        counter: MessageCounter,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.IllegalRequest)
            .setMsgId(counter.nextMsgId())
            .setGameStateId(counter.currentGsId())
            .addSystemSeatIds(invalid.systemSeatId)
            .setPrompt(
                Prompt
                    .newBuilder()
                    .setPromptId(3)
                    .addParameters(
                        PromptParameter
                            .newBuilder()
                            .setParameterName("FailureReason")
                            .setType(ParameterType.Number)
                            .setNumberValue(reason.number),
                    ),
            ).setIllegalRequestMessage(
                IllegalRequestMessage
                    .newBuilder()
                    .setInvalidMessage(invalid)
                    .setReason(reason),
            ).build()
}
