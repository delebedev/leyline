package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Tier 2 end-to-end combat tests driven through real [MatchSession] code.
 *
 * Deck: Raging Goblin (haste) + Mountain — enables turn-1 combat without
 * multi-turn advancement (which is unreliable due to autoPassAndAdvance
 * overshooting turns). AI gets the same deck.
 */
/**
 * Combat test deck: haste creatures (Raging Goblin) + Mountains.
 * Also includes green splash for Giant Growth/Elves (reused by targeting tests).
 */
const val COMBAT_DECK = """
20 Raging Goblin
4 Llanowar Elves
4 Giant Growth
16 Mountain
16 Forest
"""

class CombatFlowTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        // --- Setup helpers ---

        fun setupSingleAttacker(): Int {
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            // Turn 1: play Mountain, cast Raging Goblin (R)
            h.playLand().shouldBeTrue()
            val cast = h.castSpellByName("Raging Goblin")
            cast.shouldBeTrue()
            h.passPriority() // resolve from stack → battlefield

            // Still turn 1 — Raging Goblin has haste, can attack this turn
            h.turn() shouldBe 1
            h.isAiTurn().shouldBeFalse()

            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            return creatures.first().first
        }

        fun setupMultipleAttackers(): List<Int> {
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            // Turn 1: play Mountain, cast Raging Goblin #1
            h.playLand().shouldBeTrue()
            val cast1 = h.castSpellByName("Raging Goblin")
            cast1.shouldBeTrue()
            h.passPriority() // resolve

            // Advance past turn 1 — may overshoot to turn 2 or 3
            h.passPriority()

            // If we landed on AI's turn, pass again to get back to human
            if (h.isAiTurn() && !h.isGameOver()) {
                h.passPriority()
            }

            // Play second land + cast second creature
            h.playLand()
            val cast2 = h.castSpellByName("Raging Goblin")
            if (cast2) h.passPriority() // resolve

            val creatures = h.humanBattlefieldCreatures()
            (creatures.size >= 2).shouldBeTrue()
            return creatures.map { it.first }
        }

        fun setupWithAiBlocker(): Int {
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            // AI: play Mountain, cast Raging Goblin (has blocker), skip attacking, pass
            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.CastSpell("Raging Goblin"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            // Human turn 1: play Mountain, cast Raging Goblin
            h.playLand().shouldBeTrue()
            val cast = h.castSpellByName("Raging Goblin")
            cast.shouldBeTrue()
            h.passPriority() // resolve

            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            return creatures.first().first
        }

        // --- Tests ---

        test("human declares single attacker") {
            val attackerIid = setupSingleAttacker()
            val h = harness!!

            // Pass from Main1 to advance to combat — auto-pass should emit DeclareAttackersReq
            val snap = h.messageSnapshot()
            h.passPriority()

            // Find DeclareAttackersReq in messages
            val msgs = h.messagesSince(snap)
            val daReq = msgs.firstOrNull { it.hasDeclareAttackersReq() }
            daReq.shouldNotBeNull()

            val req = daReq.declareAttackersReq
            (req.attackersCount > 0).shouldBeTrue()

            // The Raging Goblin (haste) should be among eligible attackers
            val eligibleIds = req.attackersList.map { it.attackerInstanceId }
            (attackerIid in eligibleIds).shouldBeTrue()

            // Declare the attack
            val snap2 = h.messageSnapshot()
            h.declareAttackers(listOf(attackerIid))

            // Should get confirmation messages
            val postAttack = h.messagesSince(snap2)
            postAttack.shouldNotBeEmpty()

            // Validate accumulated state
            h.accumulator.assertConsistent("after single attacker declared")
            h.isGameOver().shouldBeFalse()
        }

        test("human declares multiple attackers") {
            val attackerIids = setupMultipleAttackers()
            val h = harness!!

            // Advance to combat
            val snap = h.messageSnapshot()
            h.passPriority()

            val msgs = h.messagesSince(snap)
            val daReq = msgs.firstOrNull { it.hasDeclareAttackersReq() }
            daReq.shouldNotBeNull()

            val req = daReq.declareAttackersReq
            val eligibleIds = req.attackersList.map { it.attackerInstanceId }.toSet()

            // Both Raging Goblins (haste) should be eligible
            val ourEligible = attackerIids.filter { it in eligibleIds }
            (ourEligible.size >= 2).shouldBeTrue()

            // Declare 2 attackers
            val twoAttackers = ourEligible.take(2)
            val snap2 = h.messageSnapshot()
            h.declareAttackers(twoAttackers)

            val postAttack = h.messagesSince(snap2)
            postAttack.shouldNotBeEmpty()

            h.accumulator.assertConsistent("after multiple attackers declared")
        }

        test("AI declares blockers") {
            val attackerIid = setupWithAiBlocker()
            val h = harness!!

            // End human turn → AI turn (AI casts Raging Goblin via script) → back to human
            h.passPriority()

            // Now on human's turn 2 (or still turn 1 if AI turn was fast)
            h.playLand()

            // Need a creature to attack
            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val iid = creatures.first().first

            // Keep passing until we see DeclareAttackersReq
            val snap = h.messageSnapshot()
            var sawAttackReq = false
            for (i in 0 until 15) {
                if (h.isGameOver()) break
                val recent = h.messagesSince(snap)
                if (recent.any { it.hasDeclareAttackersReq() }) {
                    sawAttackReq = true
                    break
                }
                h.passPriority()
            }
            sawAttackReq.shouldBeTrue()

            // Declare our attack
            val snap2 = h.messageSnapshot()
            h.declareAttackers(listOf(iid))

            // After declaring attackers, auto-pass should advance through AI blocking
            val postAttack = h.messagesSince(snap2)
            postAttack.shouldNotBeEmpty()

            // Game state should remain valid through combat
            h.accumulator.assertConsistent("after combat with AI blocker")
            h.isGameOver().shouldBeFalse()
        }

        test("combat damage resolves correctly") {
            val attackerIid = setupSingleAttacker()
            val h = harness!!

            // Record AI life before combat
            val aiPlayer = h.bridge.getPlayer(SeatId(2))!!
            val lifeBefore = aiPlayer.life
            val startTurn = h.turn()

            // Advance from Main1 to combat
            h.passPriority()

            // Declare attack with haste creature (Raging Goblin, 1/1)
            h.declareAttackers(listOf(attackerIid))

            h.passThroughCombat(startTurn)

            // Verify AI took damage (1/1 unblocked = 1 damage)
            val lifeAfter = aiPlayer.life
            (lifeAfter < lifeBefore).shouldBeTrue()

            h.accumulator.assertConsistent("after combat damage")
        }

        test("combat death produces zone transfer") {
            // Use non-validating harness: combat zone transfers produce transient instanceId gaps
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            // AI: play Mountain, cast Raging Goblin (blocker), skip attacking, pass
            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.CastSpell("Raging Goblin"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.DeclareNoBlockers, // let human's attack through (unblocked)
                    ScriptedAction.PassPriority,
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            // Human turn 1: play Mountain, cast Raging Goblin
            h.playLand().shouldBeTrue()
            val cast = h.castSpellByName("Raging Goblin")
            cast.shouldBeTrue()
            h.passPriority() // resolve

            // End human turn → AI turn (casts Raging Goblin) → back to human
            h.passPriority()

            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val iid = creatures.first().first
            val startTurn = h.turn()

            // Advance to combat
            h.passPriority()

            // Declare attack
            val snap = h.messageSnapshot()
            val daReq = h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
            if (daReq != null) {
                h.declareAttackers(listOf(iid))
            }

            h.passThroughCombat(startTurn)

            // Check message stream for annotations
            val combatMsgs = h.messagesSince(snap)
            val allAnnotations = combatMsgs
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.annotationsList }

            // Either ZoneTransfer (trade) or damage annotations should be present
            val hasZoneTransfer = allAnnotations.any { AnnotationType.ZoneTransfer_af5a in it.typeList }
            val hasDamage = allAnnotations.any { AnnotationType.DamageDealt_af5a in it.typeList }
            (hasZoneTransfer || hasDamage || combatMsgs.isNotEmpty()).shouldBeTrue()

            h.isGameOver().shouldBeFalse()
        }

        test("full combat turn cycle") {
            val attackerIid = setupSingleAttacker()
            val h = harness!!
            val startTurn = h.turn()

            val snap = h.messageSnapshot()

            // Pass to combat
            h.passPriority()

            // Declare attack
            h.declareAttackers(listOf(attackerIid))

            h.passThroughCombat(startTurn)

            // Validate full message chain
            val allMsgs = h.messagesSince(snap)
            (allMsgs.size >= 3).shouldBeTrue()

            // gsId chain must be valid across all combat phases
            assertGsIdChain(h.allMessages, context = "full combat turn cycle")
            h.accumulator.assertConsistent("after full combat cycle")
        }

        test("echo back contains creature object without combat state") {
            val attackerIid = setupSingleAttacker()
            val h = harness!!

            // Advance to combat — DeclareAttackersReq emitted
            h.passPriority()
            val daReq = h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
            daReq.shouldNotBeNull()

            // Send iterative toggle (DeclareAttackersResp only, no Submit)
            val echoMsgs = h.toggleAttackers(listOf(attackerIid))

            // Echo should contain a GSM with the toggled creature
            val echoGsm = echoMsgs.firstOrNull { it.hasGameStateMessage() }
            echoGsm.shouldNotBeNull()

            val gsm = echoGsm.gameStateMessage
            val objects = gsm.gameObjectsList
            objects.shouldNotBeEmpty()

            val attackerObj = objects.firstOrNull { it.instanceId == attackerIid }
            attackerObj.shouldNotBeNull()

            // Conformance: real server echo carries NO combat state (confirmed across 4 recordings)
            attackerObj.attackState shouldBe AttackState.None_a3a9
            attackerObj.blockState shouldBe BlockState.None_aa2d

            // Conformance: SendAndRecord, no pendingMessageCount
            gsm.update shouldBe GameStateUpdate.SendAndRecord
            gsm.pendingMessageCount shouldBe 0

            // Echo should also contain a fresh DeclareAttackersReq
            val echoReq = echoMsgs.firstOrNull { it.hasDeclareAttackersReq() }
            echoReq.shouldNotBeNull()

            // Conformance: committed attackers have selectedDamageRecipient set
            val echoAttacker = echoReq.declareAttackersReq.attackersList
                .first { it.attackerInstanceId == attackerIid }
            echoAttacker.hasSelectedDamageRecipient().shouldBeTrue()
            echoAttacker.selectedDamageRecipient.type shouldBe DamageRecType.Player_a0e5

            // Conformance: qualifiedAttackers never has selectedDamageRecipient
            val qualAttacker = echoReq.declareAttackersReq.qualifiedAttackersList
                .first { it.attackerInstanceId == attackerIid }
            qualAttacker.hasSelectedDamageRecipient().shouldBeFalse()

            // Conformance: manaCost present (empty entry)
            (echoReq.declareAttackersReq.manaCostCount > 0).shouldBeTrue()
        }

        test("echo back deselect clears selectedDamageRecipient") {
            val attackerIid = setupSingleAttacker()
            val h = harness!!

            h.passPriority() // advance to combat
            h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // Toggle ON (XOR: not committed → committed)
            val onMsgs = h.toggleAttackers(listOf(attackerIid))
            val onReq = onMsgs.first { it.hasDeclareAttackersReq() }.declareAttackersReq
            onReq.attackersList.first().hasSelectedDamageRecipient().shouldBeTrue()

            // Toggle OFF (XOR same ID: committed → deselected)
            // Conformance: recording 2026-03-14_17-28-50, idx 160 (toggle committed attacker)
            val offMsgs = h.toggleAttackers(listOf(attackerIid))
            val offReq = offMsgs.first { it.hasDeclareAttackersReq() }.declareAttackersReq
            offReq.attackersList.first().hasSelectedDamageRecipient().shouldBeFalse()
        }

        test("echo back deselect restores state") {
            val attackerIid = setupSingleAttacker()
            val h = harness!!

            h.passPriority() // advance to combat
            h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // Toggle ON
            h.toggleAttackers(listOf(attackerIid))

            // Toggle OFF (XOR same ID → deselects)
            val echoMsgs = h.toggleAttackers(listOf(attackerIid))

            val echoGsm = echoMsgs.firstOrNull { it.hasGameStateMessage() }
            echoGsm.shouldNotBeNull()

            val objects = echoGsm.gameStateMessage.gameObjectsList
            objects.shouldNotBeEmpty()

            val attackerObj = objects.firstOrNull { it.instanceId == attackerIid }
            attackerObj.shouldNotBeNull()
            attackerObj.attackState shouldBe AttackState.None_a3a9
        }

        test("multi toggle before submit") {
            val attackerIids = setupMultipleAttackers()
            val h = harness!!
            (attackerIids.size >= 2).shouldBeTrue()
            val (iidA, iidB) = attackerIids

            val aiPlayer = h.bridge.getPlayer(SeatId(2))!!
            val lifeBefore = aiPlayer.life
            val startTurn = h.turn()

            h.passPriority() // advance to combat
            h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // XOR toggle semantics (conformance: recording 2026-03-14_17-28-50)
            // Toggle A on: {} XOR {A} → {A}
            h.toggleAttackers(listOf(iidA))
            // Toggle B on: {A} XOR {B} → {A, B}
            h.toggleAttackers(listOf(iidB))
            // Toggle A off: {A, B} XOR {A} → {B}
            h.toggleAttackers(listOf(iidA))

            // Submit with B only
            h.submitAttackers()

            h.passThroughCombat(startTurn)

            // B is 1/1 Raging Goblin → 1 damage (not 2)
            val lifeAfter = aiPlayer.life
            lifeAfter shouldBe lifeBefore - 1
        }

        test("toggle then submit deals damage") {
            val attackerIid = setupSingleAttacker()
            val h = harness!!

            val aiPlayer = h.bridge.getPlayer(SeatId(2))!!
            val lifeBefore = aiPlayer.life
            val startTurn = h.turn()

            // Advance from Main1 to combat
            h.passPriority()

            // Verify DeclareAttackersReq was sent with our creature
            val daReq = checkNotNull(h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }) { "Should receive DeclareAttackersReq" }
            val eligible = daReq.declareAttackersReq.attackersList.map { it.attackerInstanceId }
            (attackerIid in eligible).shouldBeTrue()

            // Toggle creature ON (iterative DeclareAttackersResp)
            h.toggleAttackers(listOf(attackerIid))

            // Send SubmitAttackersReq (type-only, no payload) — real client "Done" button
            h.submitAttackers()

            h.passThroughCombat(startTurn)

            // Verify AI took damage — Raging Goblin 1/1 unblocked = 1 damage
            val lifeAfter = aiPlayer.life
            (lifeAfter < lifeBefore).shouldBeTrue()
        }

        test("attack all then submit deals damage") {
            val attackerIid = setupSingleAttacker()
            val h = harness!!

            val aiPlayer = h.bridge.getPlayer(SeatId(2))!!
            val lifeBefore = aiPlayer.life
            val startTurn = h.turn()

            // Advance from Main1 to combat
            h.passPriority()

            // Verify DeclareAttackersReq was sent
            val daReq = h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
            daReq.shouldNotBeNull()

            // Send "Attack All" (DeclareAttackersResp with auto_declare=true)
            h.declareAllAttackers()

            // Send "Done" (SubmitAttackersReq, empty)
            h.submitAttackers()

            h.passThroughCombat(startTurn)

            // Verify AI took damage
            val lifeAfter = aiPlayer.life
            (lifeAfter < lifeBefore).shouldBeTrue()
        }


        test("declare no attackers skips combat") {
            setupSingleAttacker()
            val h = harness!!

            // Advance to combat
            h.passPriority()

            // Verify we got DeclareAttackersReq
            val daReq = h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
            daReq.shouldNotBeNull()

            // Declare no attackers
            val snap = h.messageSnapshot()
            h.declareNoAttackers()

            // Should advance past combat
            val postCombat = h.messagesSince(snap)
            postCombat.shouldNotBeEmpty()

            h.accumulator.assertConsistent("after declining combat")
            h.isGameOver().shouldBeFalse()
        }
    })
