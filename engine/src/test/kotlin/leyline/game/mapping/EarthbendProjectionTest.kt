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
                    earthbend =
                        EarthbendProjection(
                            sourceCardGrpId = 97490,
                            hasteAbilityGrpId = 9,
                            uniqueAbilityId = 203,
                        ),
                )

            val obj =
                ObjectMapper.buildFromSnapshot(
                    cardSnap = snap,
                    instanceId = 199,
                    zoneId = ZoneIds.BATTLEFIELD,
                    ownerSeatId = 1,
                    cardProto = CardProtoBuilder(InMemoryCardRepository()),
                    visibility = Visibility.Public,
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
    })
