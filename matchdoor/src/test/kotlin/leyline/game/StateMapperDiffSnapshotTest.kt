package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.types.SeatId
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.SeatSnapshot

/**
 * Snap-fixture unit tests for the snap-vs-snap diff path. Built with
 * GsmSnapshot.forTest literals — no Forge boot.
 *
 * These tests cover the diff's structural snap-equality semantics. End-to-end
 * correctness lives in the conformance + integration suites; Task 8's
 * integration sweep is the production safety net.
 */
class StateMapperDiffSnapshotTest :
    FunSpec({
        tags(UnitTag)

        fun seat(seatNum: Int, life: Int) = SeatSnapshot(
            seatId = SeatId(seatNum),
            life = life,
            startingLife = 20,
            maxHandSize = 7,
        )

        test("snap equality detects no change") {
            val a = GsmSnapshot.forTest(
                matchId = "m",
                gameStateId = 5,
                seats = listOf(seat(1, 20), seat(2, 20)),
            )
            val b = GsmSnapshot.forTest(
                matchId = "m",
                gameStateId = 5,
                seats = listOf(seat(1, 20), seat(2, 20)),
            )
            a shouldBe b
        }

        test("snap inequality detects life total change") {
            val prev = GsmSnapshot.forTest(seats = listOf(seat(1, 20)))
            val cur = GsmSnapshot.forTest(seats = listOf(seat(1, 17)))
            prev shouldNotBe cur
        }

        test("snap gameStateId roundtrips") {
            val snap = GsmSnapshot.forTest(gameStateId = 42)
            snap.gameStateId shouldBe 42
        }
    })
