package leyline.behavior.cards

import forge.card.MagicColor
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.after
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.StaticList

class SmokebraiderManaTest :
    SessionTest({
        for (mixed in listOf(false, true)) {
            session(
                "Smokebraider chooses ${if (mixed) "green-red" else "two green"} and retains restricted spending",
                fullControl = true,
                puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Smokebraider
                humanhand=${if (mixed) "Brushfire Elemental" else "Leafkin Druid"};Grizzly Bears;Memnite
                humanlibrary=Forest;Forest;Forest
                ailibrary=Forest;Forest;Forest
            """,
            ) {
                val source = human.battlefield.iid("Smokebraider")
                val offer =
                    allMessages
                        .last { it.hasActionsAvailableReq() }
                        .actionsAvailableReq.actionsList
                        .single { it.actionType == ActionType.ActivateMana && it.instanceId == source }
                assertSoftly {
                    offer.isBatchable shouldBe false
                    offer.manaSelectionsCount shouldBe 0
                    offer.manaPaymentOptionsCount shouldBe 0
                }
                val first = after { submitAction(offer) }.expectOneSelectNReq()
                assertSoftly {
                    first.staticList shouldBe StaticList.Colors
                    first.minSel shouldBe 1
                    first.maxSel shouldBe 1
                }
                val firstMessageId = allMessages.last { it.hasSelectNReq() }.msgId
                val second = after { respondToSelectN(listOf(5)) }.expectOneSelectNReq()
                second.staticList shouldBe StaticList.Colors
                allMessages.last { it.hasSelectNReq() }.msgId shouldBeGreaterThan firstMessageId
                respondToSelectN(listOf(if (mixed) 4 else 5))
                assertSoftly {
                    human.manaPool.totalMana() shouldBe 2
                    human.manaPool.getAmountOfColor(MagicColor.GREEN) shouldBe if (mixed) 1 else 2
                    human.manaPool.getAmountOfColor(MagicColor.RED) shouldBe if (mixed) 1 else 0
                    human.manaPool.getAmountOfColor(MagicColor.WHITE) shouldBe 0
                    human.manaPool.getAmountOfColor(MagicColor.BLUE) shouldBe 0
                    human.battlefield.card("Smokebraider").isTapped shouldBe true
                }
                val bears = human.hand.iid("Grizzly Bears")
                val request = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq
                request.actionsList.none { it.actionType == ActionType.Cast && it.instanceId == bears } shouldBe true
                request.inactiveActionsList.any { it.actionType == ActionType.Cast && it.instanceId == bears } shouldBe true
                val elemental = if (mixed) "Brushfire Elemental" else "Leafkin Druid"
                castSpellByName(elemental) shouldBe true
                passUntilResolved()
                assertSoftly {
                    human.battlefield.card(elemental).name shouldBe elemental
                    human.manaPool.totalMana() shouldBe 0
                }
                castSpellByName("Memnite") shouldBe true
                passUntilResolved()
                human.battlefield.card("Memnite").name shouldBe "Memnite"
            }
        }

        session(
            "Conversion Apparatus retains three independently selected mana units",
            fullControl = true,
            puzzle = """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Conversion Apparatus
            humancounters=ENERGY=3
            humanhand=Centaur Courser
            humanlibrary=Forest;Forest;Forest
            ailibrary=Forest;Forest;Forest
        """,
        ) {
            val first = after { activateMana("Conversion Apparatus", abilityIndex = 1) shouldBe true }.expectOneSelectNReq()
            first.staticList shouldBe StaticList.Colors
            after { respondToSelectN(listOf(5)) }.expectOneSelectNReq()
            after { respondToSelectN(listOf(4)) }.expectOneSelectNReq()
            respondToSelectN(listOf(5))
            assertSoftly {
                human.manaPool.totalMana() shouldBe 3
                human.manaPool.getAmountOfColor(MagicColor.GREEN) shouldBe 2
                human.manaPool.getAmountOfColor(MagicColor.RED) shouldBe 1
            }
            castSpellByName("Centaur Courser") shouldBe true
            passUntilResolved()
            human.battlefield.card("Centaur Courser").name shouldBe "Centaur Courser"
            human.manaPool.totalMana() shouldBe 0
        }

        session(
            "Firemind Vessel excludes the first color from its second choice",
            fullControl = true,
            puzzle = """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Firemind Vessel
            humanlibrary=Forest;Forest;Forest
            ailibrary=Forest;Forest;Forest
        """,
        ) {
            after { activateMana("Firemind Vessel") shouldBe true }.expectOneSelectNReq()
            val second = after { respondToSelectN(listOf(5)) }.expectOneSelectNReq()
            second.staticList shouldBe StaticList.CardColors
            second.idsList.map { it.toInt() } shouldBe listOf(1, 2, 3, 4)
            respondToSelectN(listOf(4))
            assertSoftly {
                human.manaPool.totalMana() shouldBe 2
                human.manaPool.getAmountOfColor(MagicColor.GREEN) shouldBe 1
                human.manaPool.getAmountOfColor(MagicColor.RED) shouldBe 1
            }
        }

        session(
            "Guild Globe can finish its mana choices after sacrificing its source",
            fullControl = true,
            puzzle = """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Guild Globe;Wastes;Wastes
            humanlibrary=Forest;Forest;Forest
            ailibrary=Forest;Forest;Forest
        """,
        ) {
            after { activateMana("Guild Globe") shouldBe true }.expectOneSelectNReq()
            human.graveyard.card("Guild Globe").name shouldBe "Guild Globe"
            after { respondToSelectN(listOf(5)) }.expectOneSelectNReq()
            respondToSelectN(listOf(4))
            assertSoftly {
                human.manaPool.totalMana() shouldBe 2
                human.manaPool.getAmountOfColor(MagicColor.GREEN) shouldBe 1
                human.manaPool.getAmountOfColor(MagicColor.RED) shouldBe 1
            }
        }

        session(
            "Lotus Field still produces three mana of one selected color without extra prompts",
            fullControl = true,
            puzzle = """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Lotus Field
            humanlibrary=Forest;Forest;Forest
            ailibrary=Forest;Forest;Forest
        """,
        ) {
            val result = after { activateMana("Lotus Field", selectedColor = ManaColor.Green_afc9) shouldBe true }
            assertSoftly {
                result.messages.none { it.hasSelectNReq() } shouldBe true
                human.manaPool.totalMana() shouldBe 3
                human.manaPool.getAmountOfColor(MagicColor.GREEN) shouldBe 3
            }
        }
    })
