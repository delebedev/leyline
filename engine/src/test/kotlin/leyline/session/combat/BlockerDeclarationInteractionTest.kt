package leyline.session.combat

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf

/**
 * Non-validating harness: combat zone transfers can produce transient
 * instanceId gaps (known StateMapper issue tracked separately).
 */
// `should beInGraveyardOf(player, count = N)` carries equality semantics — the
// detekt heuristic just doesn't recognize custom matchers as equality-shape.
@Suppress("WeakAssertionOnly")
class BlockerDeclarationInteractionTest :
    SessionTest({

        /**
         * Returns (humanBlockerIid, aiAttackerIid) at COMBAT_DECLARE_BLOCKERS,
         * ready for the human to respond to DeclareBlockersReq.
         *
         * DO NOT call passPriority() after declareNoAttackers() to "advance" —
         * it submits Pass to the COMBAT_DECLARE_BLOCKERS pending (= no blockers)
         * and skips the entire DeclareBlockersReq flow. Use harness.advanceToPhase
         * + triggerAutoPass instead, as below.
         */
        fun setupAiAttacksHumanCanBlock(): Pair<Int, Int> {
            startGame(
                deckList = COMBAT_DECK,
                validating = true,
                aiScript =
                    listOf(
                        ScriptedAction.PlayLand("Mountain"),
                        ScriptedAction.CastSpell("Raging Goblin"),
                        ScriptedAction.Attack(listOf("Raging Goblin")),
                        ScriptedAction.PassPriority,
                        ScriptedAction.PlayLand("Mountain"),
                        ScriptedAction.DeclareNoAttackers,
                        ScriptedAction.PassPriority,
                    ),
            )

            // Human turn 1: play Mountain, cast Raging Goblin (haste → potential blocker)
            playLand("Mountain").shouldBeTrue()
            castSpellByName("Raging Goblin").shouldBeTrue()
            passPriority() // resolve

            // Human combat: decline if prompted. The autoPassAndAdvance inside
            // declareNoAttackers processes the AI turn (land, cast, attack)
            // and may send DeclareBlockersReq in the same call.
            if (allMessages.any { it.hasDeclareAttackersReq() }) declareNoAttackers()

            // If DeclareBlockersReq isn't in messages yet, the AI turn hasn't
            // completed. Use bridge-level advanceTo to reach COMBAT_DECLARE_BLOCKERS
            // without intercepting the pending (passPriority would submit Pass
            // to the blocker pending = "no blockers"). Then trigger autoPassAndAdvance
            // directly — CombatHandler detects the combat phase and sends
            // DeclareBlockersReq before any action is submitted.
            if (allMessages.none { it.hasDeclareBlockersReq() }) {
                harness.advanceToPhase("COMBAT_DECLARE_BLOCKERS")
                triggerAutoPass()
                harness.drainSink()
            }

            allMessages.any { it.hasDeclareBlockersReq() }.shouldBeTrue()

            val humanCreatures = humanBattlefieldCreatures()
            humanCreatures.shouldNotBeEmpty()
            val blockerIid = humanCreatures.first().first

            // Find the AI attacker instanceId from the DeclareBlockersReq
            val blockReq = allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            blockReq.blockersCount shouldBeGreaterThan 0

            // The blocker should reference attacker instanceIds
            val blocker = blockReq.blockersList.first { it.blockerInstanceId == blockerIid }
            blocker.attackerInstanceIdsCount shouldBeGreaterThan 0
            val attackerIid = blocker.attackerInstanceIdsList.first()

            return blockerIid to attackerIid
        }

        fun advanceToMultiBlockerPrompt(): Triple<Int, Int, Int> {
            val puzzleText = javaClass.getResource("/puzzles/multi-blocker.pzl")!!.readText()
            startPuzzleRaw(
                puzzleText,
                validating = true,
                aiScript =
                    listOf(
                        ScriptedAction.Attack(listOf("Centaur Courser")),
                        ScriptedAction.PassPriority,
                    ),
            )

            passUntil(maxPasses = 6) { allMessages.any { it.hasDeclareBlockersReq() } }.shouldBeTrue()

            val req = allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            req.blockersCount shouldBe 2
            val blockerIids = req.blockersList.map { it.blockerInstanceId }
            val attackerIid =
                req.blockersList
                    .first()
                    .attackerInstanceIdsList
                    .first()
            return Triple(blockerIids[0], blockerIids[1], attackerIid)
        }

        // ─── Single-blocker block / decline / trade ──────────────────────────

        test("human blocks AI attacker") {
            val (blockerIid, attackerIid) = setupAiAttacksHumanCanBlock()

            // Human life before blocking
            val lifeBefore = human.life

            // Declare block: human's Raging Goblin blocks AI's Raging Goblin
            declareBlockers(mapOf(blockerIid to attackerIid))

            passThroughCombat()

            assertSoftly {
                // Human life should NOT decrease (blocked damage)
                human.life shouldBe lifeBefore

                // Both 1/1s should have traded — human's creature should be in graveyard
                "Raging Goblin" should beInGraveyardOf(human, count = 1)

                isGameOver().shouldBeFalse()
            }
        }

        test("human declines blocking takes damage") {
            setupAiAttacksHumanCanBlock() // advances to DeclareBlockersReq

            val lifeBefore = human.life

            // Human declines to block
            declareNoBlockers()

            passThroughCombat()

            assertSoftly {
                // Human should have taken exactly 1 damage (Raging Goblin is 1/1)
                human.life shouldBe lifeBefore - 1

                // Human's creature should still be alive
                humanBattlefieldCreatures() shouldHaveSize 1

                isGameOver().shouldBeFalse()
            }
        }

        test("trade produces creature deaths") {
            val (blockerIid, attackerIid) = setupAiAttacksHumanCanBlock()

            // Declare block
            declareBlockers(mapOf(blockerIid to attackerIid))

            passThroughCombat()

            assertSoftly {
                // Both 1/1s should have traded — exactly one Raging Goblin in each graveyard
                "Raging Goblin" should beInGraveyardOf(human, count = 1)
                "Raging Goblin" should beInGraveyardOf(ai, count = 1)

                isGameOver().shouldBeFalse()
            }
        }

        // ─── Iterative multi-blocker toggle ──────────────────────────────────

        test("second iterative blocker toggle does not wipe first assignment") {
            val (b1, b2, attackerIid) = advanceToMultiBlockerPrompt()

            val echo1 = toggleBlockers(mapOf(b1 to attackerIid))
            val req1 = echo1.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            assertSoftly {
                req1.blockersList
                    .first { it.blockerInstanceId == b1 }
                    .selectedAttackerInstanceIdsCount shouldBe 1
                req1.blockersList
                    .first { it.blockerInstanceId == b2 }
                    .selectedAttackerInstanceIdsCount shouldBe 0
            }

            val echo2 = toggleBlockers(mapOf(b2 to attackerIid))
            val req2 = echo2.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            assertSoftly {
                req2.blockersList
                    .first { it.blockerInstanceId == b1 }
                    .selectedAttackerInstanceIdsCount shouldBe 1
                req2.blockersList
                    .first { it.blockerInstanceId == b2 }
                    .selectedAttackerInstanceIdsCount shouldBe 1
            }
        }

        test("deselect blocker removes only that assignment") {
            val (b1, b2, attackerIid) = advanceToMultiBlockerPrompt()

            toggleBlockers(mapOf(b1 to attackerIid))
            toggleBlockers(mapOf(b2 to attackerIid))

            val echo = deselectBlocker(b1)
            val req = echo.last { it.hasDeclareBlockersReq() }.declareBlockersReq

            assertSoftly {
                req.blockersList
                    .first { it.blockerInstanceId == b1 }
                    .selectedAttackerInstanceIdsCount shouldBe 0
                req.blockersList
                    .first { it.blockerInstanceId == b1 }
                    .attackerInstanceIdsCount shouldBeGreaterThanOrEqual 1
                req.blockersList
                    .first { it.blockerInstanceId == b2 }
                    .selectedAttackerInstanceIdsCount shouldBe 1
            }
        }
    })
