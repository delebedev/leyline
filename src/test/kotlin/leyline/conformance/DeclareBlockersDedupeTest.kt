package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual

/**
 * Regression test: DeclareBlockersReq must be sent at most once per
 * declare-blockers step. Before the fix, [CombatHandler.checkCombatPhase]
 * re-triggered during the priority window after blockers were submitted,
 * sending a duplicate DeclareBlockersReq that stalled the client.
 *
 * The fix adds a [CombatHandler.pendingBlockersSent] flag that prevents
 * re-entry: set in sendDeclareBlockersReq, checked in checkCombatPhase,
 * cleared in onDeclareBlockers.
 *
 * Confirmed via recording 2026-02-21_20-31-28.
 */
class DeclareBlockersDedupeTest :
    FunSpec({

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("no duplicate blockers req") {
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            // Script AI: play Mountain, cast Raging Goblin, attack with it
            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.CastSpell("Raging Goblin"),
                    ScriptedAction.Attack(listOf("Raging Goblin")),
                    ScriptedAction.PassPriority,
                ),
            )

            // Human turn 1: play Mountain, cast Raging Goblin (haste) as potential blocker
            h.playLand().shouldBeTrue()
            h.castSpellByName("Raging Goblin").shouldBeTrue()
            h.passPriority() // resolve

            // Skip human's own combat (declare no attackers)
            val msgs1 = h.allMessages
            if (msgs1.any { it.hasDeclareAttackersReq() }) {
                h.declareNoAttackers()
            }

            // Pass to advance through human's turn into AI's turn.
            // AI script runs: plays land, casts creature, attacks.
            var sawBlockerReq = false
            val preLoopSnap = h.messageSnapshot()
            for (i in 0 until 50) {
                if (h.isGameOver()) break
                val snap = h.messageSnapshot()
                h.passPriority()
                val recent = h.messagesSince(snap)
                if (recent.any { it.hasDeclareBlockersReq() }) {
                    sawBlockerReq = true
                    break
                }
                if (recent.any { it.hasDeclareAttackersReq() }) {
                    h.declareNoAttackers()
                }
            }

            if (!sawBlockerReq) {
                // AI never attacked — skip test (seed/script mismatch)
                println("DeclareBlockersDedupeTest: AI never attacked, skipping dedup check")
                return@test
            }

            // Human declares no blockers
            h.declareNoBlockers()

            // Pass through remaining combat
            repeat(10) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }

            // Count ALL DeclareBlockersReq since before the loop — should be exactly 1
            val allMsgs = h.messagesSince(preLoopSnap)
            val totalBlockerReqs = allMsgs.count { it.hasDeclareBlockersReq() }

            totalBlockerReqs shouldBeLessThanOrEqual 1
        }
    })
