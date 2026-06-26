package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.state.GameBridge

class GameBridgeApiContractTest :
    FunSpec({

        tags(UnitTag)

        test("zero-arg seat-1 bridge aliases stay removed") {
            val methodNames = GameBridge::class.java.declaredMethods.map { it.name }

            assertSoftly {
                methodNames.any { it == "seat" || it.startsWith("seat-") }.shouldBeTrue()
                methodNames.filter { it == "getActionBridge" || it == "getPromptBridge" } shouldBe emptyList()
            }
        }

        test("reveal draining is seat-aware and seat zero drains remaining queues") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            bridge.configureSyntheticSeat(SeatId(2))

            bridge.promptBridge(SeatId(1)).recordReveal(listOf(ForgeCardId(101)), ownerSeatId = SeatId(1))
            bridge.promptBridge(SeatId(2)).recordReveal(listOf(ForgeCardId(202)), ownerSeatId = SeatId(2))

            assertSoftly {
                bridge.drainReveals(1).map { it.ownerSeatId } shouldBe listOf(SeatId(1))
                bridge.drainReveals(0).map { it.ownerSeatId } shouldBe listOf(SeatId(2))
                bridge.drainReveals(0) shouldBe emptyList()
            }
        }

        test("disabled action timeout keeps prompt fail-safe finite") {
            val bridge = GameBridge(bridgeTimeoutMs = null, cardRepository = InMemoryCardRepository())

            assertSoftly {
                bridge.actionBridge(SeatId(1)).getTimeoutMs() shouldBe null
                bridge.promptBridge(SeatId(1)).getTimeoutMs() shouldBe GameBridge.DEFAULT_PROMPT_FAILSAFE_TIMEOUT_MS
            }
        }
    })
