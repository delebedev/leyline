package leyline.bridge

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.testkit.BoardTest

class ActionManaCostsBoardTest :
    BoardTest({
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
