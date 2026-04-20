package leyline.game.mapping

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.SeatId

class ZoneIdsTest :
    FunSpec({
        tags(UnitTag)

        test("handOf(1) == P1_HAND") { ZoneIds.handOf(1) shouldBe ZoneIds.P1_HAND }
        test("handOf(2) == P2_HAND") { ZoneIds.handOf(2) shouldBe ZoneIds.P2_HAND }

        test("libraryOf(1) == P1_LIBRARY") { ZoneIds.libraryOf(1) shouldBe ZoneIds.P1_LIBRARY }
        test("libraryOf(2) == P2_LIBRARY") { ZoneIds.libraryOf(2) shouldBe ZoneIds.P2_LIBRARY }

        test("graveyardOf(1) == P1_GRAVEYARD") { ZoneIds.graveyardOf(1) shouldBe ZoneIds.P1_GRAVEYARD }
        test("graveyardOf(2) == P2_GRAVEYARD") { ZoneIds.graveyardOf(2) shouldBe ZoneIds.P2_GRAVEYARD }

        test("handOf(SeatId(1)) delegates to P1_HAND") { ZoneIds.handOf(SeatId(1)) shouldBe ZoneIds.P1_HAND }
        test("libraryOf(SeatId(2)) delegates to P2_LIBRARY") { ZoneIds.libraryOf(SeatId(2)) shouldBe ZoneIds.P2_LIBRARY }
        test("graveyardOf(SeatId(1)) delegates to P1_GRAVEYARD") { ZoneIds.graveyardOf(SeatId(1)) shouldBe ZoneIds.P1_GRAVEYARD }

        test("revealedOf(1) == REVEALED_P1") { ZoneIds.revealedOf(1) shouldBe ZoneIds.REVEALED_P1 }
        test("revealedOf(2) == REVEALED_P2") { ZoneIds.revealedOf(2) shouldBe ZoneIds.REVEALED_P2 }
        test("revealedOf(SeatId(1)) delegates to REVEALED_P1") { ZoneIds.revealedOf(SeatId(1)) shouldBe ZoneIds.REVEALED_P1 }
        test("revealedOf(SeatId(2)) delegates to REVEALED_P2") { ZoneIds.revealedOf(SeatId(2)) shouldBe ZoneIds.REVEALED_P2 }
    })
