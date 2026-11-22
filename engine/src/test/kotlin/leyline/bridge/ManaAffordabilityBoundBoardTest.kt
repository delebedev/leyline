package leyline.bridge

import forge.game.zone.ZoneType
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest

class ManaAffordabilityBoundBoardTest :
    BoardTest({
        test("a paid filter's gross output counts toward colour needs") {
            val board =
                startWithBoard { _, player, _ ->
                    addCard("Swans of Bryn Argoll", player, ZoneType.Hand)
                    addCard("Azorius Signet", player, ZoneType.Battlefield)
                    repeat(3) { addCard("Wastes", player, ZoneType.Battlefield) }
                }
            val spell =
                board.human.hand
                    .card("Swans of Bryn Argoll")
                    .firstSpellAbility
            spell.activatingPlayer = board.human
            ManaAffordabilityBound.allows(spell, board.human) shouldBe true
        }
    })
