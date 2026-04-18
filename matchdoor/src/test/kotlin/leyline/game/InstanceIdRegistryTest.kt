package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId

/** Unit tests for [InstanceIdRegistry]. */
class InstanceIdRegistryTest :
    FunSpec({

        tags(UnitTag)

        test("resetAll returns old IDs and clears state") {
            val reg = InstanceIdRegistry(startId = 100)
            val id1 = reg.getOrAlloc(ForgeCardId(1))
            val id2 = reg.getOrAlloc(ForgeCardId(2))
            val id3 = reg.getOrAlloc(ForgeCardId(3))

            val deleted = reg.resetAll()

            deleted.shouldContainExactlyInAnyOrder(id1, id2, id3)
            // After reset, same forgeCardIds get fresh IDs
            reg.getOrAlloc(ForgeCardId(1)) shouldNotBe id1
            reg.getOrAlloc(ForgeCardId(2)) shouldNotBe id2
        }

        test("resetAll clears reverse map") {
            val reg = InstanceIdRegistry(startId = 100)
            val oldId = reg.getOrAlloc(ForgeCardId(42))

            reg.resetAll()

            // Old reverse lookup should return null
            reg.getForgeCardId(oldId).shouldBeNull()
            // New allocation should be resolvable
            val newId = reg.getOrAlloc(ForgeCardId(42))
            reg.getForgeCardId(newId).shouldNotBeNull()
        }

        test("planRealloc + applyRealloc matches realloc behaviour") {
            val a = InstanceIdRegistry(startId = 100)
            val b = InstanceIdRegistry(startId = 100)
            val fid = ForgeCardId(42)
            a.getOrAlloc(fid) // seeds id=100
            b.getOrAlloc(fid)

            val directResult = a.realloc(fid)
            val planned = b.planRealloc(fid)
            b.applyRealloc(planned)

            assertSoftly {
                directResult shouldBe planned
                a.activeSnapshot()[fid] shouldBe b.activeSnapshot()[fid]
                a.getForgeCardId(directResult.old) shouldBe b.getForgeCardId(planned.old)
            }
        }

        test("planReallocBatch threads counter forward across fids") {
            val r = InstanceIdRegistry(startId = 100)
            val f1 = ForgeCardId(1)
            r.getOrAlloc(f1) // → 100
            val f2 = ForgeCardId(2)
            r.getOrAlloc(f2) // → 101

            val plans = r.planReallocBatch(listOf(f1, f2))

            assertSoftly {
                plans[0].old shouldBe InstanceId(100)
                plans[0].new shouldBe InstanceId(102)
                plans[1].old shouldBe InstanceId(101)
                plans[1].new shouldBe InstanceId(103)
            }
        }

        test("planRealloc on unknown fid mirrors getOrAlloc") {
            val r = InstanceIdRegistry(startId = 100)
            val fid = ForgeCardId(99)
            val plan = r.planRealloc(fid)
            plan.old shouldBe plan.new
        }
    })
