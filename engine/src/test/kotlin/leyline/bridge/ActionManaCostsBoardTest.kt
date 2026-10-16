package leyline.bridge

import forge.game.cost.Cost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.testkit.BoardTest

class ActionManaCostsBoardTest :
    BoardTest({
        for ((cardName, lands) in listOf(
            "Spiteful Visions" to listOf("Swamp", "Island", "Island", "Island"),
            "Creakwood Liege" to listOf("Swamp", "Forest", "Island", "Island"),
            "Growing Ranks" to listOf("Forest", "Island", "Island", "Island"),
        )) {
            test("$cardName requires every ordinary hybrid pip") {
                val board =
                    startWithBoard { _, player, _ ->
                        addCard(cardName, player, ZoneType.Hand)
                        lands.forEach { addCard(it, player, ZoneType.Battlefield) }
                    }
                val spell =
                    board.human.hand
                        .card(cardName)
                        .firstSpellAbility
                spell.activatingPlayer = board.human

                ActionManaCosts.canPayManaCost(spell, board.human) shouldBe false
                ActionAvailability.canExecute(spell, board.human) shouldBe false
            }
        }

        for ((cardName, lands) in listOf(
            "Spiteful Visions" to listOf("Swamp", "Mountain", "Island", "Island"),
            "Creakwood Liege" to listOf("Swamp", "Forest", "Forest", "Island"),
            "Growing Ranks" to listOf("Forest", "Plains", "Island", "Island"),
        )) {
            test("$cardName accepts mixed ordinary hybrid payment") {
                val board =
                    startWithBoard { _, player, _ ->
                        addCard(cardName, player, ZoneType.Hand)
                        lands.forEach { addCard(it, player, ZoneType.Battlefield) }
                    }
                val spell =
                    board.human.hand
                        .card(cardName)
                        .firstSpellAbility
                spell.activatingPlayer = board.human

                ActionManaCosts.canPayManaCost(spell, board.human) shouldBe true
            }
        }

        test("two-generic hybrid fallback cannot omit an ordinary hybrid requirement") {
            val board =
                startWithBoard { _, player, _ ->
                    addCard("Standing Troops", player, ZoneType.Hand)
                    repeat(2) { addCard("Island", player, ZoneType.Battlefield) }
                }
            val spell =
                board.human.hand
                    .card("Standing Troops")
                    .firstSpellAbility
            spell.activatingPlayer = board.human
            spell.payCosts = Cost("2B BR", false)

            ActionManaCosts.canPayManaCost(spell, board.human) shouldBe false
        }

        test("equip affordability considers the enchanted target without committing it") {
            val board = startPuzzleAtMain1FromResource("data/puzzles/strong-back-equip.pzl")
            val player = board.human
            val collar = player.battlefield.card("Basilisk Collar")
            val equip = getNonManaActivatedAbilities(collar, player).single { it.isEquip }
            equip.activatingPlayer = player
            val targets = equip.targets

            assertSoftly {
                ActionManaCosts.canPayManaCost(equip, player) shouldBe true
                equip.targets shouldBeSameInstanceAs targets
                equip.targets.size shouldBe 0
                ActionManaCosts.computeEffectiveCost(equip, player)?.genericCost shouldBe 2
            }
        }

        test("selected equip target determines the exact payable cost") {
            val board = startPuzzleAtMain1FromResource("data/puzzles/strong-back-equip.pzl")
            val player = board.human
            val collar = player.battlefield.card("Basilisk Collar")
            val equip = getNonManaActivatedAbilities(collar, player).single { it.isEquip }
            equip.activatingPlayer = player
            equip.targets.add(player.battlefield.card("Grizzly Bears"))
            val targets = equip.targets

            assertSoftly {
                ActionManaCosts.computeEffectiveCost(equip, player)?.genericCost shouldBe 0
                ActionManaCosts.canPayManaCost(equip, player) shouldBe true
                equip.targets shouldBeSameInstanceAs targets
                equip.targets.size shouldBe 1
            }
        }

        test("an unenchanted equip target remains unaffordable without mana") {
            val board =
                startWithBoard { _, player, _ ->
                    addCard("Grizzly Bears", player, ZoneType.Battlefield)
                    addCard("Basilisk Collar", player, ZoneType.Battlefield)
                }
            val player = board.human
            val collar = player.battlefield.card("Basilisk Collar")
            val equip = getNonManaActivatedAbilities(collar, player).single { it.isEquip }
            equip.activatingPlayer = player
            ActionManaCosts.canPayManaCost(equip, player) shouldBe false
            equip.targets.add(player.battlefield.card("Grizzly Bears"))
            ActionManaCosts.computeEffectiveCost(equip, player)?.genericCost shouldBe 2
            ActionManaCosts.canPayManaCost(equip, player) shouldBe false
        }

        test("an illegal enchanted target cannot make equip affordable") {
            val board = startPuzzleAtMain1FromResource("data/puzzles/strong-back-equip.pzl")
            val player = board.human
            player.battlefield.card("Grizzly Bears").addIntrinsicKeyword("Shroud")
            val equip = getNonManaActivatedAbilities(player.battlefield.card("Basilisk Collar"), player).single { it.isEquip }
            equip.activatingPlayer = player
            val targets = equip.targets

            assertSoftly {
                ActionManaCosts.canPayManaCost(equip, player) shouldBe false
                equip.targets shouldBeSameInstanceAs targets
                equip.targets.size shouldBe 0
            }
        }

        test("a selected unenchanted target cannot borrow another target's reduction") {
            val board = startPuzzleAtMain1FromResource("data/puzzles/strong-back-equip-unpaid.pzl")
            val player = board.human
            val equip = getNonManaActivatedAbilities(player.battlefield.card("Basilisk Collar"), player).single { it.isEquip }
            equip.activatingPlayer = player
            equip.targets.add(player.battlefield.card("Centaur Courser"))
            val targets = equip.targets

            assertSoftly {
                ActionManaCosts.canPayManaCost(equip, player) shouldBe false
                equip.targets shouldBeSameInstanceAs targets
                equip.targets.targetCards.single() shouldBe player.battlefield.card("Centaur Courser")
            }
        }
    })
