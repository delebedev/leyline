package leyline.game.mapper

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.SeatId
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.SeatSnapshot

class PlayerMapperSnapshotTest :
    FunSpec({

        tags(UnitTag)

        test("buildFromSnapshot pulls life + startingLife + maxHandSize from the matching seat") {
            val snap = GsmSnapshot.forTest(
                seats = listOf(
                    SeatSnapshot(SeatId(1), life = 15, startingLife = 20, maxHandSize = 7),
                    SeatSnapshot(SeatId(2), life = 12, startingLife = 20, maxHandSize = 7),
                ),
            )
            val info = PlayerMapper.buildFromSnapshot(snap, seatId = 1)
            assertSoftly {
                info.systemSeatNumber shouldBe 1
                info.lifeTotal shouldBe 15
                info.startingLifeTotal shouldBe 20
                info.maxHandSize shouldBe 7
            }
        }

        test("buildFromSnapshot returns bare seatId when seat missing") {
            val snap = GsmSnapshot.forTest(seats = emptyList())
            val info = PlayerMapper.buildFromSnapshot(snap, seatId = 1)
            info.systemSeatNumber shouldBe 1
            info.lifeTotal shouldBe 0
        }
    })
