package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.config.RuntimeMatchConfig

class RuntimeGameVariantTest :
    FunSpec({
        tags(UnitTag)

        test("spectator runtime format overrides an earlier selected event") {
            runtimeGameVariant(
                RuntimeMatchConfig(matchId = "standard-spectator", spectatorMode = true),
                "Play_Brawl",
            ) shouldBe null

            runtimeGameVariant(
                RuntimeMatchConfig(matchId = "brawl-spectator", gameVariant = "brawl", spectatorMode = true),
                "QuickDraft",
            ) shouldBe "brawl"
        }

        test("player runtime launches honor an explicit Commander variant") {
            runtimeGameVariant(
                RuntimeMatchConfig(matchId = "commander-player", gameVariant = "commander"),
                "Play_Brawl",
            ) shouldBe "commander"
        }

        test("non-spectator matches infer their format from the selected event") {
            runtimeGameVariant(null, "Play_Brawl_Historic") shouldBe "brawl"
        }
    })
