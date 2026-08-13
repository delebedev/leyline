package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.InstanceId

/**
 * Unit pins for the [ZoneHandoff] data shape — the realloc + retire +
 * zone-assignment bundle that callers fold into [ProjectionState]. End-to-end
 * coverage of the detector's handoff consumption lives in the existing zone-
 * transfer fixtures (PureDiffReplayTest, BoundCardParityTest, the per-keyword
 * tests).
 */
class ZoneHandoffTest :
    FunSpec({
        tags(UnitTag)

        test("keepingSameInstanceId returns no-op handoff with old==new and null limbo retirement") {
            val handoff = ZoneHandoff.keepingSameInstanceId(InstanceId(42), destinationZoneId = 7)

            assertSoftly {
                handoff.realloc.old.value shouldBe 42
                handoff.realloc.new.value shouldBe 42
                handoff.limboRetirement shouldBe null
                handoff.zoneAssignment shouldBe (InstanceId(42) to 7)
            }
        }

        test("fromRealloc with old != new produces full retire + zone-assign handoff") {
            val realloc = InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(200))
            val handoff = ZoneHandoff.fromRealloc(realloc, destinationZoneId = 7)

            assertSoftly {
                handoff.realloc shouldBe realloc
                handoff.limboRetirement shouldBe InstanceId(100)
                handoff.zoneAssignment shouldBe (InstanceId(200) to 7)
            }
        }

        test("fromRealloc with old == new mirrors keepingSameInstanceId — no limbo retirement") {
            val noopRealloc = InstanceIdRegistry.IdReallocation(InstanceId(42), InstanceId(42))
            val handoff = ZoneHandoff.fromRealloc(noopRealloc, destinationZoneId = 7)

            assertSoftly {
                handoff.realloc shouldBe noopRealloc
                handoff.limboRetirement shouldBe null
                handoff.zoneAssignment shouldBe (InstanceId(42) to 7)
            }
        }
    })
