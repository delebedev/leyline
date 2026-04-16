package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Action legality filtering — spells and blockers should only be offered
 * when legal targets/blocks exist.
 */
class ActionLegalityTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("counterspell not offered as castable when stack is empty") {
            val puzzleText = javaClass.getResource("/puzzles/counterspell-empty-stack.pzl")!!.readText()
            val h = MatchFlowHarness(validating = false)
            harness = h

            h.connectAndKeepPuzzleText(puzzleText)

            // Pass to get ActionsAvailableReq in Main1
            val found = h.passUntil(maxPasses = 3) {
                allMessages.any { it.hasActionsAvailableReq() }
            }
            found.shouldBeTrue()

            // Find the priority-stop ActionsAvailableReq (has Pass action)
            val aar = h.allMessages.last {
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
            val h = MatchFlowHarness(validating = false)
            harness = h

            h.connectAndKeepPuzzleText(
                puzzleText,
                aiScript = listOf(
                    ScriptedAction.Attack(listOf("Spyglass Siren", "Kitesail Cleric")),
                    ScriptedAction.PassPriority,
                ),
            )

            // AI turn: pass until combat happens and resolves
            h.passThroughCombat()

            // DeclareBlockersReq should never have been sent
            val blockReq = h.allMessages.firstOrNull { it.hasDeclareBlockersReq() }
            blockReq.shouldBeNull()
        }
    })
