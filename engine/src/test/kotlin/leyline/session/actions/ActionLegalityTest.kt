package leyline.session.actions

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Action legality filtering — spells and blockers should only be offered
 * when legal targets/blocks exist.
 */
class ActionLegalityTest :
    SessionTest({

        session(
            "counterspell not offered as castable when stack is empty",
            puzzleFile = "puzzles/counterspell-empty-stack.pzl",
            validating = true,
        ) {
            // Pass to get ActionsAvailableReq in Main1
            val found =
                passUntil(maxPasses = 3) {
                    allMessages.any { it.hasActionsAvailableReq() }
                }
            found.shouldBeTrue()

            // Find the priority-stop ActionsAvailableReq (has Pass action)
            val aar =
                allMessages.last {
                    it.hasActionsAvailableReq() &&
                        it.actionsAvailableReq.actionsList.any { a -> a.actionType == ActionType.Pass }
                }
            val actions = aar.actionsAvailableReq.actionsList

            // Counterspell should NOT be in active actions (no legal targets)
            val castActions = actions.filter { it.actionType == ActionType.Cast }
            castActions.size shouldBe 0
        }

        session(
            "no DeclareBlockersReq when only flyers attack and defender has no reach",
            puzzleFile = "puzzles/flying-blockers.pzl",
            validating = true,
            aiScript =
                listOf(
                    ScriptedAction.Attack(listOf("Spyglass Siren", "Kitesail Cleric")),
                    ScriptedAction.PassPriority,
                ),
        ) {
            // AI turn: pass until combat happens and resolves
            passThroughCombat()

            // DeclareBlockersReq should never have been sent
            val blockReq = allMessages.firstOrNull { it.hasDeclareBlockersReq() }
            blockReq.shouldBeNull()
        }
    })
