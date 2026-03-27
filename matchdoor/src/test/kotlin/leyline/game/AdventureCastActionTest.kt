package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.UnitTag
import leyline.conformance.ConformanceTestBase
import leyline.game.mapper.ActionMapper
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class AdventureCastActionTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("adventure card in hand produces both Cast and CastAdventure actions") {
            val (b, _, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                repeat(3) { base.addCard("Mountain", human, ZoneType.Battlefield) }
            }

            val creatureGrpId = b.cards.findGrpIdByName("Ratcatcher Trainee")
                ?: error("Ratcatcher Trainee not in card registry")

            val actions = ActionMapper.buildActionList(
                seatId = 1,
                bridge = b,
                checkLegality = true,
            )

            val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
            val adventureActions = actions.actionsList.filter { it.actionType == ActionType.CastAdventure }

            castActions shouldHaveSize 1

            adventureActions shouldHaveSize 1
            val adv = adventureActions[0]
            // grpId = creature face (client can't resolve IsPrimaryCard=0 adventure faces)
            adv.grpId shouldBe creatureGrpId
            // Pest Problem costs {2}{R}: generic=2 + red=1
            adv.manaCostCount shouldBe 2
        }

        test("non-adventure card produces no CastAdventure") {
            val (b, _, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
                repeat(2) { base.addCard("Forest", human, ZoneType.Battlefield) }
            }

            val actions = ActionMapper.buildActionList(
                seatId = 1,
                bridge = b,
                checkLegality = true,
            )

            actions.actionsList.filter { it.actionType == ActionType.CastAdventure } shouldHaveSize 0
        }
    })

/** Unit test for producedToManaColor — used by addManaCostFromForge. */
class ProducedToManaColorTest :
    FunSpec({
        tags(UnitTag)

        test("maps single-letter color codes") {
            ActionMapper.producedToManaColor("R") shouldBe ManaColor.Red_afc9
            ActionMapper.producedToManaColor("W") shouldBe ManaColor.White_afc9
            ActionMapper.producedToManaColor("U") shouldBe ManaColor.Blue_afc9
            ActionMapper.producedToManaColor("B") shouldBe ManaColor.Black_afc9
            ActionMapper.producedToManaColor("G") shouldBe ManaColor.Green_afc9
            ActionMapper.producedToManaColor("C") shouldBe ManaColor.Colorless_afc9
            ActionMapper.producedToManaColor("ANY") shouldBe ManaColor.Generic
        }

        test("case insensitive") {
            ActionMapper.producedToManaColor("r") shouldBe ManaColor.Red_afc9
            ActionMapper.producedToManaColor("any") shouldBe ManaColor.Generic
        }

        test("unknown returns null") {
            ActionMapper.producedToManaColor("X") shouldBe null
            ActionMapper.producedToManaColor("{R}") shouldBe null // caller must strip braces
        }
    })
