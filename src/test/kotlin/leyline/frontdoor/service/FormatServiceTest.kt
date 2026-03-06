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
            FormatService.mapArenaFormat("TraditionalExplorer") shouldBe "Pioneer"
            FormatService.mapArenaFormat("TraditionalHistoric") shouldBe "Historic"
        }

        test("mapArenaFormat maps Explorer to Pioneer") {
            FormatService.mapArenaFormat("Explorer") shouldBe "Pioneer"
        }

        test("mapArenaFormat returns null for unmapped formats") {
            FormatService.mapArenaFormat("Timeless").shouldBeNull()
            FormatService.mapArenaFormat("Alchemy").shouldBeNull()
            FormatService.mapArenaFormat("TraditionalTimeless").shouldBeNull()
        }

        test("mapArenaFormat passes through base formats") {
            FormatService.mapArenaFormat("Standard") shouldBe "Standard"
            FormatService.mapArenaFormat("Historic") shouldBe "Historic"
        }
    })
