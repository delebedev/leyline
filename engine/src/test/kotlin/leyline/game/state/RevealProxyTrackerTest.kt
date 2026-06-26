package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.state.RevealProxyTracker

class RevealProxyTrackerTest :
    FunSpec({

        tags(UnitTag)

        test("allocate stores and lookup returns the stored id") {
            val tracker = RevealProxyTracker()
            tracker.allocate(ForgeCardId(1), InstanceId(101))
            tracker.allocate(ForgeCardId(2), InstanceId(102))
            assertSoftly {
                tracker.lookup(ForgeCardId(1)) shouldBe InstanceId(101)
                tracker.lookup(ForgeCardId(2)) shouldBe InstanceId(102)
                tracker.size shouldBe 2
                tracker.isEmpty shouldBe false
            }
        }

        test("lookup on missing forge id returns null") {
            val tracker = RevealProxyTracker()
            tracker.lookup(ForgeCardId(999)) shouldBe null
        }

        test("drain returns all instance ids in insertion order and empties the tracker") {
            val tracker = RevealProxyTracker()
            tracker.allocate(ForgeCardId(1), InstanceId(101))
            tracker.allocate(ForgeCardId(2), InstanceId(102))
            assertSoftly {
                tracker.drain() shouldBe listOf(InstanceId(101), InstanceId(102))
                tracker.isEmpty shouldBe true
                tracker.size shouldBe 0
            }
        }

        test("clear empties the tracker without returning values") {
            val tracker = RevealProxyTracker()
            tracker.allocate(ForgeCardId(1), InstanceId(101))
            tracker.clear()
            tracker.isEmpty shouldBe true
        }
    })
