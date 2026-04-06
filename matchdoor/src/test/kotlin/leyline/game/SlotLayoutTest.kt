package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class SlotLayoutTest :
    FunSpec({

        tags(UnitTag)

        test("forgeIndexFor returns activated ability index offset by keyword count") {
            val layout = SlotLayout(
                keywordCount = 2,
                activatedCount = 1,
                slots = listOf(
                    SlotEntry(abilityGrpId = 100, textId = 0, kind = SlotKind.Keyword),
                    SlotEntry(abilityGrpId = 101, textId = 0, kind = SlotKind.Keyword),
                    SlotEntry(abilityGrpId = 102, textId = 0, kind = SlotKind.Activated),
                ),
            )
            layout.forgeIndexFor(102) shouldBe 0
        }

        test("forgeIndexFor returns null for unknown abilityGrpId") {
            val layout = SlotLayout(
                keywordCount = 1,
                activatedCount = 0,
                slots = listOf(
                    SlotEntry(abilityGrpId = 100, textId = 0, kind = SlotKind.Keyword),
                ),
            )
            layout.forgeIndexFor(999).shouldBeNull()
        }

        test("forgeIndexFor with zero keywords returns slot index directly") {
            val layout = SlotLayout(
                keywordCount = 0,
                activatedCount = 2,
                slots = listOf(
                    SlotEntry(abilityGrpId = 200, textId = 0, kind = SlotKind.Activated),
                    SlotEntry(abilityGrpId = 201, textId = 0, kind = SlotKind.Activated),
                ),
            )
            layout.forgeIndexFor(200) shouldBe 0
            layout.forgeIndexFor(201) shouldBe 1
        }

        test("forgeIndexFor keyword returns negative offset") {
            val layout = SlotLayout(
                keywordCount = 2,
                activatedCount = 0,
                slots = listOf(
                    SlotEntry(abilityGrpId = 100, textId = 0, kind = SlotKind.Keyword),
                    SlotEntry(abilityGrpId = 101, textId = 0, kind = SlotKind.Keyword),
                ),
            )
            layout.forgeIndexFor(100) shouldBe -2
            layout.forgeIndexFor(101) shouldBe -1
        }
    })
