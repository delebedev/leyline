package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.bundle.MessageCounter
import wotc.mtgo.gre.external.messaging.Messages.*

class ResponseEnvelopeGuardTest :
    FunSpec({
        tags(UnitTag)

        test("accepts a response correlated to the latest prompt") {
            val counter = MessageCounter()
            counter.markPromptMsgId(17)

            ResponseEnvelopeGuard.mismatchReason(response(respId = 17), counter) shouldBe null
            counter.responsesAccepted() shouldBe 1
        }

        test("classifies a response correlated to another prompt without advancing response state") {
            val counter = MessageCounter(initialMsgId = 20)
            counter.markPromptMsgId(17)
            val before = counter.snapshot()

            ResponseEnvelopeGuard.mismatchReason(response(respId = 16), counter) shouldBe FailureReason.ReqRespMismatch

            assertSoftly {
                counter.snapshot() shouldBe before
                counter.responsesAccepted() shouldBe 0
            }
        }

        test("rejects a response when no prompt has been emitted") {
            ResponseEnvelopeGuard.mismatchReason(response(respId = 0), MessageCounter()) shouldBe FailureReason.ReqRespMismatch
        }

        test("does not correlate control messages") {
            val counter = MessageCounter()
            counter.markPromptMsgId(17)
            val control =
                ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.CancelActionReq_097b)
                    .build()

            ResponseEnvelopeGuard.mismatchReason(control, counter) shouldBe null
            counter.responsesAccepted() shouldBe 0
        }

        test("correlates numeric input responses") {
            val counter = MessageCounter()
            counter.markPromptMsgId(17)
            ResponseEnvelopeGuard.mismatchReason(
                response(respId = 16, type = ClientMessageType.NumericInputResp_097b),
                counter,
            ) shouldBe FailureReason.ReqRespMismatch
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
