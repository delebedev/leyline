package leyline.session.costs

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.game.generator.PuzzleSource
import leyline.testkit.SessionTest

/** Which permanents automatic payment taps for a spell's generic cost. */
class AutomaticManaPaymentTest :
    SessionTest({
        val requiredTomb = PuzzleSource.definitionFromResource("data/puzzles/required-multi-mana-generic-payment.pzl").content

        session(
            "a required two-mana land pays a generic cost with one other land and no floating mana",
            puzzle = requiredTomb,
            fullControl = true,
        ) {
            castSpellByName("Dragon's Hoard").shouldBeTrue()
            passUntilResolved()

            assertSoftly {
                human.battlefield.card("Dragon's Hoard")
                human.battlefield.card("Ancient Tomb").isTapped shouldBe true
                human.getZone(ZoneType.Battlefield).cards.count { it.isLand && it.isTapped } shouldBe 2
                human.manaPool.totalMana() shouldBe 0
                human.life shouldBe 18
            }
        }

        session(
            "a required two-mana land keeps the only source of a colour a held card needs",
            puzzle = requiredTomb.replace("humanhand=Dragon's Hoard", "humanhand=Dragon's Hoard;Shock"),
            fullControl = true,
        ) {
            castSpellByName("Dragon's Hoard").shouldBeTrue()
            passUntilResolved()

            assertSoftly {
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isTapped }
                    .map { it.name } shouldContainExactlyInAnyOrder listOf("Terrain Generator", "Ancient Tomb")
                human.manaPool.totalMana() shouldBe 0
            }
        }
    })
