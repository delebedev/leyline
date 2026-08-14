package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class ActionPerformerPolicyTest :
    FunSpec({
        tags(UnitTag)

        test("only explicit auto-resolve delegates repetition after one synchronization horizon") {
            assertSoftly {
                ActionPerformer.shouldDelegateSynchronization(TargetingHandler.PromptResult.NONE, true) shouldBe true
                ActionPerformer.shouldDelegateSynchronization(TargetingHandler.PromptResult.NONE, false) shouldBe false
                ActionPerformer.shouldDelegateSynchronization(TargetingHandler.PromptResult.SENT_TO_CLIENT, true) shouldBe false
            }
        }
    })
