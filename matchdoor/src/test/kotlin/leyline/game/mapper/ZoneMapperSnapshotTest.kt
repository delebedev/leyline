package leyline.game.mapper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.ZoneSnapshot
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class ZoneMapperSnapshotTest :
    FunSpec({

        tags(UnitTag)

        test("snapshot carries a hand zone with ordered card contents") {
            val snap = GsmSnapshot.forTest(
                zones = mapOf(
                    ZoneIds.P1_HAND to ZoneSnapshot(
                        id = ZoneIds.P1_HAND,
                        type = ZoneType.Hand,
                        owner = SeatId(1),
                        visibility = Visibility.Private,
                        contents = listOf(ForgeCardId(101), ForgeCardId(102), ForgeCardId(103)),
                    ),
                ),
            )
            snap.zones[ZoneIds.P1_HAND]?.contents shouldBe listOf(ForgeCardId(101), ForgeCardId(102), ForgeCardId(103))
        }

        test("missing zone is null on snapshot") {
            val snap = GsmSnapshot.forTest()
            snap.zones[ZoneIds.P1_HAND] shouldBe null
        }
    })
