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

        test("counterspell not offered as castable when stack is empty") {
            val puzzleText = javaClass.getResource("/puzzles/counterspell-empty-stack.pzl")!!.readText()
            startPuzzleRaw(puzzleText, validating = true)

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

        test("no DeclareBlockersReq when only flyers attack and defender has no reach") {
            val puzzleText = javaClass.getResource("/puzzles/flying-blockers.pzl")!!.readText()

            startPuzzleRaw(
                puzzleText,
                validating = true,
                aiScript =
                    listOf(
                        ScriptedAction.Attack(listOf("Spyglass Siren", "Kitesail Cleric")),
                        ScriptedAction.PassPriority,
                    ),
            )

            // AI turn: pass until combat happens and resolves
            harness.passThroughCombat()

            // DeclareBlockersReq should never have been sent
            val blockReq = allMessages.firstOrNull { it.hasDeclareBlockersReq() }
            blockReq.shouldBeNull()
        }
    })
