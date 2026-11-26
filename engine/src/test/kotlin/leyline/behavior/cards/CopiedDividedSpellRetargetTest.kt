package leyline.behavior.cards

import forge.game.card.CounterEnumType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * A copied divide-as-you-choose spell keeps its division when its controller
 * picks new targets for the copy.
 */
class CopiedDividedSpellRetargetTest :
    SessionTest({
        session(
            "copy of an X counter distribution resolves on its new target",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Elusive Otter
                humanbattlefield=Unbound Flourishing;Grizzly Bears;Runeclaw Bear;Forest;Forest;Forest
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            val bears = human.battlefield.card("Grizzly Bears")
            val runeclaw = human.battlefield.card("Runeclaw Bear")
            val otterIid = human.hand.iid("Elusive Otter")
            val adventure =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.CastAdventure && it.instanceId == otterIid }

            nextNumericInput(2)
            submitAction(adventure)
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))

            // Unbound Flourishing copies the spell; the copy moves to the other creature.
            passUntil(maxPasses = 10) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true }
            selectTargets(listOf(human.battlefield.iid("Runeclaw Bear")))
            passUntil(maxPasses = 10) { game().stack.isEmpty }

            assertSoftly {
                game().isGameOver shouldBe false
                bears.getCounters(CounterEnumType.P1P1) shouldBe 2
                runeclaw.getCounters(CounterEnumType.P1P1) shouldBe 2
                human.getZone(ForgeZoneType.Exile).cards.any { it.name == "Elusive Otter" } shouldBe true
            }
        }

        session(
            "copy carries each divided amount to its new targets",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Elusive Otter
                humanbattlefield=Unbound Flourishing;Grizzly Bears;Runeclaw Bear;Savannah Lions;Forest;Forest;Forest
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            val bears = human.battlefield.card("Grizzly Bears")
            val runeclaw = human.battlefield.card("Runeclaw Bear")
            val lions = human.battlefield.card("Savannah Lions")
            val otterIid = human.hand.iid("Elusive Otter")
            val adventure =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.CastAdventure && it.instanceId == otterIid }

            nextNumericInput(2)
            submitAction(adventure)
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears"), human.battlefield.iid("Runeclaw Bear")))

            passUntil(maxPasses = 10) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true }
            selectTargets(listOf(human.battlefield.iid("Runeclaw Bear"), human.battlefield.iid("Savannah Lions")))
            passUntil(maxPasses = 10) { game().stack.isEmpty }

            assertSoftly {
                game().isGameOver shouldBe false
                bears.getCounters(CounterEnumType.P1P1) shouldBe 1
                runeclaw.getCounters(CounterEnumType.P1P1) shouldBe 2
                lions.getCounters(CounterEnumType.P1P1) shouldBe 1
            }
        }
    })
