package leyline.game.event

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId

class PendingStackAbilityRegistryTest :
    FunSpec({
        tags(UnitTag)

        test("trigger context is visible until consumed") {
            val registry = PendingStackAbilityRegistry()

            registry.recordTrigger(7, ForgeCardId(42), abilityGrpId = 101)

            assertSoftly {
                registry.isTriggerResolving(7) shouldBe true
                registry.consume(7) shouldBe
                    PendingStackAbilityContext(
                        kind = PendingStackAbilityKind.Trigger,
                        sourceCardId = ForgeCardId(42),
                        abilityGrpId = 101,
                    )
                registry.isTriggerResolving(7) shouldBe false
                registry.consume(7) shouldBe null
            }
        }

        test("activation context preserves ability grpId") {
            val registry = PendingStackAbilityRegistry()

            registry.recordActivation(9, ForgeCardId(77), abilityGrpId = 202)

            assertSoftly {
                registry.isTriggerResolving(9) shouldBe false
                registry.consume(9) shouldBe
                    PendingStackAbilityContext(
                        kind = PendingStackAbilityKind.Activation,
                        sourceCardId = ForgeCardId(77),
                        abilityGrpId = 202,
                    )
            }
        }

        test("ability lookup can stay trigger-only") {
            val registry = PendingStackAbilityRegistry()

            registry.recordActivation(8, ForgeCardId(42), abilityGrpId = 777)
            registry.recordTrigger(9, ForgeCardId(42), abilityGrpId = 777)

            registry.abilityIdFor(ForgeCardId(42), 777, PendingStackAbilityKind.Trigger) shouldBe 9
        }
    })
