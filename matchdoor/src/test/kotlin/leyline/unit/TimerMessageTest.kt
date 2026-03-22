package leyline.unit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import leyline.game.MessageCounter
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.TimerType

class TimerMessageTest :
    FunSpec({

        tags(UnitTag)

        fun bb() = BundleBuilder(GameBridge(), "test-match", 1)

        test("timerStart builds TimerStateMessage with Decision timer running") {
            val counter = MessageCounter()
            val result = bb().timerStart(counter = counter, durationSec = 30)

            result.messages.size shouldBe 1
            val msg = result.messages[0]
            msg.type shouldBe GREMessageType.TimerStateMessage_695e
            msg.timerStateMessage.seatId shouldBe 1
            msg.timerStateMessage.timersCount shouldBe 1

            val timer = msg.timerStateMessage.timersList[0]
            timer.type shouldBe TimerType.Decision
            timer.durationSec shouldBe 30
            timer.running shouldBe true
            timer.elapsedSec shouldBe 0
        }

        test("timerStop builds TimerStateMessage with running=false") {
            val counter = MessageCounter()
            val result = bb().timerStop(counter = counter)

            result.messages.size shouldBe 1
            val msg = result.messages[0]
            msg.type shouldBe GREMessageType.TimerStateMessage_695e

            val timer = msg.timerStateMessage.timersList[0]
            timer.type shouldBe TimerType.Decision
            timer.running shouldBe false
        }

        test("timerStart uses counter for msgId") {
            val counter = MessageCounter()
            val startMsgId = counter.currentMsgId()

            bb().timerStart(counter = counter)

            // Counter should have advanced by 1 msgId
            counter.currentMsgId() shouldBe startMsgId + 1
        }
    })
