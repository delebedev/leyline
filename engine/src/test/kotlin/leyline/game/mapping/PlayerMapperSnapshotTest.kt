package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.SeatId
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.ManaPoolEntry
import leyline.game.snapshot.SeatSnapshot
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType

class PlayerMapperSnapshotTest :
    FunSpec({

        tags(UnitTag)

        test("buildFromSnapshot pulls life + startingLife + maxHandSize from the matching seat") {
            val snap =
                GsmSnapshot.forTest(
                    seats =
                        listOf(
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

        test("buildFromSnapshot projects floating mana pool entries") {
            val snap =
                GsmSnapshot.forTest(
                    seats =
                        listOf(
                            SeatSnapshot(
                                SeatId(1),
                                life = 20,
                                startingLife = 20,
                                maxHandSize = 7,
                                manaPool =
                                    listOf(
                                        ManaPoolEntry(
                                            manaId = 10,
                                            color = ManaColor.Green_afc9,
                                            srcInstanceId = 42,
                                            abilityGrpId = 123,
                                            count = 1,
                                            specs = listOf(ManaSpecType.DoesNotEmpty),
                                        ),
                                    ),
                            ),
                        ),
                )

            val mana = PlayerMapper.buildFromSnapshot(snap, seatId = 1).manaPoolList.single()

            assertSoftly {
                mana.manaId shouldBe 10
                mana.color shouldBe ManaColor.Green_afc9
                mana.srcInstanceId shouldBe 42
                mana.abilityGrpId shouldBe 123
                mana.count shouldBe 1
                mana.specsList.single().type shouldBe ManaSpecType.DoesNotEmpty
            }
        }
    })
