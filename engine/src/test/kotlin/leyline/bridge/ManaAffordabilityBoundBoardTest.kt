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

        for (
        case in
        listOf(
            BoundCase("Growth Spiral", listOf("Overflowing Basin"), false),
            BoundCase("Growth Spiral", listOf("Overflowing Basin", "Overflowing Basin"), false),
            BoundCase("Growth Spiral", listOf("Overflowing Basin", "Island"), true),
            BoundCase("Growth Spiral", listOf("Overflowing Basin", "Overflowing Basin", "Island"), true),
            BoundCase("Dark Ritual", listOf("Fetid Heath"), false),
            BoundCase("Gifted Aetherborn", listOf("Fetid Heath", "Swamp"), true),
            BoundCase("Necropotence", listOf("Fetid Heath", "Swamp"), false),
            BoundCase("Grizzly Bears", listOf("Fetid Heath", "Forest"), true),
        )
        ) {
            test("${case.spell} with ${case.battlefield.joinToString()} passes the bound: ${case.allowed}") {
                val board =
                    startWithBoard { _, player, _ ->
                        addCard(case.spell, player, ZoneType.Hand)
                        case.battlefield.forEach { addCard(it, player, ZoneType.Battlefield) }
                    }
                val spell =
                    board.human.hand
                        .card(case.spell)
                        .firstSpellAbility
                spell.activatingPlayer = board.human
                ManaAffordabilityBound.allows(spell, board.human) shouldBe case.allowed
                ActionAvailability.canExecute(spell, board.human) shouldBe case.allowed
            }
        }
    })

private data class BoundCase(
    val spell: String,
    val battlefield: List<String>,
    val allowed: Boolean,
)
