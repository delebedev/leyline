package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf
import leyline.tooling.headless.HeadlessResponseMode
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class GoblinWelderTest :
    SessionTest({
        session(
            "published Welder activation exchanges its paired targets and permits continued play",
            fullControl = true,
            responseMode = HeadlessResponseMode.PolicyVisible,
            puzzle = """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Goblin Welder;Sol Ring
            humangraveyard=Ornithopter
            humanhand=Tormod's Crypt;Plains
            humanlibrary=Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain
        """,
        ) {
            val welder = human.battlefield.card("Goblin Welder")
            val id = human.battlefield.iid("Goblin Welder")
            val offer =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.Activate_add3 && it.instanceId == id }
            welder.isTapped shouldBe false
            submitAction(offer)
            selectTargets(listOf(human.battlefield.iid("Sol Ring")))
            selectTargets(listOf(human.graveyard.iid("Ornithopter")))
            val resolved = passUntil(maxPasses = 4) { game().stack.isEmpty }
            assertSoftly {
                resolved shouldBe true
                welder.isTapped shouldBe true
                "Sol Ring" should beInGraveyardOf(human)
                human.battlefield.card("Ornithopter").name shouldBe "Ornithopter"
            }
            castSpellByName("Tormod's Crypt") shouldBe true
            passUntilResolved()
            human.battlefield.card("Tormod's Crypt").name shouldBe "Tormod's Crypt"
        }

        for (control in listOf("empty", "wrong-controller", "nonartifact", "sick", "tapped")) {
            val flag =
                when (control) {
                    "sick" -> "|SummonSick"
                    "tapped" -> "|Tapped"
                    else -> ""
                }
            val yard =
                when (control) {
                    "empty" -> ""
                    "wrong-controller" -> "aigraveyard=Ornithopter"
                    "nonartifact" -> "humangraveyard=Forest"
                    else -> "humangraveyard=Ornithopter"
                }
            session(
                "Welder $control control publishes no activation and retains continued play",
                fullControl = true,
                responseMode = HeadlessResponseMode.PolicyVisible,
                puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Goblin Welder$flag;Sol Ring
                $yard
                humanhand=Tormod's Crypt;Plains
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
            """,
            ) {
                val id = human.battlefield.iid("Goblin Welder")
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .none { it.actionType == ActionType.Activate_add3 && it.instanceId == id } shouldBe true
                castSpellByName("Tormod's Crypt") shouldBe true
                passUntilResolved()
                human.battlefield.card("Tormod's Crypt").name shouldBe "Tormod's Crypt"
            }
        }
    })
