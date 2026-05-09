package leyline.game.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class AbilityLineageRegistryTest :
    FunSpec({
        tags(UnitTag)

        test("consume returns and removes recorded ability identity") {
            val registry = AbilityLineageRegistry()
            val identity =
                AbilityWireIdentity(
                    abilityIid = 132,
                    sourceIidAtCreate = 118,
                    sourceZoneAtCreate = 28,
                    abilityGrpId = 147760,
                )

            registry.record(identity)

            registry.consume(132) shouldBe identity
            registry.consume(132) shouldBe null
        }

        test("record replaces existing identity for same ability iid") {
            val registry = AbilityLineageRegistry()
            registry.record(AbilityWireIdentity(abilityIid = 132, sourceIidAtCreate = 118, sourceZoneAtCreate = 28, abilityGrpId = 1))
            val latest = AbilityWireIdentity(abilityIid = 132, sourceIidAtCreate = 134, sourceZoneAtCreate = 28, abilityGrpId = 2)

            registry.record(latest)

            registry.consume(132) shouldBe latest
        }
    })
