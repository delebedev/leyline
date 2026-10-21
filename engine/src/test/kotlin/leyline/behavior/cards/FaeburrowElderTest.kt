package leyline.behavior.cards

import forge.card.MagicColor
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.after
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class FaeburrowElderTest :
    SessionTest({
        for (extraBlack in listOf(false, true)) {
            session(
                "Elder offers mana for controlled permanent colors and funds Watchwolf: black=$extraBlack",
                fullControl = true,
                puzzle = """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Faeburrow Elder;Island;Mountain${if (extraBlack) ";Walking Corpse" else ""}
                    humanhand=Watchwolf
                    humanlibrary=Forest;Forest;Forest
                    ailibrary=Plains;Plains;Plains
                """,
            ) {
                val elder = human.battlefield.card("Faeburrow Elder")
                val iid = human.battlefield.iid("Faeburrow Elder")
                val offer =
                    allMessages
                        .last { it.hasActionsAvailableReq() }
                        .actionsAvailableReq.actionsList
                        .single { it.actionType == ActionType.ActivateMana && it.instanceId == iid }
                assertSoftly {
                    elder.isTapped shouldBe false
                    elder.isSick shouldBe false
                    offer.isBatchable shouldBe false
                    offer.manaPaymentOptionsCount shouldBe 0
                    offer.manaSelectionsCount shouldBe 0
                }
                after { activateMana("Faeburrow Elder") shouldBe true }
                assertSoftly {
                    elder.isTapped shouldBe true
                    human.manaPool.getAmountOfColor(MagicColor.WHITE) shouldBe 1
                    human.manaPool.getAmountOfColor(MagicColor.GREEN) shouldBe 1
                    human.manaPool.getAmountOfColor(MagicColor.BLACK) shouldBe if (extraBlack) 1 else 0
                    human.manaPool.getAmountOfColor(MagicColor.BLUE) shouldBe 0
                    human.manaPool.getAmountOfColor(MagicColor.RED) shouldBe 0
                    human.manaPool.totalMana() shouldBe if (extraBlack) 3 else 2
                    game().stack.isEmpty shouldBe true
                }
                castSpellByName("Watchwolf") shouldBe true
                passUntilResolved()
                human.battlefield.card("Watchwolf").name shouldBe "Watchwolf"
                human.manaPool.getAmountOfColor(MagicColor.BLACK) shouldBe if (extraBlack) 1 else 0
            }
        }

        session(
            "Tapped Elder does not offer a second mana activation",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Faeburrow Elder|Tapped
                humanlibrary=Forest;Forest;Forest
                ailibrary=Plains;Plains;Plains
            """,
        ) {
            val elder = human.battlefield.card("Faeburrow Elder")
            elder.isTapped shouldBe true
            val iid = human.battlefield.iid("Faeburrow Elder")
            allMessages
                .last { it.hasActionsAvailableReq() }
                .actionsAvailableReq.actionsList
                .any { it.actionType == ActionType.ActivateMana && it.instanceId == iid } shouldBe false
            human.manaPool.totalMana() shouldBe 0
        }

        session(
            "Newly cast Elder cannot activate its tap ability",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Forest;Plains;Wastes
                humanhand=Faeburrow Elder
                humanlibrary=Forest;Forest;Forest
                ailibrary=Plains;Plains;Plains
            """,
        ) {
            castSpellByName("Faeburrow Elder") shouldBe true
            passUntilResolved()
            val elder = human.battlefield.card("Faeburrow Elder")
            elder.isSick shouldBe true
            val iid = human.battlefield.iid("Faeburrow Elder")
            allMessages
                .last { it.hasActionsAvailableReq() }
                .actionsAvailableReq.actionsList
                .any { it.actionType == ActionType.ActivateMana && it.instanceId == iid } shouldBe false
            human.manaPool.totalMana() shouldBe 0
        }
    })
