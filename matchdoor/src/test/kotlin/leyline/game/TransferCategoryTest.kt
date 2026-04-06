package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class TransferCategoryTest :
    FunSpec({

        tags(UnitTag)

        test("Resolve keeps same instanceId") {
            TransferCategory.Resolve.keepsSameInstanceId shouldBe true
        }

        test("all other categories realloc instanceId") {
            val nonRealloc = TransferCategory.entries.filter { it.keepsSameInstanceId }
            nonRealloc.map { it.name } shouldBe listOf("Resolve")
        }

        test("CastSpell does not keep same instanceId") {
            TransferCategory.CastSpell.keepsSameInstanceId shouldBe false
        }
    })
