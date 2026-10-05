package leyline.session.costs

import forge.game.zone.ZoneType
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

class EquipCostInteractionTest :
    SessionTest({
        session("target reduction equips without mana and permits another action", puzzleFile = "data/puzzles/strong-back-equip.pzl") {
            activateAbility("Basilisk Collar").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
            passUntilResolved()
            human.battlefield.card("Basilisk Collar").attachedTo shouldBe human.battlefield.card("Grizzly Bears")
            human.battlefield.card("Grizzly Bears").netPower shouldBe 6
            playLand("Forest").shouldBeTrue()
        }

        session(
            "unenchanted target does not attach when its cost cannot be paid",
            puzzleFile = "data/puzzles/strong-back-equip-unpaid.pzl",
        ) {
            activateAbility("Basilisk Collar").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Centaur Courser")))
            human.battlefield.card("Basilisk Collar").attachedTo shouldBe null
            game().stack.isEmpty.shouldBeTrue()
            playLand("Forest").shouldBeTrue()
        }

        session("unenchanted target pays the unreduced equip cost", puzzleFile = "data/puzzles/strong-back-equip-paid.pzl") {
            activateAbility("Basilisk Collar").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Centaur Courser")))
            passUntilResolved()
            human.battlefield.card("Basilisk Collar").attachedTo shouldBe human.battlefield.card("Centaur Courser")
            human.getCardsIn(ZoneType.Battlefield).count { it.name == "Mountain" && it.isTapped } shouldBe 2
            playLand("Forest").shouldBeTrue()
        }
    })
