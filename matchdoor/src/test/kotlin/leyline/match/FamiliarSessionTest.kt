package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.SeatId
import leyline.infra.ListMessageSink
import wotc.mtgo.gre.external.messaging.Messages.*

class FamiliarSessionTest :
    FunSpec({

        tags(UnitTag)

        test("sendBundledGRE forwards messages to sink") {
            val sink = ListMessageSink()
            val session = FamiliarSession(seatId = SeatId(2), matchId = "m-1", sink = sink)
            val msg = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .build()

            session.sendBundledGRE(listOf(msg))

            sink.messages shouldHaveSize 1
            sink.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
        }

        test("seatId and matchId are accessible") {
            val session = FamiliarSession(seatId = SeatId(2), matchId = "m-42", sink = ListMessageSink())
            session.seatId shouldBe SeatId(2)
            session.matchId shouldBe "m-42"
        }

        test("sendGameOver is no-op — sink stays empty") {
            val sink = ListMessageSink()
            val session = FamiliarSession(seatId = SeatId(2), matchId = "m-1", sink = sink)

            session.sendGameOver(ResultReason.Game_ae0a)

            sink.messages.shouldBeEmpty()
            sink.rawMessages.shouldBeEmpty()
        }

        test("action methods are inherited no-ops") {
            val sink = ListMessageSink()
            val session = FamiliarSession(seatId = SeatId(2), matchId = "m-1", sink = sink)
            val dummyMsg = ClientToGREMessage.getDefaultInstance()

            session.onPerformAction(dummyMsg)
            session.onDeclareAttackers(dummyMsg)
            session.onDeclareBlockers(dummyMsg)
            session.onSelectTargets(dummyMsg)
            session.onSelectN(dummyMsg)
            session.onGroupResp(dummyMsg)
            session.onCancelAction(dummyMsg)
            session.onConcede()
            session.onSettings(dummyMsg)
            session.onMulliganKeep()
            session.onPuzzleStart()

            sink.messages.shouldBeEmpty()
        }

        test("makeGRE builds message with correct fields") {
            val session = FamiliarSession(seatId = SeatId(2), matchId = "m-1", sink = ListMessageSink())
            val gre = session.makeGRE(
                type = GREMessageType.GameStateMessage_695e,
                gsId = 5,
                msgId = 10,
            ) { /* no extra config */ }

            gre.type shouldBe GREMessageType.GameStateMessage_695e
            gre.gameStateId shouldBe 5
            gre.msgId shouldBe 10
            gre.systemSeatIdsList shouldBe listOf(2)
        }
    })
