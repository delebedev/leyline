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
