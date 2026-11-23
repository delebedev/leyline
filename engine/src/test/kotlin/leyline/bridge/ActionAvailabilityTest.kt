package leyline.bridge

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest

class ActionAvailabilityTest :
    BoardTest({
        for ((yard, legal) in listOf("ours" to true, "opponent" to false, "empty" to false, "nonartifact" to false)) {
            test("dependent targets require a same-controller artifact pair with $yard graveyard") {
                val board =
                    startWithBoard { _, player, opponent ->
                        addCard("Goblin Welder", player, ZoneType.Battlefield)
                        addCard("Sol Ring", player, ZoneType.Battlefield)
                        when (yard) {
                            "ours" -> addCard("Ornithopter", player, ZoneType.Graveyard)
                            "opponent" -> addCard("Ornithopter", opponent, ZoneType.Graveyard)
                            "nonartifact" -> addCard("Forest", player, ZoneType.Graveyard)
                        }
                    }
                val ability = getNonManaActivatedAbilities(board.human.battlefield.card("Goblin Welder"), board.human).single()
                ability.activatingPlayer = board.human
                assertSoftly {
                    ActionAvailability.hasLegalTargetsAndModes(ability) shouldBe legal
                    ability.targets.size shouldBe 0
                    ability.subAbility.targets.size shouldBe 0
                }
            }
        }

        for (
        case in
        listOf(
            XTargetCase("Repeal", "Island", 3, "Grizzly Bears", true),
            XTargetCase("Disembowel", "Swamp", 3, "Grizzly Bears", true),
            XTargetCase("Repeal", "Island", 2, "Grizzly Bears", false),
            XTargetCase("Repeal", "Island", 3, "Serra Angel", false),
        )
        ) {
            test("${case.spell} with ${case.lands} ${case.land} and only a ${case.target} is offered: ${case.offered}") {
                val board =
                    startWithBoard { _, player, opponent ->
                        addCard(case.spell, player, ZoneType.Hand)
                        repeat(case.lands) { addCard(case.land, player, ZoneType.Battlefield) }
                        addCard(case.target, opponent, ZoneType.Battlefield)
                    }
                val ability =
                    board.human.hand
                        .card(case.spell)
                        .firstSpellAbility
                assertSoftly {
                    ActionAvailability.canExecute(ability, board.human) shouldBe case.offered
                    ability.xManaCostPaid shouldBe null
                    ability.targets.size shouldBe 0
                }
            }
        }

        test("dependent target search can use an opponent pair without choosing targets on the offered ability") {
            val board =
                startWithBoard { _, player, opponent ->
                    addCard("Goblin Welder", player, ZoneType.Battlefield)
                    addCard("Sol Ring", player, ZoneType.Battlefield)
                    addCard("Sol Ring", opponent, ZoneType.Battlefield)
                    addCard("Ornithopter", opponent, ZoneType.Graveyard)
                }
            val ability = getNonManaActivatedAbilities(board.human.battlefield.card("Goblin Welder"), board.human).single()
            ability.activatingPlayer = board.human
            assertSoftly {
                ActionAvailability.hasLegalTargetsAndModes(ability) shouldBe true
                ability.targets.size shouldBe 0
                ability.subAbility.targets.size shouldBe 0
            }
        }
    })

private data class XTargetCase(
    val spell: String,
    val land: String,
    val lands: Int,
    val target: String,
    val offered: Boolean,
)
