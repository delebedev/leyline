package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId

class AbilityLineageRegistryTest :
    FunSpec({
        tags(UnitTag)

        fun id(
            saId: Int = 42,
            abilityIid: Int = 1042,
            sourceForgeId: Int = 7,
            sourceIid: Int = 372,
            sourceZone: Int = 28,
            grp: Int = 99999,
        ) = AbilityWireIdentity(
            abilityForgeId = saId,
            abilityIid = InstanceId(abilityIid),
            sourceForgeId = ForgeCardId(sourceForgeId),
            sourceIidAtCreate = InstanceId(sourceIid),
            sourceZoneAtCreate = sourceZone,
            abilityGrpId = grp,
        )

        test("record then lookup returns the stored identity") {
            val reg = AbilityLineageRegistry()
            val ident = id()
            reg.record(ident)
            reg.lookup(42) shouldBe ident
        }

        test("lookup returns null for an unrecorded ability") {
            AbilityLineageRegistry().lookup(999).shouldBeNull()
        }

        test("consume returns the entry and removes it") {
            val reg = AbilityLineageRegistry()
            val ident = id()
            reg.record(ident)
            assertSoftly {
                reg.consume(42) shouldBe ident
                reg.lookup(42).shouldBeNull()
                reg.consume(42).shouldBeNull()
            }
        }

        test("multiple in-flight abilities from the same source resolve to distinct ids") {
            val reg = AbilityLineageRegistry()
            reg.record(id(saId = 42, abilityIid = 1042))
            reg.record(id(saId = 43, abilityIid = 1043))
            assertSoftly {
                reg.lookup(42)?.abilityIid shouldBe InstanceId(1042)
                reg.lookup(43)?.abilityIid shouldBe InstanceId(1043)
            }
        }

        test("record overwrites a prior entry for the same SA id") {
            val reg = AbilityLineageRegistry()
            reg.record(id(saId = 42, abilityIid = 1042))
            reg.record(id(saId = 42, abilityIid = 9999, sourceIid = 555))
            val current = reg.lookup(42)!!
            assertSoftly {
                current.abilityIid shouldBe InstanceId(9999)
                current.sourceIidAtCreate shouldBe InstanceId(555)
            }
        }

        test("clear removes all entries") {
            val reg = AbilityLineageRegistry()
            reg.record(id(saId = 42))
            reg.record(id(saId = 43, abilityIid = 1043))
            reg.clear()
            assertSoftly {
                reg.lookup(42).shouldBeNull()
                reg.lookup(43).shouldBeNull()
            }
        }
    })
