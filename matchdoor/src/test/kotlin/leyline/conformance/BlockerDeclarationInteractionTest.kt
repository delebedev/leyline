package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * Session-tier blocker declaration tests — DeclareBlockersReq flow,
 * iterative toggle semantics, multi-blocker assignment.
 *
 * Split out from CombatInteractionTest at the LargeClass threshold —
 * blocker declaration is a distinct flow with its own setup ergonomics
 * (AI scripted to attack so the human can prompt-respond as defender).
 *
 * Uses [COMBAT_DECK] (declared in CombatInteractionTest.kt).
 *
 * Uses non-validating harness: combat zone transfers can produce transient
 * instanceId gaps (known StateMapper issue tracked separately).
 */
class BlockerDeclarationInteractionTest :
    InteractionTest({

        /**
         * Setup: human casts Raging Goblin turn 1 (potential blocker).
         * AI scripted to cast Raging Goblin and attack with it on its turn.
         * Advances to the point where DeclareBlockersReq should be sent.
         *
         * Returns pair of (humanBlockerInstanceId, aiAttackerInstanceId).
         *
         * Key insight: after declareNoAttackers() on human's combat, the
         * autoPassAndAdvance inside the submit handler processes the AI turn.
         * DO NOT call passPriority() to "advance" — it submits Pass to the
         * COMBAT_DECLARE_BLOCKERS pending, which means "no blockers" and
         * skips the entire DeclareBlockersReq flow.
         */
        fun setupAiAttacksHumanCanBlock(): Pair<Int, Int> {
            startGame(
                deckList = COMBAT_DECK,
                validating = false,
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
            playLand().shouldBeTrue()
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
                leyline.game.advanceToPhase(harness.bridge, "COMBAT_DECLARE_BLOCKERS")
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
                validating = false,
                aiScript =
                    listOf(
                        ScriptedAction.Attack(listOf("Hill Giant")),
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

            // Human life should NOT decrease (blocked damage)
            human.life shouldBe lifeBefore

            // Both 1/1s should have traded — human's creature should be in graveyard
            val humanGy = human.getZone(ZoneType.Graveyard).cards
            humanGy.any { it.name == "Raging Goblin" }.shouldBeTrue()

            isGameOver().shouldBeFalse()
        }

        test("human declines blocking takes damage") {
            setupAiAttacksHumanCanBlock() // advances to DeclareBlockersReq

            val lifeBefore = human.life

            // Human declines to block
            declareNoBlockers()

            passThroughCombat()

            // Human should have taken exactly 1 damage (Raging Goblin is 1/1)
            human.life shouldBe lifeBefore - 1

            // Human's creature should still be alive
            humanBattlefieldCreatures().shouldNotBeEmpty()

            isGameOver().shouldBeFalse()
        }

        test("trade produces creature deaths") {
            val (blockerIid, attackerIid) = setupAiAttacksHumanCanBlock()

            // Declare block
            declareBlockers(mapOf(blockerIid to attackerIid))

            passThroughCombat()

            // Both creatures should be dead
            val humanGy = human.getZone(ZoneType.Graveyard).cards
            val aiGy = ai.getZone(ZoneType.Graveyard).cards

            humanGy.any { it.name == "Raging Goblin" }.shouldBeTrue()
            aiGy.any { it.name == "Raging Goblin" }.shouldBeTrue()

            isGameOver().shouldBeFalse()
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
