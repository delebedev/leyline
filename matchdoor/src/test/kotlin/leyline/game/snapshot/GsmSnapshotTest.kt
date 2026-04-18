package leyline.game.snapshot

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId

class GsmSnapshotTest :
    FunSpec({

        tags(UnitTag)

        test("forTest builds a snapshot with supplied fields") {
            val snap = GsmSnapshot.forTest(
                matchId = "m-1",
                seats = listOf(SeatSnapshot(SeatId(1), life = 20, startingLife = 20, maxHandSize = 7)),
            )
            assertSoftly {
                snap.matchId shouldBe "m-1"
                snap.seats.single().life shouldBe 20
                snap.zones shouldBe emptyMap()
            }
        }

        test("equals ignores CaptureMarker wallClock") {
            val a = GsmSnapshot.forTest(
                matchId = "m-1",
                capturedAt = CaptureMarker(gsIdBeforeCapture = -1, wallClockMs = 100L),
            )
            val b = GsmSnapshot.forTest(
                matchId = "m-1",
                capturedAt = CaptureMarker(gsIdBeforeCapture = -1, wallClockMs = 999L),
            )
            a shouldBe b
        }

        test("CardSnapshot equality is structural") {
            val c1 = CardSnapshot(ForgeCardId(1), "Grizzly Bears", grpId = 123, owner = SeatId(1), controller = SeatId(1))
            val c2 = CardSnapshot(ForgeCardId(1), "Grizzly Bears", grpId = 123, owner = SeatId(1), controller = SeatId(1))
            c1 shouldBe c2
        }

        test("CardSnapshot snap-diff fields default to false") {
            val card = CardSnapshot(
                forgeCardId = ForgeCardId(1),
                name = "Test",
                grpId = 0,
                owner = SeatId(1),
                controller = SeatId(1),
            )
            card.isOnAdventure shouldBe false
            card.endOfTurnLeavePlay shouldBe false
        }

        test("GsmSnapshot.forTest defaults abilityWordEntries to empty") {
            val snap = GsmSnapshot.forTest()
            snap.abilityWordEntries.shouldBeEmpty()
        }
    })
