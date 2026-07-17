package leyline.game.snapshot

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class PendingTriggerVisualPolicyTest :
    FunSpec({
        tags(UnitTag)

        test("supported delayed-return sources have explicit visual policies") {
            assertSoftly {
                PendingTriggerVisualPolicy.forSourceCard(93_996) shouldBe
                    PendingTriggerVisualPolicy(136_220, displaysAffectedCards = true)
                PendingTriggerVisualPolicy.forSourceCard(102_473) shouldBe
                    PendingTriggerVisualPolicy(204_550, displaysAffectedCards = true)
                PendingTriggerVisualPolicy.forSourceCard(104_978) shouldBe
                    PendingTriggerVisualPolicy(206_386, displaysAffectedCards = true)
                PendingTriggerVisualPolicy.forSourceCard(93_779) shouldBe
                    PendingTriggerVisualPolicy(179_839, displaysAffectedCards = false)
            }
        }

        test("unclassified delayed triggers do not receive return visuals") {
            PendingTriggerVisualPolicy.forSourceCard(1).shouldBeNull()
        }
    })
