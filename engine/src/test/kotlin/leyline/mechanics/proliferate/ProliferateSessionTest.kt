package leyline.mechanics.proliferate

import forge.game.card.CounterEnumType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

class ProliferateSessionTest :
    SessionTest({
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main2
            HumanLife=20
            AILife=20
            humanbattlefield=Atraxa, Praetors' Voice;Bloated Contaminator|Counters:P1P1=1
            humanlibrary=Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain
            humancounters=POISON=1
            aicounters=POISON=1
            """.trimIndent()

        session("proliferate chooses permanents and players and keeps later turns interactive", puzzle = puzzle, turns = 4) {
            val creature = human.battlefield.card("Bloated Contaminator")
            val creatureIid = human.battlefield.iid("Bloated Contaminator")
            passUntil(maxPasses = 30) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true } shouldBe true
            val first =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq.targetsList
                    .single()
            first.minTargets shouldBe 0
            first.targetsList.map { it.targetInstanceId } shouldContainAll listOf(1, 2, creatureIid)

            selectTargets(listOf(creatureIid, 2))
            passUntil(maxPasses = 20) { creature.getCounters(CounterEnumType.P1P1) == 2 } shouldBe true
            assertSoftly {
                creature.getCounters(CounterEnumType.P1P1) shouldBe 2
                human.getCounters(CounterEnumType.POISON) shouldBe 1
                ai.getCounters(CounterEnumType.POISON) shouldBe 2
            }

            val firstPromptId = allMessages.last { it.hasSelectTargetsReq() }.msgId
            passUntil(maxPasses = 80) {
                allMessages.lastOrNull { it.hasSelectTargetsReq() }?.msgId != firstPromptId &&
                    allMessages.lastOrNull()?.hasSelectTargetsReq() == true
            } shouldBe true
            submitTargets()
            passUntil(maxPasses = 20) { allMessages.lastOrNull()?.hasActionsAvailableReq() == true } shouldBe true
            assertSoftly {
                creature.getCounters(CounterEnumType.P1P1) shouldBe 2
                ai.getCounters(CounterEnumType.POISON) shouldBe 2
            }
        }

        session(
            "proliferate can choose a permanent when neither player has counters",
            puzzle = puzzle.replace("humancounters=POISON=1\naicounters=POISON=1", ""),
            turns = 3,
        ) {
            val creature = human.battlefield.card("Bloated Contaminator")
            val creatureIid = human.battlefield.iid("Bloated Contaminator")
            passUntil(maxPasses = 30) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true } shouldBe true
            val choices =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq.targetsList
                    .single()
            choices.targetsList.map { it.targetInstanceId } shouldBe listOf(creatureIid)
            selectTargets(listOf(creatureIid))
            passUntil(maxPasses = 20) { creature.getCounters(CounterEnumType.P1P1) == 2 } shouldBe true
        }

        session("canceling optional proliferate leaves the game interactive", puzzle = puzzle, turns = 3) {
            val creature = human.battlefield.card("Bloated Contaminator")
            passUntil(maxPasses = 30) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true } shouldBe true
            cancelAction()
            passUntil(maxPasses = 20) { allMessages.lastOrNull()?.hasActionsAvailableReq() == true } shouldBe true
            assertSoftly {
                creature.getCounters(CounterEnumType.P1P1) shouldBe 1
                human.getCounters(CounterEnumType.POISON) shouldBe 1
                ai.getCounters(CounterEnumType.POISON) shouldBe 1
            }
        }
    })
