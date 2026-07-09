package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.*

class SessionOpsDefaultsTest :
    FunSpec({

        tags(UnitTag)

        val ops =
            object : SessionOps {
                override val seatId = SeatId(1)
                override val matchId = "test-match"
                override var counter = MessageCounter()

                override fun sendBundledGRE(messages: List<GREToClientMessage>) {}

                override fun sendRealGameState(
                    bridge: GameBridge,
                    revealForSeat: Int?,
                ) {}

                override fun sendBundle(result: BundleBuilder.BundleResult) {}

                override fun sendGameOver(reason: ResultReason) {}

                override fun paceDelay(multiplier: Int) {}
            }

        val dummyMsg = ClientToGREMessage.getDefaultInstance()

        test("onPerformAction default is no-op") {
            shouldNotThrowAny { ops.onPerformAction(dummyMsg) }
        }

        test("onDeclareAttackers default is no-op") {
            shouldNotThrowAny { ops.onDeclareAttackers(dummyMsg) }
        }

        test("onDeclareBlockers default is no-op") {
            shouldNotThrowAny { ops.onDeclareBlockers(dummyMsg) }
        }

        test("onSelectTargets default is no-op") {
            shouldNotThrowAny { ops.onSelectTargets(dummyMsg) }
        }

        test("onSelectN default is no-op") {
            shouldNotThrowAny { ops.onSelectN(dummyMsg) }
        }

        test("onGroupResp default is no-op") {
            shouldNotThrowAny { ops.onGroupResp(dummyMsg) }
        }

        test("onCancelAction default is no-op") {
            shouldNotThrowAny { ops.onCancelAction(dummyMsg) }
        }

        test("onConcede default is no-op") {
            shouldNotThrowAny { ops.onConcede() }
        }

        test("onSettings default is no-op") {
            shouldNotThrowAny { ops.onSettings(dummyMsg) }
        }

        test("onMulliganKeep default is no-op") {
            shouldNotThrowAny { ops.onMulliganKeep() }
        }

        test("onPuzzleStart default is no-op") {
            shouldNotThrowAny { ops.onPuzzleStart() }
        }

        test("recorder default is null") {
            ops.recorder.shouldBeNull()
        }

        test("makeGRE default builds envelope fields") {
            val gre = ops.makeGRE(GREMessageType.GameStateMessage_695e, gsId = 7, msgId = 11) {}

            assertSoftly(gre) {
                type shouldBe GREMessageType.GameStateMessage_695e
                msgId shouldBe 11
                gameStateId shouldBe 7
                systemSeatIdsList shouldBe listOf(1)
            }
        }

        // --- ActionReceiver-only smoke tests ------------------------------------
        // Pins that the on* no-op defaults live on ActionReceiver itself (not
        // just reachable through SessionOps), so consumers narrowed to
        // ActionReceiver don't need to override every method.

        val actionReceiver = object : ActionReceiver {}

        test("ActionReceiver.onPerformAction default is no-op") {
            shouldNotThrowAny { actionReceiver.onPerformAction(dummyMsg) }
        }

        test("ActionReceiver.onDeclareAttackers default is no-op") {
            shouldNotThrowAny { actionReceiver.onDeclareAttackers(dummyMsg) }
        }

        test("ActionReceiver.onSelectTargets default is no-op") {
            shouldNotThrowAny { actionReceiver.onSelectTargets(dummyMsg) }
        }

        test("ActionReceiver.onConcede default is no-op") {
            shouldNotThrowAny { actionReceiver.onConcede() }
        }

        test("ActionReceiver.onMulliganKeep default is no-op") {
            shouldNotThrowAny { actionReceiver.onMulliganKeep() }
        }
    })
