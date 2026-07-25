package leyline.game.annotations

import forge.card.MagicColor
import forge.game.card.Card
import forge.game.mana.Mana
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.testkit.Board
import leyline.testkit.BoardTest

class CastAbilityWordScannerTest :
    BoardTest({
        fun Board.putSpellOnStack(card: Card): SpellAbility {
            val ability = card.spells.first()
            ability.activatingPlayer = card.controller
            game.stack.freezeStack(ability)
            ability.hostCard = game.action.moveToStack(card, ability)
            game.stack.addAndUnfreeze(ability)
            return ability
        }

        fun SpellAbility.recordPayment(vararg colors: Byte) {
            colors.forEach { color -> payingMana.add(Mana(color, hostCard, null, activatingPlayer)) }
        }

        test("ColorsSpentToCast reports the distinct WUBRG colors actually paid") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Sundering Archaic", human, ZoneType.Hand)
                }
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val spell = board.putSpellOnStack(card)
            spell.recordPayment(MagicColor.WHITE, MagicColor.RED, MagicColor.WHITE)

            val entry = CastAbilityWordScanner.scan(board.game, board.bridge).single()

            assertSoftly {
                entry.abilityWordName shouldBe "ColorsSpentToCast"
                entry.instanceId shouldBe board.instanceId(card.id)
                entry.affectorId shouldBe null
                entry.affectedIds shouldBe emptyList()
                entry.colors shouldBe listOf(1, 4)
            }
        }

        test("ColorsSpentToCast reports one actual paid color") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Sundering Archaic", human, ZoneType.Hand)
                }
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            board.putSpellOnStack(card).recordPayment(MagicColor.RED, MagicColor.RED)

            CastAbilityWordScanner.scan(board.game, board.bridge).single().colors shouldBe listOf(4)
        }

        test("keyword-backed Converge cards report actual paid colors") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Wildgrowth Archaic", human, ZoneType.Hand)
                }
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            board.putSpellOnStack(card).recordPayment(MagicColor.RED, MagicColor.GREEN)

            card.hasConverge() shouldBe true
            CastAbilityWordScanner.scan(board.game, board.bridge).single().colors shouldBe listOf(4, 5)
        }

        test("generic multicolor spell does not emit ColorsSpentToCast") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Unfriendly Fire", human, ZoneType.Hand)
                }
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            board.putSpellOnStack(card).recordPayment(MagicColor.WHITE, MagicColor.RED)

            CastAbilityWordScanner.scan(board.game, board.bridge).shouldBeEmpty()
        }

        test("colorless payment does not emit ColorsSpentToCast") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Sundering Archaic", human, ZoneType.Hand)
                }
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            board.putSpellOnStack(card).recordPayment(MagicColor.COLORLESS)

            CastAbilityWordScanner.scan(board.game, board.bridge).shouldBeEmpty()
        }
    })
