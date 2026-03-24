package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import leyline.IntegrationTag

/**
 * AssignDamageReq integration tests — manual combat damage distribution.
 *
 * Uses [ScriptedAction.Block] for deterministic AI blocking.
 */
class AssignDamageTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("trample damage assignment sends AssignDamageReq and completes combat") {
            val puzzleText = javaClass.getResource("/puzzles/trample-damage-assign.pzl")!!.readText()
            val h = MatchFlowHarness(validating = false)
            harness = h

            h.connectAndKeepPuzzleText(
                puzzleText,
                aiScript = listOf(
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.Block(
                        mapOf(
                            "Grizzly Bears" to "Charging Monstrosaur",
                            "Runeclaw Bear" to "Charging Monstrosaur",
                        ),
                    ),
                    ScriptedAction.PassPriority,
                ),
            )

            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val dreadmawIid = creatures.first().first

            // Pass to combat → DeclareAttackersReq
            val found = h.passUntil(maxPasses = 5) {
                allMessages.any { it.hasDeclareAttackersReq() }
            }
            found.shouldBeTrue()

            // Attack. After submit, engine processes AI blockers → COMBAT_DAMAGE →
            // WPC.assignCombatDamage blocks on dedicated future →
            // auto-pass detects via checkPendingDamageAssignment → sends AssignDamageReq
            h.declareAttackers(listOf(dreadmawIid))
            h.submitAttackers()

            // AssignDamageReq should be in messages (sent before session lock released)
            val assignReq = h.allMessages.lastOrNull { it.hasAssignDamageReq() }
            assignReq.shouldNotBeNull()

            val req = assignReq.assignDamageReq
            req.damageAssignersCount.shouldBeGreaterThan(0)

            val assigner = req.damageAssignersList.first()
            assigner.totalDamage shouldBeGreaterThan 0
            assigner.assignmentsCount.shouldBeGreaterThan(1)

            // Assign exactly minDamage (lethal) to each blocker.
            // Trample overflow (totalDamage - sum) goes to defender implicitly.
            val responseAssignments = assigner.assignmentsList.map {
                it.instanceId to it.minDamage
            }

            val snap = h.messageSnapshot()
            h.assignDamage(listOf(assigner.instanceId to responseAssignments))

            val postAssign = h.messagesSince(snap)
            val confirmation = postAssign.firstOrNull { it.hasAssignDamageConfirmation() }
            confirmation.shouldNotBeNull()

            // Trample overflow is computed server-side (onAssignDamage adds null-key
            // for totalDamage minus blocker sum). But without a defender slot in the
            // proto, the client assigns all damage to blockers. Needs trample recording
            // to determine correct defender instanceId (#235).
            if (!h.isGameOver()) h.passThroughCombat()
        }

        test("single blocker does not trigger AssignDamageReq") {
            val puzzleText = """
                [metadata]
                Name:Single Blocker No Prompt
                Goal:Win
                Turns:10
                Difficulty:Tutorial
                Description:3/3 attacks into single 2/2 blocker. No manual assignment needed.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain;Raging Goblin;Raging Goblin;Raging Goblin
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                aibattlefield=Forest;Grizzly Bears
                ailibrary=Forest;Forest;Forest;Forest;Forest
            """.trimIndent()

            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzleText(
                puzzleText,
                aiScript = listOf(
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.Block(mapOf("Grizzly Bears" to "Raging Goblin")),
                    ScriptedAction.PassPriority,
                ),
            )

            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val attackerIid = creatures.first().first

            val found = h.passUntil(maxPasses = 5) {
                allMessages.any { it.hasDeclareAttackersReq() }
            }
            found.shouldBeTrue()

            h.declareAttackers(listOf(attackerIid))
            h.submitAttackers()

            h.passThroughCombat()

            val assignReq = h.allMessages.firstOrNull { it.hasAssignDamageReq() }
            assignReq.shouldBeNull()

            h.isGameOver().shouldBeFalse()
        }
    })
