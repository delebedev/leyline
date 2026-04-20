package leyline.bridge.types

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class SeatIdTest :
    FunSpec({
        tags(UnitTag)

        test("opponent flips seat 1 to 2") {
            SeatId(1).opponent shouldBe SeatId(2)
        }

        test("opponent flips seat 2 to 1") {
            SeatId(2).opponent shouldBe SeatId(1)
        }
    })
