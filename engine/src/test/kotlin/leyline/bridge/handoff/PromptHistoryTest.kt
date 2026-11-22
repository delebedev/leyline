package leyline.bridge.handoff

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class PromptHistoryTest :
    FunSpec({
        tags(UnitTag)

        fun request(message: String) = PromptRequest(promptType = "confirm", message = message, options = listOf("Yes", "No"))

        test("defaults outlive routine prompts and count what their own cap evicts") {
            val history = PromptHistory(capacity = 2, defaultCapacity = 2)
            history.record(request("first"), PromptCallStatus.DEFAULTED_POLICY, listOf(0))
            repeat(3) { history.record(request("asked $it"), PromptCallStatus.RESPONDED, listOf(1)) }
            history.record(request("second"), PromptCallStatus.DEFAULTED_POLICY, listOf(0))
            history.record(request("third"), PromptCallStatus.DEFAULTED_POLICY, listOf(0))

            assertSoftly {
                history.defaults.map { it.message } shouldBe listOf("second", "third")
                history.droppedDefaults shouldBe 1
                history.snapshot.map { it.message } shouldBe listOf("second", "third")
            }
            history.clear()
            history.droppedDefaults shouldBe 0
        }
    })
