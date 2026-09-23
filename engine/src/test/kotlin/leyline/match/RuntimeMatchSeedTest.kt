package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.config.RuntimeMatchConfig

class RuntimeMatchSeedTest :
    FunSpec({
        tags(UnitTag)

        test("runtime match seed overrides the process setting") {
            runtimeMatchSeed(RuntimeMatchConfig(matchId = "fixed", seed = 42), 7) shouldBe 42
        }

        test("missing runtime seed preserves the process setting") {
            runtimeMatchSeed(RuntimeMatchConfig(matchId = "default"), 7) shouldBe 7
            runtimeMatchSeed(null, null) shouldBe null
        }
    })
