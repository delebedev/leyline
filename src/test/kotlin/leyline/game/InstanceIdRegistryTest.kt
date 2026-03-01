package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag

/** Unit tests for [InstanceIdRegistry]. */
class InstanceIdRegistryTest :
    FunSpec({

        tags(UnitTag)

        test("resetAll returns old IDs and clears state") {
            val reg = InstanceIdRegistry(startId = 100)
            val id1 = reg.getOrAlloc(1)
            val id2 = reg.getOrAlloc(2)
            val id3 = reg.getOrAlloc(3)

            val deleted = reg.resetAll()

            deleted.shouldContainExactlyInAnyOrder(id1, id2, id3)
            // After reset, same forgeCardIds get fresh IDs
            reg.getOrAlloc(1) shouldNotBe id1
            reg.getOrAlloc(2) shouldNotBe id2
        }

        test("resetAll clears reverse map") {
            val reg = InstanceIdRegistry(startId = 100)
            val oldId = reg.getOrAlloc(42)

            reg.resetAll()

            // Old reverse lookup should return null
            reg.getForgeCardId(oldId).shouldBeNull()
            // New allocation should be resolvable
            val newId = reg.getOrAlloc(42)
            reg.getForgeCardId(newId).shouldNotBeNull()
        }
    })
