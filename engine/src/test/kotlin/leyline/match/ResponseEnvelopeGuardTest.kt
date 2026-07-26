package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.*

class ResponseEnvelopeGuardTest :
    FunSpec({
        tags(UnitTag)

        test("accepts a response correlated to the latest prompt") {
            val counter = MessageCounter()
            val sink = CollectingSink()

            ResponseEnvelopeGuard.rejectMismatch(response(respId = 17), 17, counter, sink) shouldBe false
            sink.messages shouldBe emptyList()
        }

        test("rejects a response correlated to another prompt") {
            val counter = MessageCounter(initialMsgId = 20)
            val sink = CollectingSink()
            val invalid = response(respId = 16)

            ResponseEnvelopeGuard.rejectMismatch(invalid, 17, counter, sink) shouldBe true

            val rejection = sink.messages.single()
            assertSoftly {
                rejection.type shouldBe GREMessageType.IllegalRequest
                rejection.illegalRequestMessage.reason shouldBe FailureReason.ReqRespMismatch
                rejection.illegalRequestMessage.invalidMessage shouldBe invalid
                rejection.msgId shouldBe 21
                rejection.prompt.promptId shouldBe 3
                rejection.prompt.parametersList
                    .single()
                    .numberValue shouldBe FailureReason.ReqRespMismatch.number
            }
        }

        test("rejects a response when no prompt has been emitted") {
            val sink = CollectingSink()

            ResponseEnvelopeGuard.rejectMismatch(
                response(respId = 0),
                0,
                MessageCounter(),
                sink,
            ) shouldBe true
            sink.messages
                .single()
                .illegalRequestMessage.reason shouldBe FailureReason.ReqRespMismatch
        }

        test("does not correlate control messages") {
            val counter = MessageCounter()
            val sink = CollectingSink()
            val control =
                ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.CancelActionReq_097b)
                    .build()

            ResponseEnvelopeGuard.rejectMismatch(control, 17, counter, sink) shouldBe false
            sink.messages shouldBe emptyList()
        }

        test("correlates numeric input responses") {
            val counter = MessageCounter()
            val sink = CollectingSink()

            ResponseEnvelopeGuard.rejectMismatch(
                response(respId = 16, type = ClientMessageType.NumericInputResp_097b),
                17,
                counter,
                sink,
            ) shouldBe true
        }
    })

private fun response(
    respId: Int,
    type: ClientMessageType = ClientMessageType.PerformActionResp_097b,
): ClientToGREMessage =
    ClientToGREMessage
        .newBuilder()
        .setType(type)
        .setSystemSeatId(1)
        .setRespId(respId)
        .build()

private class CollectingSink : GreMessageSink {
    val messages = mutableListOf<GREToClientMessage>()

    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        this.messages += messages
    }

    override fun sendMatchProgress(message: MatchServiceToClientMessage) {}

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) = Unit

    override fun sendBundle(result: BundleBuilder.BundleResult) {
        messages += result.messages
    }

    override fun sendGameOver(reason: ResultReason) = Unit

    override fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(type)
            .setGameStateId(gsId)
            .setMsgId(msgId)
            .apply(configure)
            .build()
}
