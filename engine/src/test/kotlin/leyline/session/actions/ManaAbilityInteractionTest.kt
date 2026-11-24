package leyline.session.actions

import forge.card.MagicColor
import forge.card.mana.ManaAtom
import forge.game.card.CounterEnumType
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.bridge.types.StaticChoiceIds
import leyline.testkit.SessionTest
import leyline.testkit.after
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
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
            "a once-each-turn mana ability is offered again only on its controller's next turn",
            fullControl = true,
            puzzle = """
                [metadata]
                Name:Once Each Turn Mana
                Goal:Survive
                Turns:4
                Difficulty:Easy
                Description:Vivi Ornitier's mana ability is offered once on each of its controller's turns.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Vivi Ornitier|Counters:P1P1=1;Island
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
            """,
        ) {
            val vivi = human.battlefield.card("Vivi Ornitier")
            val firstActivation = activateMana("Vivi Ornitier", selectedColor = ManaColor.Red_afc9)
            val redAfterFirst = human.manaPool.getAmountOfColor(MagicColor.RED)
            val viviTappedAfterFirst = vivi.isTapped
            val secondActivation = activateMana("Vivi Ornitier")
            val islandThisTurn = activateMana("Island")

            passUntilTurn(3)
            val reachedMain = passUntil(maxPasses = 10) { game().phaseHandler.phase == PhaseType.MAIN1 }
            val nextTurnActivation = activateMana("Vivi Ornitier", selectedColor = ManaColor.Blue_afc9)
            val islandNextTurn = activateMana("Island")
            val blueNextTurn = human.manaPool.getAmountOfColor(MagicColor.BLUE)
            val repeatNextTurn = activateMana("Vivi Ornitier")

            assertSoftly {
                listOf(firstActivation, secondActivation, islandThisTurn) shouldBe listOf(true, false, true)
                redAfterFirst shouldBe 1
                viviTappedAfterFirst shouldBe false
                reachedMain shouldBe true
                listOf(nextTurnActivation, islandNextTurn, repeatNextTurn) shouldBe listOf(true, true, false)
                blueNextTurn shouldBe 2
            }
        }

        session(
            "a once-each-turn mana ability without a tap cost is not offered twice in a turn",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Wall of Roots
                humanlibrary=Forest;Forest
                ailibrary=Mountain;Mountain
            """,
        ) {
            val first = activateMana("Wall of Roots")
            val green = human.manaPool.getAmountOfColor(MagicColor.GREEN)
            val second = activateMana("Wall of Roots")
            assertSoftly {
                listOf(first, second) shouldBe listOf(true, false)
                green shouldBe 1
            }
        }

        session(
            "spending mana from an ability without a tap cost does not show its source tapping",
            fullControl = true,
            puzzle = """
                [metadata]
                Name:Untapped Mana Source Payment
                Goal:Survive
                Turns:2
                Difficulty:Easy
                Description:Vivi Ornitier's mana pays for Opt while Vivi stays untapped.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Vivi Ornitier|Counters:P1P1=1
                humanhand=Opt
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
            """,
        ) {
            val vivi = human.battlefield.card("Vivi Ornitier")
            val viviIid = bridge.instanceId(vivi)
            val activated = activateMana("Vivi Ornitier", selectedColor = ManaColor.Blue_afc9)
            val cast = castSpellByName("Opt")
            val annotations = allMessages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.annotationsList }
            assertSoftly {
                listOf(activated, cast) shouldBe listOf(true, true)
                annotations.count { AnnotationType.ManaPaid in it.typeList && it.affectorId == viviIid } shouldBe 1
                annotations
                    .count { AnnotationType.TappedUntappedPermanent in it.typeList && viviIid in it.affectedIdsList } shouldBe 0
                vivi.isTapped shouldBe false
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
