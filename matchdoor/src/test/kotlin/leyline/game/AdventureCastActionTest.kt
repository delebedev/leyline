package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.conformance.ConformanceTestBase
import leyline.game.mapper.ActionMapper
import wotc.mtgo.gre.external.messaging.Messages.ActionType

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
