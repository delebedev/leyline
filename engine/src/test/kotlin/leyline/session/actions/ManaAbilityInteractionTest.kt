package leyline.session.actions

import forge.card.MagicColor
import forge.card.mana.ManaAtom
import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.bridge.types.StaticChoiceIds
import leyline.testkit.SessionTest
import leyline.testkit.after
import wotc.mtgo.gre.external.messaging.Messages.StaticList

class ManaAbilityInteractionTest :
    SessionTest({
        session(
            "reveal mana activation retains the chosen hand cards and produces their amount",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Ornithopter;Sol Ring
                humanbattlefield=Metalworker;Forest
                humanlibrary=Forest;Forest
                ailibrary=Mountain;Mountain
            """,
        ) {
            val source = human.battlefield.card("Metalworker")
            val revealed = human.hand.iid("Ornithopter")
            after { activateMana("Metalworker").shouldBeTrue() }.expectOneSelectNReq()
            respondToSelectN(listOf(revealed))
            assertSoftly {
                source.isTapped.shouldBeTrue()
                human.manaPool.getAmountOfColor(ManaAtom.COLORLESS.toByte()) shouldBe 2
                human.hand.card("Ornithopter")
                human.hand.card("Sol Ring")
                source.remembered.none().shouldBeTrue()
            }
        }

        session(
            "manual activation with a counter-removal X cost asks for X and adds that much mana",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Rushwood Grove|Counters:STORAGE=3
                humanlibrary=Forest;Forest
                ailibrary=Mountain;Mountain
            """,
        ) {
            val grove = human.battlefield.card("Rushwood Grove")
            holdNextNumericInput()
            activateMana("Rushwood Grove").shouldBeTrue()
            val prompt = allMessages.last { it.hasNumericInputReq() }.numericInputReq
            assertSoftly {
                prompt.minValue shouldBe 0
                prompt.maxValue shouldBe 3
            }
            respondToNumericInput(2)
            assertSoftly {
                grove.isTapped.shouldBeTrue()
                grove.getCounters(CounterEnumType.STORAGE) shouldBe 1
                human.manaPool.getAmountOfColor(MagicColor.GREEN) shouldBe 2
                human.manaPool.totalMana() shouldBe 2
            }
        }

        session(
            "manual tap of an enchanted land asks the aura's extra mana color",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Forest|Id:7;Fertile Ground|AttachedTo:7
                humanhand=Opt
                humanlibrary=Forest;Forest
                ailibrary=Mountain;Mountain
            """,
        ) {
            val choice = after { activateMana("Forest").shouldBeTrue() }.expectOneSelectNReq()
            choice.staticList shouldBe StaticList.Colors
            respondToSelectN(listOf(checkNotNull(StaticChoiceIds.colorIdForMask(MagicColor.BLUE))))
            assertSoftly {
                human.battlefield
                    .card("Forest")
                    .isTapped
                    .shouldBeTrue()
                human.manaPool.getAmountOfColor(MagicColor.GREEN) shouldBe 1
                human.manaPool.getAmountOfColor(MagicColor.BLUE) shouldBe 1
                human.manaPool.totalMana() shouldBe 2
            }
        }

        session(
            "recipient mana activation gives mana only to the chosen player",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Victory Chimes;Forest
                humanlibrary=Forest;Forest
                ailibrary=Mountain;Mountain
            """,
        ) {
            activateMana("Victory Chimes").shouldBeTrue()
            passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            assertSoftly {
                human.battlefield
                    .card("Victory Chimes")
                    .isTapped
                    .shouldBeTrue()
                human.manaPool.totalMana() shouldBe 0
                ai.manaPool.getAmountOfColor(ManaAtom.COLORLESS.toByte()) shouldBe 1
            }
        }

        session(
            "devotion mana activation pays its cost and retains the selected color",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Nykthos, Shrine to Nyx;Swamp;Swamp;Walking Corpse;Vampire Nighthawk
                humanlibrary=Forest;Forest
                ailibrary=Mountain;Mountain
            """,
        ) {
            val choice = after { activateMana("Nykthos, Shrine to Nyx", abilityIndex = 1).shouldBeTrue() }.expectOneSelectNReq()
            choice.staticList shouldBe StaticList.Colors
            respondToSelectN(listOf(3))
            assertSoftly {
                human.battlefield
                    .card("Nykthos, Shrine to Nyx")
                    .isTapped
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Swamp" }
                    .all { it.isTapped }
                    .shouldBeTrue()
                human.manaPool.getAmountOfColor(MagicColor.BLACK) shouldBe 3
                human.manaPool.totalMana() shouldBe 3
                human.battlefield
                    .card("Nykthos, Shrine to Nyx")
                    .hasChosenColor()
                    .shouldBeFalse()
            }
        }
    })
