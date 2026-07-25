package leyline.game.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId

class PendingSpellEventRegistryTest :
    FunSpec({
        tags(UnitTag)

        test("consume removes the exact face grpId fallback") {
            val registry = PendingSpellEventRegistry<String>()
            val cardId = ForgeCardId(1)
            val unrelatedCardId = ForgeCardId(2)

            registry.record(cardId, 95537, "omen")
            registry.find(unrelatedCardId, 95537) shouldBe "omen"

            registry.consume(cardId)

            registry.find(unrelatedCardId, 95537).shouldBeNull()
        }
    })
