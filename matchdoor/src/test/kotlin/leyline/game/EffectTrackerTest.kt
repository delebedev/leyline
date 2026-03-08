package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class EffectTrackerTest : FunSpec({

    tags(UnitTag)

    test("allocates synthetic IDs starting at 7002") {
        val tracker = EffectTracker()
        tracker.nextEffectId() shouldBe 7002
        tracker.nextEffectId() shouldBe 7003
        tracker.nextEffectId() shouldBe 7004
    }

    test("reset clears ID counter back to 7002") {
        val tracker = EffectTracker()
        tracker.nextEffectId() // 7002
        tracker.nextEffectId() // 7003
        tracker.resetAll()
        tracker.nextEffectId() shouldBe 7002
    }
})
