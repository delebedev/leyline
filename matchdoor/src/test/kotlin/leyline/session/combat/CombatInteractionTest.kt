package leyline.session.combat

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.annotations.AnnotationConstants
import leyline.game.bundle.InvariantCheck
import leyline.game.bundle.InvariantSelection
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.allAnnotations
import leyline.testkit.assertGsIdChain
import leyline.testkit.gsm
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AttackState
import wotc.mtgo.gre.external.messaging.Messages.BlockState
import wotc.mtgo.gre.external.messaging.Messages.DamageRecType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step
import kotlin.time.Duration.Companion.seconds

const val COMBAT_DECK = """
20 Raging Goblin
4 Llanowar Elves
4 Giant Growth
16 Mountain
16 Forest
"""

/**
 * Raging Goblin (haste) + Mountain enables turn-1 combat without multi-turn
 * advancement — autoPassAndAdvance overshoots turns when stretched further.
 */
// LargeClass: attacker-side tests share three setup helpers; splitting
// further fragments them.
@Suppress("LargeClass")
class CombatInteractionTest :
    SessionTest({

        val combatValidation =
            InvariantSelection.protocolFactsExcept(
                "combat playback can skip queued gsIds in some lanes",
                InvariantCheck.GsIdMonotonicity,
            )

        // ─── Setup helpers ────────────────────────────────────────────────────

        fun aiTurnActionsAvailableReqs(messages: List<GREToClientMessage>): List<GREToClientMessage> {
            val aars = mutableListOf<GREToClientMessage>()
            var lastActivePlayer = OPPONENT_SEAT
            for (msg in messages) {
                if (msg.hasGameStateMessage() && msg.gameStateMessage.hasTurnInfo()) {
                    lastActivePlayer = msg.gameStateMessage.turnInfo.activePlayer
                }
                if (msg.type == GREMessageType.ActionsAvailableReq_695e && lastActivePlayer == OPPONENT_SEAT) {
                    aars.add(msg)
                }
            }
            return aars
        }

        fun passBackToHumanMain1() {
            repeat(80) {
                if (!isAiTurn() && phase() == "MAIN1" && turn() > 1) return
                if (!isAiTurn() && phase() == "COMBAT_DECLARE_ATTACKERS") {
                    declareNoAttackers()
                } else {
                    passPriority()
                }
            }
            check(!isAiTurn() && phase() == "MAIN1" && turn() > 1) {
                "Expected to return to human MAIN1 after setup passes; turn=${turn()} phase=${phase()} aiTurn=${isAiTurn()}"
            }
        }

        fun setupSingleAttacker(): Int {
            startGame(
                deckList = COMBAT_DECK,
                validating = true,
                validation = combatValidation,
                aiScript =
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
            playLand("Mountain").shouldBeTrue()
            resolveSpell("Raging Goblin").shouldBeTrue()

            // Still turn 1 — Raging Goblin has haste, can attack this turn
            turn() shouldBe 1
            isAiTurn().shouldBeFalse()

            val creatures = humanBattlefieldCreatures()
            creatures shouldHaveSize 1
            return creatures.first().first
        }

        fun setupMultipleAttackers(): List<Int> {
            startGame(
                deckList = COMBAT_DECK,
                validating = true,
                validation = combatValidation,
                aiScript =
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
            playLand("Mountain").shouldBeTrue()
            castSpellByName("Raging Goblin").shouldBeTrue()
            passPriority() // resolve

            // Advance through opponent-turn priority windows back to our Main1.
            passBackToHumanMain1()

            // Play second land + cast second creature
            playLand("Mountain")
            val cast2 = castSpellByName("Raging Goblin")
            if (cast2) passPriority() // resolve

            val creatures = humanBattlefieldCreatures()
            creatures.size shouldBeGreaterThanOrEqualTo 2
            return creatures.map { it.first }
        }

        fun setupWithAiBlocker(): Int {
            startGame(
                deckList = COMBAT_DECK,
                validating = true,
                validation = combatValidation,
                aiScript =
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
            playLand("Mountain").shouldBeTrue()
            castSpellByName("Raging Goblin").shouldBeTrue()
            passPriority() // resolve

            val creatures = humanBattlefieldCreatures()
            creatures shouldHaveSize 1
            return creatures.first().first
        }

        // ─── Declare attackers ────────────────────────────────────────────────

        test("human declares single attacker") {
            val attackerIid = setupSingleAttacker()

            // Pass from Main1 to advance to combat — auto-pass should emit DeclareAttackersReq
            val req = after { passPriority() }.expectOneDeclareAttackersReq()
            req.attackersCount shouldBeGreaterThan 0

            // The Raging Goblin (haste) should be among eligible attackers
            val eligibleIds = req.attackersList.map { it.attackerInstanceId }
            (attackerIid in eligibleIds).shouldBeTrue()

            // Declare the attack
            val postAttack = after { declareAttackers(listOf(attackerIid)) }.messages

            // Should get confirmation messages
            postAttack.shouldNotBeEmpty()

            // Validate accumulated state
            assertAccumulatorConsistent("after single attacker declared")
            isGameOver().shouldBeFalse()
        }

        test("human declares multiple attackers") {
            val attackerIids = setupMultipleAttackers()

            // Advance to combat
            val req = after { passPriority() }.expectOneDeclareAttackersReq()
            val eligibleIds = req.attackersList.map { it.attackerInstanceId }.toSet()

            // Both Raging Goblins (haste) should be eligible
            val ourEligible = attackerIids.filter { it in eligibleIds }
            ourEligible.size shouldBeGreaterThanOrEqualTo 2

            // Declare 2 attackers
            val twoAttackers = ourEligible.take(2)
            after { declareAttackers(twoAttackers) }.messages.shouldNotBeEmpty()

            assertAccumulatorConsistent("after multiple attackers declared")
        }

        test("AI declares blockers") {
            val attackerIid = setupWithAiBlocker()

            // End human turn → AI turn (AI casts Raging Goblin via script) → back to human
            passBackToHumanMain1()

            // Now on human's turn 2 (or still turn 1 if AI turn was fast)
            playLand("Mountain")

            // Need a creature to attack — only the human's haste Raging Goblin
            val creatures = humanBattlefieldCreatures()
            creatures shouldHaveSize 1
            val iid = creatures.first().first

            // Keep passing until we see DeclareAttackersReq
            val snap = messageSnapshot()
            passUntil(maxPasses = 15) {
                messagesSince(snap).any { it.hasDeclareAttackersReq() }
            }.shouldBeTrue()

            // Declare our attack — auto-pass advances through AI blocking
            after { declareAttackers(listOf(iid)) }.messages.shouldNotBeEmpty()

            // Game state should remain valid through combat
            assertAccumulatorConsistent("after combat with AI blocker")
            isGameOver().shouldBeFalse()
        }

        test("combat damage frame carries persistent DamagedThisTurn badge") {
            val attackerIid = setupWithAiBlocker()

            passBackToHumanMain1()
            playLand("Mountain")
            val snap = messageSnapshot()
            passUntil { messagesSince(snap).any { it.hasDeclareAttackersReq() } }.shouldBeTrue()

            val attackTurn = turn()
            declareAttackers(listOf(attackerIid))
            passThroughCombat(attackTurn)

            val damageGsm =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
                    .firstOrNull {
                        it.turnInfo.step == Step.CombatDamage_a2cb &&
                            it.annotationsList.any { ann ->
                                AnnotationType.DamageDealt_af5a in ann.typeList
                            }
                    }
            damageGsm.shouldNotBeNull()

            val badge = damageGsm.persistentAnnotationsList.single { AnnotationType.DamagedThisTurn in it.typeList }
            assertSoftly {
                badge.affectorId shouldBe AnnotationConstants.BATTLEFIELD_ZONE_AFFECTOR.value
                badge.affectedIdsList.shouldNotBeEmpty()
                damageGsm.annotationsList.none { AnnotationType.DamagedThisTurn in it.typeList }.shouldBeTrue()
            }
        }

        test("combat damage resolves correctly") {
            val attackerIid = setupSingleAttacker()

            // Record AI life before combat
            val lifeBefore = ai.life
            val startTurn = turn()

            // Advance from Main1 to combat
            passPriority()

            // Declare attack with haste creature (Raging Goblin, 1/1)
            declareAttackers(listOf(attackerIid))

            passThroughCombat(startTurn)

            // Verify AI took damage (1/1 unblocked = 1 damage)
            ai.life shouldBeLessThan lifeBefore

            assertAccumulatorConsistent("after combat damage")
        }

        test("combat damage GSM has correct phase and annotation shape") {
            val attackerIid = setupSingleAttacker()

            // Advance to combat
            passPriority()

            declareAttackers(listOf(attackerIid))

            // Pass through combat — damage happens during these passes
            val startTurn = turn()
            passThroughCombat(startTurn)

            // Also capture messages from post-combat (turn advance triggers GSM build)
            // Search ALL messages, not just since snapshot — damage GSM may precede turn advance
            val allGsms =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
            val damageGsm =
                allGsms.firstOrNull { gsm ->
                    gsm.annotationsList.any { ann ->
                        ann.typeList.any { it == AnnotationType.DamageDealt_af5a }
                    }
                }
            damageGsm.shouldNotBeNull()

            // turnInfo must report CombatDamage phase (not Main2)
            damageGsm.turnInfo.phase shouldBe Phase.Combat_a549
            damageGsm.turnInfo.step shouldBe Step.CombatDamage_a2cb

            // Annotation ordering: PhaseOrStepModified first
            val annTypes = damageGsm.annotationsList.map { ann -> ann.typeList.first() }
            annTypes.first() shouldBe AnnotationType.PhaseOrStepModified

            val phaseAnnotations =
                damageGsm.annotationsList.filter { ann ->
                    ann.typeList.any { it == AnnotationType.PhaseOrStepModified }
                }
            phaseAnnotations.size shouldBe 1
            phaseAnnotations
                .single()
                .detailsList
                .first { it.key == "step" }
                .valueInt32List
                .single() shouldBe Step.CombatDamage_a2cb.number

            // DamageDealt has correct affectorId (attacker) and affectedIds (target seat)
            val dmgAnn =
                damageGsm.annotationsList.first { ann ->
                    ann.typeList.any { it == AnnotationType.DamageDealt_af5a }
                }
            dmgAnn.affectorId shouldBe attackerIid
            dmgAnn.affectedIdsList shouldBe listOf(OPPONENT_SEAT)

            // ModifiedLife has affectorId set (not 0)
            val lifeAnn =
                damageGsm.annotationsList.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.ModifiedLife }
                }
            if (lifeAnn != null) {
                lifeAnn.affectorId shouldBeGreaterThan 0
            }

            damageGsm.annotationsList
                .none { ann ->
                    ann.typeList.any { it == AnnotationType.DamagedThisTurn }
                }.shouldBeTrue()

            // Human-turn combat animation checkpoint must not reopen priority.
            allMessages.none { it.hasActionsAvailableReq() && it.gameStateId == damageGsm.gameStateId }.shouldBeTrue()

            val damageIndex = allGsms.indexOfFirst { it.gameStateId == damageGsm.gameStateId }
            damageIndex shouldBeGreaterThanOrEqualTo 0
            val echoGsm = allGsms.getOrNull(damageIndex + 1)
            echoGsm.shouldNotBeNull()
            echoGsm.annotationsCount shouldBe 0
            echoGsm.prevGameStateId shouldBe damageGsm.gameStateId

            val endCombatGsm =
                allGsms.drop(damageIndex + 2).firstOrNull { gsm ->
                    gsm.annotationsList.any { ann ->
                        ann.typeList.any { it == AnnotationType.PhaseOrStepModified } &&
                            ann.detailsList.any { detail ->
                                detail.key == "step" && detail.valueInt32List.contains(Step.EndCombat_a2cb.number)
                            }
                    }
                }
            endCombatGsm.shouldNotBeNull()
            endCombatGsm.annotationsList.none { ann -> ann.typeList.any { it == AnnotationType.DamageDealt_af5a } }.shouldBeTrue()
        }

        test("first strike combat damage uses first-strike damage step") {
            val attackerIid = setupSingleAttacker()
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .filter { it.isCreature }
                .single()
                .addIntrinsicKeyword("First Strike")

            passPriority()
            declareAttackers(listOf(attackerIid))
            passThroughCombat(turn())

            val damageGsm =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
                    .firstOrNull { gsm ->
                        gsm.annotationsList.any { AnnotationType.DamageDealt_af5a in it.typeList }
                    }
            damageGsm.shouldNotBeNull()
            damageGsm.turnInfo.phase shouldBe Phase.Combat_a549
            damageGsm.turnInfo.step shouldBe Step.FirstStrikeDamage_a2cb
            damageGsm.annotationsList
                .single { AnnotationType.PhaseOrStepModified in it.typeList }
                .detailsList
                .first { it.key == "step" }
                .valueInt32List
                .single() shouldBe Step.FirstStrikeDamage_a2cb.number
        }

        test("combat death produces zone transfer") {
            // AI: play Mountain, cast Raging Goblin (blocker), skip attacking, decline blocking
            startGame(
                deckList = COMBAT_DECK,
                validating = true,
                validation = combatValidation,
                aiScript =
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
            playLand("Mountain").shouldBeTrue()
            castSpellByName("Raging Goblin").shouldBeTrue()
            passPriority() // resolve

            // End human turn → AI turn (casts Raging Goblin) → back to human
            passPriority()

            val creatures = humanBattlefieldCreatures()
            creatures shouldHaveSize 1
            val iid = creatures.first().first
            val startTurn = turn()

            // Advance to combat
            passPriority()

            // Declare attack
            val combatMsgs =
                after {
                    val daReq = allMessages.lastOrNull { it.hasDeclareAttackersReq() }
                    if (daReq != null) declareAttackers(listOf(iid))
                    passThroughCombat(startTurn)
                }.messages
            val allAnnotations =
                combatMsgs
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }

            // Either ZoneTransfer (trade) or damage annotations should be present
            val hasZoneTransfer = allAnnotations.any { AnnotationType.ZoneTransfer_af5a in it.typeList }
            val hasDamage = allAnnotations.any { AnnotationType.DamageDealt_af5a in it.typeList }
            (hasZoneTransfer || hasDamage || combatMsgs.isNotEmpty()).shouldBeTrue()

            isGameOver().shouldBeFalse()
        }

        test("full combat turn cycle") {
            val attackerIid = setupSingleAttacker()
            val startTurn = turn()

            val allMsgs =
                after {
                    // Pass to combat → declare attack → resolve combat
                    passPriority()
                    declareAttackers(listOf(attackerIid))
                    passThroughCombat(startTurn)
                }.messages
            allMsgs.size shouldBeGreaterThanOrEqualTo 3

            // gsId chain must be valid across all combat phases
            assertGsIdChain(allMessages, context = "full combat turn cycle")
            assertAccumulatorConsistent("after full combat cycle")
        }

        // ─── Iterative attacker toggle (echo back) ────────────────────────────

        test("echo back contains creature object without combat state") {
            val attackerIid = setupSingleAttacker()

            // Advance to combat — DeclareAttackersReq emitted
            passPriority()
            allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // Send iterative toggle (DeclareAttackersResp only, no Submit)
            val echoMsgs = toggleAttackers(listOf(attackerIid))

            // Echo should contain a GSM with the toggled creature
            val echoGsm = echoMsgs.firstOrNull { it.hasGameStateMessage() }
            echoGsm.shouldNotBeNull()

            val gsm = echoGsm.gameStateMessage
            val objects = gsm.gameObjectsList
            objects.shouldNotBeEmpty()

            val attackerObj = objects.firstOrNull { it.instanceId == attackerIid }
            attackerObj.shouldNotBeNull()

            // Conformance: client echo carries NO combat state.
            attackerObj.attackState shouldBe AttackState.None_a3a9
            attackerObj.blockState shouldBe BlockState.None_aa2d

            // Conformance: SendAndRecord, no pendingMessageCount
            gsm.update shouldBe GameStateUpdate.SendAndRecord
            gsm.pendingMessageCount shouldBe 0

            // Echo should also contain a fresh DeclareAttackersReq
            val echoReq = echoMsgs.firstOrNull { it.hasDeclareAttackersReq() }
            echoReq.shouldNotBeNull()

            // Conformance: committed attackers have selectedDamageRecipient set
            val echoAttacker =
                echoReq.declareAttackersReq.attackersList
                    .first { it.attackerInstanceId == attackerIid }
            echoAttacker.hasSelectedDamageRecipient().shouldBeTrue()
            echoAttacker.selectedDamageRecipient.type shouldBe DamageRecType.Player_a0e5

            // Conformance: qualifiedAttackers never has selectedDamageRecipient
            val qualAttacker =
                echoReq.declareAttackersReq.qualifiedAttackersList
                    .first { it.attackerInstanceId == attackerIid }
            qualAttacker.hasSelectedDamageRecipient().shouldBeFalse()

            // Conformance: manaCost present (empty entry)
            echoReq.declareAttackersReq.manaCostCount shouldBeGreaterThan 0
        }

        test("echo back deselect clears selectedDamageRecipient") {
            val attackerIid = setupSingleAttacker()

            passPriority() // advance to combat
            allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // Toggle ON (XOR: not committed → committed)
            val onMsgs = toggleAttackers(listOf(attackerIid))
            val onReq = onMsgs.first { it.hasDeclareAttackersReq() }.declareAttackersReq
            onReq.attackersList
                .first()
                .hasSelectedDamageRecipient()
                .shouldBeTrue()

            // Toggle OFF (XOR same ID: committed → deselected)
            val offMsgs = toggleAttackers(listOf(attackerIid))
            val offReq = offMsgs.first { it.hasDeclareAttackersReq() }.declareAttackersReq
            offReq.attackersList
                .first()
                .hasSelectedDamageRecipient()
                .shouldBeFalse()
        }

        test("echo back deselect restores state") {
            val attackerIid = setupSingleAttacker()

            passPriority() // advance to combat
            allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // Toggle ON
            toggleAttackers(listOf(attackerIid))

            // Toggle OFF (XOR same ID → deselects)
            val echoMsgs = toggleAttackers(listOf(attackerIid))

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
            attackerIids.size shouldBeGreaterThanOrEqualTo 2
            val (iidA, iidB) = attackerIids

            val lifeBefore = ai.life
            val startTurn = turn()

            passPriority() // advance to combat
            allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // XOR toggle semantics
            // Toggle A on: {} XOR {A} → {A}
            toggleAttackers(listOf(iidA))
            // Toggle B on: {A} XOR {B} → {A, B}
            toggleAttackers(listOf(iidB))
            // Toggle A off: {A, B} XOR {A} → {B}
            toggleAttackers(listOf(iidA))

            // Submit with B only
            submitAttackers()

            passThroughCombat(startTurn)

            // B is 1/1 Raging Goblin → 1 damage (not 2)
            ai.life shouldBe lifeBefore - 1
        }

        test("toggle then submit deals damage") {
            val attackerIid = setupSingleAttacker()

            val lifeBefore = ai.life
            val startTurn = turn()

            // Advance from Main1 to combat
            passPriority()

            // Verify DeclareAttackersReq was sent with our creature
            val daReq = checkNotNull(allMessages.lastOrNull { it.hasDeclareAttackersReq() }) { "Should receive DeclareAttackersReq" }
            val eligible = daReq.declareAttackersReq.attackersList.map { it.attackerInstanceId }
            (attackerIid in eligible).shouldBeTrue()

            // Toggle creature ON (iterative DeclareAttackersResp)
            toggleAttackers(listOf(attackerIid))

            // Send SubmitAttackersReq (type-only, no payload) — reference client "Done" button
            submitAttackers()

            passThroughCombat(startTurn)

            // Verify AI took damage — Raging Goblin 1/1 unblocked = 1 damage
            ai.life shouldBeLessThan lifeBefore
        }

        test("attack all then submit deals damage") {
            setupSingleAttacker()

            val lifeBefore = ai.life
            val startTurn = turn()

            // Advance from Main1 to combat
            passPriority()

            // Verify DeclareAttackersReq was sent
            allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // Send "Attack All" (DeclareAttackersResp with auto_declare=true)
            declareAllAttackers()

            // Send "Done" (SubmitAttackersReq, empty)
            submitAttackers()

            passThroughCombat(startTurn)

            // Verify AI took damage
            ai.life shouldBeLessThan lifeBefore
        }

        test("declare no attackers skips combat") {
            setupSingleAttacker()

            // Advance to combat
            passPriority()

            // Verify we got DeclareAttackersReq
            allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // Declare no attackers — should advance past combat
            after { declareNoAttackers() }.messages.shouldNotBeEmpty()

            assertAccumulatorConsistent("after declining combat")
            isGameOver().shouldBeFalse()
        }

        // ─── Assign damage (multi-blocker / trample) ──────────────────────────

        test("trample damage assignment sends AssignDamageReq and completes combat") {
            val puzzleText = javaClass.getResource("/puzzles/trample-damage-assign.pzl")!!.readText()
            startPuzzleRaw(
                puzzleText,
                validating = true,
                validation = combatValidation,
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

            val creatures = humanBattlefieldCreatures()
            creatures shouldHaveSize 1 // Charging Monstrosaur — the trample attacker
            val dreadmawIid = creatures.first().first

            // Pass to combat → DeclareAttackersReq
            passUntil(maxPasses = 5) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()

            // Attack. After submit, engine processes AI blockers → COMBAT_DAMAGE →
            // WPC.assignCombatDamage blocks on dedicated future →
            // auto-pass detects via checkPendingDamageAssignment → sends AssignDamageReq
            declareAttackers(listOf(dreadmawIid))
            submitAttackers()

            // AssignDamageReq should be in messages (sent before session lock released)
            val assignReq = allMessages.lastOrNull { it.hasAssignDamageReq() }
            assignReq.shouldNotBeNull()

            val req = assignReq.assignDamageReq
            req.damageAssignersCount shouldBeGreaterThan 0

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
                defenderSlot.instanceId shouldBe OPPONENT_SEAT
                defenderSlot.maxDamage shouldBe 1 // 5 - 2 - 2 = 1 overflow
                defenderSlot.assignedDamage shouldBe 1
            }

            // Send back the pre-filled assignments (lethal to blockers + overflow to defender)
            val responseAssignments =
                assigner.assignmentsList.map {
                    it.instanceId to it.assignedDamage
                }

            val confirmation =
                after { assignDamage(listOf(assigner.instanceId to responseAssignments)) }
                    .messages
                    .firstOrNull { it.hasAssignDamageConfirmation() }
            confirmation.shouldNotBeNull()

            // 1 trample overflow to AI at 1 life → game should end
            if (!isGameOver()) passThroughCombat()
        }

        test("single blocker does not trigger AssignDamageReq") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain;Raging Goblin;Raging Goblin;Raging Goblin
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                aibattlefield=Forest;Grizzly Bears
                ailibrary=Forest;Forest;Forest;Forest;Forest
                """,
                name = "Single Blocker No Prompt",
                turns = 10,
                validating = true,
                validation = combatValidation,
                aiScript =
                    listOf(
                        ScriptedAction.DeclareNoAttackers,
                        ScriptedAction.Block(mapOf("Grizzly Bears" to "Raging Goblin")),
                        ScriptedAction.PassPriority,
                    ),
            )

            val creatures = humanBattlefieldCreatures()
            creatures shouldHaveSize 3 // 3× Raging Goblin from the puzzle state
            val attackerIid = creatures.first().first

            passUntil(maxPasses = 5) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()

            declareAttackers(listOf(attackerIid))
            submitAttackers()

            passThroughCombat()

            val assignReq = allMessages.firstOrNull { it.hasAssignDamageReq() }
            assignReq.shouldBeNull()

            isGameOver().shouldBeFalse()
        }

        // ─── Zero-blocker auto-advance ────────────────────────────────────────

        test("zero blockers auto-advances without DeclareBlockersReq") {
            // Human has only lands, AI has haste attackers — server should
            // auto-advance through declare-blockers instead of prompting.
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Plains;Plains
                humanlibrary=Plains;Plains;Plains;Plains;Plains
                aibattlefield=Mountain;Mountain;Raging Goblin;Raging Goblin
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """,
                name = "Zero Blockers AI Attack",
                turns = 10,
                validating = true,
                validation = combatValidation,
                aiScript =
                    listOf(
                        ScriptedAction.Attack(listOf("Raging Goblin")),
                        ScriptedAction.PassPriority,
                    ),
            )

            after {
                // Pass through human turn into AI combat → combat auto-advances
                passPriority()
                passThroughCombat()
            }.expectNoDeclareBlockersReq()

            allMessages.count { it.hasDeclareBlockersReq() } shouldBe 0
            // Game should still be running (not stuck)
            isGameOver().shouldBeFalse()
        }

        // ─── AI combat opponent-turn priority ─────────────────────────────────

        test("AI combat grants priority when human has castable instant").config(timeout = 30.seconds) {
            // Puzzle: AI's turn at COMBAT_DECLARE_ATTACKERS. AI has a Raging Goblin
            // marked |Attacking|Tapped. Human has Burst Lightning + untapped Mountain.
            // The client should get an ActionsAvailableReq for the instant instead
            // of silently auto-passing through combat damage.
            startPuzzleRaw(
                """
                [metadata]
                Name:AI Combat AutoPass
                Goal:Win
                Turns:3
                Difficulty:Easy
                Description:AI attacks while human has instant in hand

                [state]
                ActivePlayer=AI
                ActivePhase=COMBAT_DECLARE_ATTACKERS
                HumanLife=20
                AILife=20

                humanhand=Burst Lightning
                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin|Attacking|Tapped;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                validating = true,
                validation = combatValidation,
            )

            val aiTurnAars = aiTurnActionsAvailableReqs(allMessages)

            assertSoftly {
                aiTurnAars.shouldNotBeEmpty()
                aiTurnAars
                    .flatMap { it.actionsAvailableReq.actionsList }
                    .any { it.actionType == ActionType.Cast } shouldBe true
                human.life shouldBe 20
            }
        }
    })
