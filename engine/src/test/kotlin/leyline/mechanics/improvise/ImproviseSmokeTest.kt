package leyline.mechanics.improvise

import forge.game.zone.ZoneType
import io.kotest.matchers.collections.shouldNotBeEmpty
import leyline.bridge.getAllCastableAbilities
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer

class ImproviseSmokeTest :
    BoardTest({
        test("Forge surfaces Ironheart as a castable Improvise card") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(4) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Manalith", human, ZoneType.Battlefield)
                    addCard("Ironheart, Clever Champion", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.name == "Ironheart, Clever Champion" }

            getAllCastableAbilities(card, human).shouldNotBeEmpty()
        }
    })
