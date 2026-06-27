package leyline.bridge.types

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.PrioritySignal

class PrioritySignalResolvedTest :
    FunSpec({

        tags(UnitTag)

        test("consumePromptResolved returns false before any mark") {
            val signal = PrioritySignal()
            signal.consumePromptResolved() shouldBe false
        }

        test("mark then consume returns true exactly once") {
            val signal = PrioritySignal()
            signal.markPromptResolved()
            signal.consumePromptResolved() shouldBe true
            signal.consumePromptResolved() shouldBe false
        }

        test("multiple marks still consume to true-once (idempotent set)") {
            val signal = PrioritySignal()
            signal.markPromptResolved()
            signal.markPromptResolved()
            signal.consumePromptResolved() shouldBe true
            signal.consumePromptResolved() shouldBe false
        }
    })
