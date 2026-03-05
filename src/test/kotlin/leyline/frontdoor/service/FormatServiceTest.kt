package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.FdTag

class FormatServiceTest :
    FunSpec({
        tags(FdTag)

        test("resolve returns null for null format") {
            FormatService.resolve(null).shouldBeNull()
        }

        test("resolve returns null for blank format") {
            FormatService.resolve("").shouldBeNull()
            FormatService.resolve("  ").shouldBeNull()
        }

        test("mapArenaFormat strips Traditional prefix") {
            FormatService.mapArenaFormat("TraditionalStandard") shouldBe "Standard"
            FormatService.mapArenaFormat("TraditionalExplorer") shouldBe "Explorer"
            FormatService.mapArenaFormat("TraditionalHistoric") shouldBe "Historic"
            FormatService.mapArenaFormat("TraditionalTimeless") shouldBe "Timeless"
        }

        test("mapArenaFormat passes through base formats") {
            FormatService.mapArenaFormat("Standard") shouldBe "Standard"
            FormatService.mapArenaFormat("Explorer") shouldBe "Explorer"
            FormatService.mapArenaFormat("Historic") shouldBe "Historic"
            FormatService.mapArenaFormat("Timeless") shouldBe "Timeless"
            FormatService.mapArenaFormat("Alchemy") shouldBe "Alchemy"
        }
    })
