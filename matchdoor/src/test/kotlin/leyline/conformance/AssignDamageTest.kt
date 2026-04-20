package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
                aiScript =
                    listOf(
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
            val found =
                h.passUntil(maxPasses = 5) {
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
            assigner.totalDamage shouldBe 5 // Charging Monstrosaur 5/5

            // Blocker slots: minDamage=lethal(2), assignedDamage=lethal(2)
            // Defender slot: no minDamage, maxDamage=overflow(1), assignedDamage=overflow(1)
            val blockerSlots = assigner.assignmentsList.filter { it.minDamage > 0 }
            val defenderSlot = assigner.assignmentsList.find { it.minDamage == 0 && it.maxDamage > 0 }

            blockerSlots.size shouldBe 2
            blockerSlots.forEach {
                it.minDamage shouldBe 2
                it.assignedDamage shouldBe 2
            }

            assertSoftly {
                defenderSlot.shouldNotBeNull()
                defenderSlot.instanceId shouldBe 2 // defending seatId
                defenderSlot.maxDamage shouldBe 1 // 5 - 2 - 2 = 1 overflow
                defenderSlot.assignedDamage shouldBe 1
            }

            // Send back the pre-filled assignments (lethal to blockers + overflow to defender)
            val responseAssignments =
                assigner.assignmentsList.map {
                    it.instanceId to it.assignedDamage
                }

            val snap = h.messageSnapshot()
            h.assignDamage(listOf(assigner.instanceId to responseAssignments))

            val postAssign = h.messagesSince(snap)
            val confirmation = postAssign.firstOrNull { it.hasAssignDamageConfirmation() }
            confirmation.shouldNotBeNull()

            // 1 trample overflow to AI at 1 life → game should end
            if (!h.isGameOver()) h.passThroughCombat()
        }

        test("single blocker does not trigger AssignDamageReq") {
            val puzzleText =
                """
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
                aiScript =
                    listOf(
                        ScriptedAction.DeclareNoAttackers,
                        ScriptedAction.Block(mapOf("Grizzly Bears" to "Raging Goblin")),
                        ScriptedAction.PassPriority,
                    ),
            )

            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val attackerIid = creatures.first().first

            val found =
                h.passUntil(maxPasses = 5) {
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
