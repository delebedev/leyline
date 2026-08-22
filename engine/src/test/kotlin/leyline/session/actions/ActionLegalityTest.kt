package leyline.session.actions

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType

private val COUNTERSPELL_EMPTY_STACK_PUZZLE =
    """
    [metadata]
    Name:Counterspell Empty Stack
    Goal:Win
    Turns:10
    Difficulty:Tutorial
    Description:Counterspell in hand with empty stack. Should not be offered as castable action.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanbattlefield=Island;Island
    humanhand=Counterspell
    humanlibrary=Island;Island;Island;Island;Island
    aibattlefield=Forest;Forest
    ailibrary=Forest;Forest;Forest;Forest;Forest
    """.trimIndent()

private val FLYING_BLOCKERS_PUZZLE =
    """
    [metadata]
    Name:Flying Blockers
    Goal:Win
    Turns:10
    Difficulty:Tutorial
    Description:AI attacks with 2 flyers, human has only ground creatures. No legal blocks — DeclareBlockersReq should not be sent.

    [state]
    ActivePlayer=AI
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanbattlefield=Forest;Forest;Grizzly Bears;Runeclaw Bear
    humanlibrary=Forest;Forest;Forest;Forest;Forest
    aibattlefield=Island;Island;Island;Island;Spyglass Siren;Kitesail Cleric
    ailibrary=Island;Island;Island;Island;Island
    """.trimIndent()

/**
 * Action legality filtering — spells and blockers should only be offered
 * when legal targets/blocks exist.
 */
class ActionLegalityTest :
    SessionTest({

        session(
            "counterspell not offered as castable when stack is empty",
            puzzle = COUNTERSPELL_EMPTY_STACK_PUZZLE,
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
            "counterspell targeting noncreature spells not offered when only a creature spell is on stack",
            puzzle = """
                ActivePlayer=AI
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Negate
                humanbattlefield=Island;Island
                humanlibrary=Island;Island;Island
                aihand=Grizzly Bears
                aibattlefield=Forest;Forest
                ailibrary=Forest;Forest;Forest
                """,
            aiScript = listOf(ScriptedAction.CastSpell("Grizzly Bears"), ScriptedAction.PassPriority),
        ) {
            passPriority()
            val castOffered =
                allMessages.any {
                    it.hasActionsAvailableReq() &&
                        it.actionsAvailableReq.actionsList.any { a -> a.actionType == ActionType.Cast }
                }
            castOffered shouldBe false
        }

        session(
            "counterspell targeting noncreature spells is offered when a noncreature spell is on stack",
            puzzle = """
                ActivePlayer=AI
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Negate
                humanbattlefield=Island;Island
                humanlibrary=Island;Island;Island
                aihand=Divination
                aibattlefield=Island;Island;Island
                ailibrary=Island;Island;Island
                """,
            aiScript = listOf(ScriptedAction.CastSpell("Divination"), ScriptedAction.PassPriority),
        ) {
            passPriority()
            val aar = allMessages.last { it.hasActionsAvailableReq() }
            val castActions = aar.actionsAvailableReq.actionsList.filter { it.actionType == ActionType.Cast }
            castActions.size shouldBe 1
        }

        session(
            "counterspell targeting mana value 2 not offered when stack spell has a different mana value",
            puzzle = """
                ActivePlayer=AI
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Spell Snare
                humanbattlefield=Island
                humanlibrary=Island;Island;Island
                aihand=Divination
                aibattlefield=Island;Island;Island
                ailibrary=Island;Island;Island
                """,
            aiScript = listOf(ScriptedAction.CastSpell("Divination"), ScriptedAction.PassPriority),
        ) {
            passPriority()
            val castOffered =
                allMessages.any {
                    it.hasActionsAvailableReq() &&
                        it.actionsAvailableReq.actionsList.any { a -> a.actionType == ActionType.Cast }
                }
            castOffered shouldBe false
        }

        session(
            "no DeclareBlockersReq when only flyers attack and defender has no reach",
            puzzle = FLYING_BLOCKERS_PUZZLE,
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
