package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId

class ActionPerformerTest :
    FunSpec({

        tags(UnitTag)

        test("requiredAbilityCastAction fails closed when specialized face ability is missing") {
            val cardId = ForgeCardId(42)

            assertSoftly {
                requiredAbilityCastAction(cardId, null) shouldBe PlayerAction.PassPriority
                requiredAbilityCastAction(cardId, 2) shouldBe PlayerAction.CastSpell(cardId, abilityId = 2)
            }
        }
    })
