package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.data.CardProtoBuilder
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.EarthbendProjection
import leyline.game.snapshot.GsmSnapshot
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.Visibility

class EarthbendProjectionTest :
    FunSpec({
        tags(UnitTag)

        test("Earthbend projection adds haste ability and source-card origin") {
            val snap =
                CardSnapshot(
                    forgeCardId = ForgeCardId(199),
                    name = "Swamp",
                    grpId = 102736,
                    owner = SeatId(1),
                    controller = SeatId(1),
                    isOnBattlefield = true,
                    netPower = 4,
                    netToughness = 4,
                    liveCardTypeNumbers = listOf(CardType.Creature.number, CardType.Land_a80b.number),
                )

            val obj =
                ObjectMapper.buildFromSnapshot(
                    cardSnap = snap,
                    instanceId = 199,
                    zoneId = ZoneIds.BATTLEFIELD,
                    ownerSeatId = 1,
                    cardProto = CardProtoBuilder(InMemoryCardRepository()),
                    visibility = Visibility.Public,
                    earthbend = EarthbendProjection(97490, 9, 203),
                )

            assertSoftly {
                obj.cardTypesList shouldContain CardType.Creature
                obj.cardTypesList shouldContain CardType.Land_a80b
                obj.power.value shouldBe 4
                obj.toughness.value shouldBe 4
                obj.uniqueAbilitiesList.first { it.grpId == 9 }.id shouldBe 203
                obj.abilityOriginalCardGrpIdsList shouldContain 97490
            }
        }

        test("cut-scoped Earthbend projection reaches the first mapped object") {
            val cardId = ForgeCardId(199)
            val snapshot =
                GsmSnapshot.forTest(
                    objects =
                        mapOf(
                            cardId to
                                CardSnapshot(
                                    forgeCardId = cardId,
                                    name = "Swamp",
                                    grpId = 102736,
                                    owner = SeatId(1),
                                    controller = SeatId(1),
                                    isOnBattlefield = true,
                                ),
                        ),
                )

            val objectInfo =
                ObjectMapper.buildFromSnapshot(
                    cardSnap = snapshot.objects.getValue(cardId),
                    instanceId = 199,
                    zoneId = ZoneIds.BATTLEFIELD,
                    ownerSeatId = 1,
                    cardProto = CardProtoBuilder(InMemoryCardRepository()),
                    visibility = Visibility.Public,
                    earthbend = EarthbendProjection(97490, 9, 203),
                )

            assertSoftly {
                objectInfo.uniqueAbilitiesList.single { it.grpId == 9 }.id shouldBe 203
                objectInfo.abilityOriginalCardGrpIdsList shouldContain 97490
            }
        }
    })
