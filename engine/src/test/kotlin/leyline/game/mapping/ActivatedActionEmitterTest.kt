package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class ActivatedActionEmitterTest :
    BoardTest({
        val colors = setOf(ManaColor.White_afc9, ManaColor.Blue_afc9, ManaColor.Black_afc9, ManaColor.Red_afc9, ManaColor.Green_afc9)
        for ((name, expected) in listOf(
            "Utopia Mycon" to colors,
            "Birds of Paradise" to colors,
            "Lotus Petal" to colors,
            "Elves of Deep Shadow" to setOf(ManaColor.Black_afc9),
            "Wastes" to setOf(ManaColor.Colorless_afc9),
            "Noble Hierarch" to setOf(ManaColor.White_afc9, ManaColor.Blue_afc9, ManaColor.Green_afc9),
        )) {
            test("$name publishes its produced colors without generic or extra choices") {
                val board = startWithBoard { _, player, _ -> addCard(name, player, ZoneType.Battlefield) }
                val source = board.human.battlefield.card(name)
                source.manaAbilities.flatMap { ActivatedActionEmitter.producedManaColors(it) }.toSet() shouldBe expected
            }
        }
    })
