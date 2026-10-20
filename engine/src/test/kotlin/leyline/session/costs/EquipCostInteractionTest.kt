package leyline.session.costs

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class EquipCostInteractionTest :
    SessionTest({
        for ((slot, cost, target) in listOf(
            Triple(0, 3, "Isamaru, Hound of Konda"),
            Triple(1, 5, "Grizzly Bears"),
        )) {
            session(
                "selected equip declaration $slot pays $cost and attaches to its intended target",
                forgeCatalog = true,
                puzzle = """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Commander's Plate;Isamaru, Hound of Konda|IsCommander;Grizzly Bears;Plains;Plains;Plains;Plains;Plains
                    humanhand=Forest
                    humanlibrary=Plains;Plains;Plains
                    ailibrary=Mountain;Mountain;Mountain
                """,
            ) {
                val plate = human.battlefield.card("Commander's Plate")
                val repo = bridge.cardRepository
                val data = checkNotNull(repo.findByGrpId(checkNotNull(repo.findGrpIdByName(plate.name))))
                val projection = ActionMapper.buildProjectionFromSnapshot(1, GsmSnapshot.capture(game(), bridge, "test", 0), bridge)
                val offers =
                    projection.offers.filter {
                        it.action.instanceId == bridge.instanceId(plate) && it.action.actionType == ActionType.Activate_add3
                    }
                offers.size shouldBe 2
                val selected = offers.single { it.action.abilityGrpId == data.abilityIds[slot].first }
                selected.action.uniqueAbilityId shouldBe 50 + slot
                submitAction(selected.action)
                selectTargets(listOf(human.battlefield.iid(target)))
                passUntilResolved()
                assertSoftly {
                    plate.attachedTo shouldBe human.battlefield.card(target)
                    human.getCardsIn(ZoneType.Battlefield).count { it.name == "Plains" && it.isTapped } shouldBe cost
                }
                playLand("Forest").shouldBeTrue()
            }
        }

        session("target reduction equips without mana and permits another action", puzzleFile = "data/puzzles/strong-back-equip.pzl") {
            activateAbility("Basilisk Collar").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
            passUntilResolved()
            assertSoftly {
                human.battlefield.card("Basilisk Collar").attachedTo shouldBe human.battlefield.card("Grizzly Bears")
                human.battlefield.card("Grizzly Bears").netPower shouldBe 6
            }
            playLand("Forest").shouldBeTrue()
        }

        session(
            "unenchanted target does not attach when its cost cannot be paid",
            puzzleFile = "data/puzzles/strong-back-equip-unpaid.pzl",
        ) {
            activateAbility("Basilisk Collar").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Centaur Courser")))
            assertSoftly {
                human.battlefield.card("Basilisk Collar").attachedTo shouldBe null
                game().stack.isEmpty.shouldBeTrue()
            }
            playLand("Forest").shouldBeTrue()
        }

        session("unenchanted target pays the unreduced equip cost", puzzleFile = "data/puzzles/strong-back-equip-paid.pzl") {
            activateAbility("Basilisk Collar").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Centaur Courser")))
            passUntilResolved()
            assertSoftly {
                human.battlefield.card("Basilisk Collar").attachedTo shouldBe human.battlefield.card("Centaur Courser")
                human.getCardsIn(ZoneType.Battlefield).count { it.name == "Mountain" && it.isTapped } shouldBe 2
            }
            playLand("Forest").shouldBeTrue()
        }
    })
