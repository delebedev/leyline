package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTestBase
import leyline.testkit.haveManaCost
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class AdventureCastActionTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("adventure card in hand produces both Cast and CastAdventure actions") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                    repeat(3) { base.addCard("Mountain", human, ZoneType.Battlefield) }
                }

            val creatureGrpId =
                b.cardRepository.findGrpIdByName("Ratcatcher Trainee")
                    ?: error("Ratcatcher Trainee not in card registry")
            val trainee =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Ratcatcher Trainee" }
            val traineeIid = b.getOrAllocInstanceId(ForgeCardId(trainee.id)).value

            val actions =
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = GsmSnapshot.capture(game, b, "test", 0),
                    bridge = b,
                )

            val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
            val adventureActions = actions.actionsList.filter { it.actionType == ActionType.CastAdventure }

            castActions shouldHaveSize 1

            adventureActions shouldHaveSize 1
            val adv = adventureActions[0]
            assertSoftly {
                adv.instanceId shouldBe traineeIid
                // grpId = creature face (client can't resolve IsPrimaryCard=0 adventure faces)
                adv.grpId shouldBe creatureGrpId
                adv should haveManaCost(generic = 2, red = 1)
            }
        }

        test("non-adventure card produces no CastAdventure") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Hand)
                    repeat(2) { base.addCard("Forest", human, ZoneType.Battlefield) }
                }

            val actions =
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = GsmSnapshot.capture(game, b, "test", 0),
                    bridge = b,
                )

            actions.actionsList.filter { it.actionType == ActionType.CastAdventure } shouldHaveSize 0
            actions.inactiveActionsList.filter { it.actionType == ActionType.CastAdventure } shouldHaveSize 0
        }

        test("unaffordable adventure action cost does not require pre-seeded activator") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                }

            val actions =
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = GsmSnapshot.capture(game, b, "test", 0),
                    bridge = b,
                )

            actions.inactiveActionsList
                .filter { it.actionType == ActionType.CastAdventure }
                .shouldHaveSize(1)
        }
    })
