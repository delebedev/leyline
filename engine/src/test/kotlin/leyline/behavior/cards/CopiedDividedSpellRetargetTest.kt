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

        // Original split: Grizzly Bears 2, Runeclaw Bear 1. A kept target keeps its own amount.
        listOf(
            listOf("Savannah Lions", "Grizzly Bears") to mapOf("Grizzly Bears" to 4, "Runeclaw Bear" to 1, "Savannah Lions" to 1),
            listOf("Runeclaw Bear", "Savannah Lions") to mapOf("Grizzly Bears" to 2, "Runeclaw Bear" to 2, "Savannah Lions" to 2),
        ).forEach { (copyTargets, expected) ->
            session(
                "kept target keeps its own amount of an unequal split: copy targets $copyTargets",
                puzzle = """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20

                    humanhand=Elusive Otter
                    humanbattlefield=Unbound Flourishing;Grizzly Bears;Runeclaw Bear;Savannah Lions;Forest;Forest;Forest;Forest
                    humanlibrary=Forest
                    ailibrary=Mountain
                    """,
            ) {
                val creatures = expected.keys.associateWith { human.battlefield.card(it) }
                val otterIid = human.hand.iid("Elusive Otter")
                val adventure =
                    allMessages
                        .last { it.hasActionsAvailableReq() }
                        .actionsAvailableReq.actionsList
                        .single { it.actionType == ActionType.CastAdventure && it.instanceId == otterIid }

                nextNumericInput(3)
                submitAction(adventure)
                val bearsIid = human.battlefield.iid("Grizzly Bears")
                val runeclawIid = human.battlefield.iid("Runeclaw Bear")
                selectTargets(listOf(bearsIid, runeclawIid))
                respondToDistribution(listOf(bearsIid to 2, runeclawIid to 1))

                passUntil(maxPasses = 10) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true }
                selectTargets(copyTargets.map { human.battlefield.iid(it) })
                passUntil(maxPasses = 10) { game().stack.isEmpty }

                assertSoftly {
                    game().isGameOver shouldBe false
                    expected.forEach { (name, counters) ->
                        creatures.getValue(name).getCounters(CounterEnumType.P1P1) shouldBe counters
                    }
                }
            }
        }
    })
