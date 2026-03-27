package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId

class GameBridgeApiContractTest :
    FunSpec({

        tags(UnitTag)

        test("zero-arg seat-1 bridge aliases stay removed") {
            val methodNames = GameBridge::class.java.declaredMethods.map { it.name }

            methodNames shouldContain "seat"
            methodNames shouldNotContain "getActionBridge"
            methodNames shouldNotContain "getPromptBridge"
        }

        test("reveal draining is seat-aware and seat zero drains remaining queues") {
            val bridge = GameBridge()
            bridge.configureSyntheticSeat(2)

            bridge.promptBridge(1).recordReveal(listOf(ForgeCardId(101)), ownerSeatId = SeatId(1))
            bridge.promptBridge(2).recordReveal(listOf(ForgeCardId(202)), ownerSeatId = SeatId(2))

            bridge.drainReveals(1).map { it.ownerSeatId } shouldBe listOf(SeatId(1))
            bridge.drainReveals(0).map { it.ownerSeatId } shouldBe listOf(SeatId(2))
            bridge.drainReveals(0) shouldBe emptyList()
        }
    })
