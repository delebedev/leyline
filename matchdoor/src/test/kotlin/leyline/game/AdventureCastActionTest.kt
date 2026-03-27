package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.conformance.ConformanceTestBase
import leyline.conformance.TestCardRegistry
import leyline.game.mapper.ActionMapper
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class AdventureCastActionTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("adventure card in hand produces both Cast and CastAdventure actions") {
            // Pre-register the adventure face so nameToGrpId can resolve it
            val adventureGrpId = TestCardRegistry.ensureCardRegistered("Ratcatcher Trainee") + 1
            TestCardRegistry.repo.register(adventureGrpId, "Pest Problem")

            val (b, _, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                repeat(3) { base.addCard("Mountain", human, ZoneType.Battlefield) }
            }

            val actions = ActionMapper.buildActionList(
                seatId = 1,
                bridge = b,
                checkLegality = true,
            )

            val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
            val adventureActions = actions.actionsList.filter { it.actionType == ActionType.CastAdventure }

            castActions shouldHaveSize 1

            adventureActions shouldHaveSize 1
            // Real server sends no manaCost in CastAdventure — client derives from card DB
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
