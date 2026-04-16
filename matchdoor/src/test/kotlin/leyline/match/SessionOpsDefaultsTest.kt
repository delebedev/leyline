package leyline.match

import forge.game.Game
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import leyline.UnitTag
import leyline.bridge.SeatId
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import leyline.game.InMemoryCardRepository
import leyline.game.MessageCounter
import wotc.mtgo.gre.external.messaging.Messages.*

class SessionOpsDefaultsTest :
    FunSpec({

        tags(UnitTag)

        val ops = object : SessionOps {
            override val seatId = SeatId(1)
            override val matchId = "test-match"
            override var counter = MessageCounter()

            override fun sendBundledGRE(messages: List<GREToClientMessage>) {}
            override fun sendRealGameState(bridge: GameBridge, revealForSeat: Int?) {}
            override fun sendBundle(result: BundleBuilder.BundleResult) {}
            override fun sendGameOver(reason: ResultReason) {}
            override fun traceEvent(type: MatchEventType, game: Game, detail: String) {}
            override fun paceDelay(multiplier: Int) {}

            override fun makeGRE(
                type: GREMessageType,
                gsId: Int,
                msgId: Int,
                configure: (GREToClientMessage.Builder) -> Unit,
            ): GREToClientMessage = GREToClientMessage.getDefaultInstance()
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

        test("gameBridge default is null") {
            ops.gameBridge.shouldBeNull()
        }

        test("recorder default is null") {
            ops.recorder.shouldBeNull()
        }

        test("connectBridge default is no-op") {
            shouldNotThrowAny { ops.connectBridge(GameBridge(cardRepository = InMemoryCardRepository())) }
        }
    })
