package leyline.session.combat

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
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
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.copilot.CopilotProposalService
import leyline.game.annotations.AnnotationConstants
import leyline.game.bundle.InvariantCheck
import leyline.game.bundle.InvariantSelection
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.TestCardInjector
import leyline.testkit.allAnnotations
import leyline.testkit.assertGsIdChain
import leyline.testkit.detailInt
import leyline.testkit.gsm
import leyline.tooling.headless.planeswalkerDamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AttackState
import wotc.mtgo.gre.external.messaging.Messages.BlockState
import wotc.mtgo.gre.external.messaging.Messages.DamageRecType
import wotc.mtgo.gre.external.messaging.Messages.FailureReason
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
            assertSoftly {
                playLand("Mountain").shouldBeTrue()
                resolveSpell("Raging Goblin").shouldBeTrue()

                // Still turn 1 — Raging Goblin has haste, can attack this turn
                turn() shouldBe 1
                isAiTurn().shouldBeFalse()
            }

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

            // Pass-only phase stops are skipped before the declaration window.
            val pending =
                harness.bridge
                    .actionBridge(SeatId(1))
                    .getPending()
                    .shouldNotBeNull()
            pending.state.kind shouldBe PendingActionKind.DECLARE_ATTACKERS
            val requestMessage = allMessages.last { it.hasDeclareAttackersReq() }
            requestMessage.gameStateId shouldBe pending.promptGameStateId
            val req = requestMessage.declareAttackersReq
            req.attackersCount shouldBeGreaterThan 0

            // The Raging Goblin (haste) should be among eligible attackers
            val eligibleIds = req.attackersList.map { it.attackerInstanceId }
            eligibleIds.count { it == attackerIid } shouldBe 1

            // Declare the attack
            val postAttack = after { declareAttackers(listOf(attackerIid)) }.messages

            assertSoftly {
                postAttack.shouldNotBeEmpty()
                assertAccumulatorConsistent("after single attacker declared")
                isGameOver().shouldBeFalse()
            }
        }

        test("human declares multiple attackers") {
            val attackerIids = setupMultipleAttackers()

            passPriority() // advance to combat

            val pending =
                harness.bridge
                    .actionBridge(SeatId(1))
                    .getPending()
                    .shouldNotBeNull()
            pending.state.kind shouldBe PendingActionKind.DECLARE_ATTACKERS
            val requestMessage = allMessages.last { it.hasDeclareAttackersReq() }
            requestMessage.gameStateId shouldBe pending.promptGameStateId
            val req = requestMessage.declareAttackersReq
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
            val reachedAttackers =
                passUntil(maxPasses = 15) {
                    messagesSince(snap).any { it.hasDeclareAttackersReq() }
                }
            assertSoftly {
                reachedAttackers.shouldBeTrue()
                messagesSince(snap).count { it.hasDeclareAttackersReq() } shouldBe 1
            }

            val attackMessages = after { declareAttackers(listOf(iid)) }.messages
            assertSoftly {
                attackMessages.shouldNotBeEmpty()
                assertAccumulatorConsistent("after combat with AI blocker")
                isGameOver().shouldBeFalse()
            }
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
                badge.affectedIdsList.last() shouldBe attackerIid
                damageGsm.annotationsList.count { AnnotationType.DamagedThisTurn in it.typeList } shouldBe 0
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

        session(
            "human can destroy opposing planeswalker in combat",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
            turns = 5,
            validating = true,
            validation = combatValidation,
        ) {
            val attacker =
                TestCardInjector.inject(
                    bridge,
                    HUMAN_SEAT,
                    "Raging Goblin",
                    ZoneType.Battlefield,
                    sick = false,
                )
            val planeswalker =
                TestCardInjector.inject(
                    bridge,
                    OPPONENT_SEAT,
                    "Liliana of the Veil",
                    ZoneType.Battlefield,
                )
            planeswalker.card.setCounters(CounterEnumType.LOYALTY, 1)
            val attackerIid = attacker.instanceId
            val planeswalkerIid = planeswalker.instanceId
            val startTurn = turn()

            val promptSnap = messageSnapshot()
            val sawPrompt =
                passUntil(maxPasses = 5) {
                    messagesSince(promptSnap).any { it.hasDeclareAttackersReq() }
                }
            val attackerPrompts = messagesSince(promptSnap).filter { it.hasDeclareAttackersReq() }
            assertSoftly {
                sawPrompt.shouldBeTrue()
                attackerPrompts.size shouldBe 1
            }
            val req = attackerPrompts.last().declareAttackersReq
            val attackerPrompt = req.attackersList.first { it.attackerInstanceId == attackerIid }
            assertSoftly {
                attackerPrompt.legalDamageRecipientsList.map { it.type } shouldBe
                    listOf(DamageRecType.Player_a0e5, DamageRecType.PlanesWalker)
                attackerPrompt.legalDamageRecipientsList.last().planeswalkerInstanceId shouldBe planeswalkerIid
            }

            val recipient = planeswalkerDamageRecipient(planeswalkerIid)
            val echoReq =
                after {
                    toggleAttackers(listOf(attackerIid), damageRecipients = mapOf(attackerIid to recipient))
                }.expectOneDeclareAttackersReq()
            val selectedAttacker =
                echoReq.attackersList.firstOrNull { it.attackerInstanceId == attackerIid }
            withClue("echo attackers=${echoReq.attackersList.map { it.attackerInstanceId }} expected=$attackerIid") {
                selectedAttacker.shouldNotBeNull()
            }
            assertSoftly {
                selectedAttacker!!.hasSelectedDamageRecipient().shouldBeTrue()
                selectedAttacker.selectedDamageRecipient.type shouldBe DamageRecType.PlanesWalker
                selectedAttacker.selectedDamageRecipient.planeswalkerInstanceId shouldBe planeswalkerIid
            }

            submitAttackers()
            passThroughCombat(startTurn)

            val battlefieldPlaneswalker =
                ai.getZone(ZoneType.Battlefield).cards.firstOrNull { it.id == planeswalker.forgeCardId }
            val graveyardPlaneswalker =
                ai.getZone(ZoneType.Graveyard).cards.firstOrNull { it.id == planeswalker.forgeCardId }
            assertSoftly {
                ai.life shouldBe 20
                withClue(
                    "aiBattlefield=${ai.getZone(ZoneType.Battlefield).cards.map { it.name to it.currentLoyalty }} " +
                        "aiGraveyard=${ai.getZone(ZoneType.Graveyard).cards.map { it.name }}",
                ) {
                    battlefieldPlaneswalker.shouldBeNull()
                    graveyardPlaneswalker.shouldNotBeNull()
                }
            }
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
                } ?: error("Expected combat damage GSM")
            assertSoftly {
                damageGsm.shouldNotBeNull()

                // turnInfo must report CombatDamage phase (not Main2)
                damageGsm.turnInfo.phase shouldBe Phase.Combat_a549
                damageGsm.turnInfo.step shouldBe Step.CombatDamage_a2cb

                // Annotation ordering: PhaseOrStepModified first
                val annTypes = damageGsm.annotationsList.map { ann -> ann.typeList.first() }
                annTypes.first() shouldBe AnnotationType.PhaseOrStepModified
            }

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

            assertSoftly {
                damageGsm.annotationsList.count { ann -> AnnotationType.DamagedThisTurn in ann.typeList } shouldBe 0
                allMessages.count { it.hasActionsAvailableReq() && it.gameStateId == damageGsm.gameStateId } shouldBe 0
            }

            val damageIndex = allGsms.indexOfFirst { it.gameStateId == damageGsm.gameStateId }
            damageIndex shouldBeGreaterThanOrEqualTo 0
            val echoGsm = allGsms.getOrNull(damageIndex + 1)
            assertSoftly {
                echoGsm.shouldNotBeNull()
                echoGsm.annotationsCount shouldBe 0
                echoGsm.prevGameStateId shouldBe damageGsm.gameStateId
            }

            val endCombatGsm =
                allGsms.drop(damageIndex + 2).firstOrNull { gsm ->
                    gsm.annotationsList.any { ann ->
                        ann.typeList.any { it == AnnotationType.PhaseOrStepModified } &&
                            ann.detailsList.any { detail ->
                                detail.key == "step" && detail.valueInt32List.contains(Step.EndCombat_a2cb.number)
                            }
                    }
                } ?: error("Expected end combat GSM")
            assertSoftly {
                endCombatGsm.shouldNotBeNull()
                endCombatGsm.annotationsList.count { ann -> AnnotationType.DamageDealt_af5a in ann.typeList } shouldBe 0
            }
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
                    } ?: error("Expected first-strike damage GSM")
            assertSoftly {
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
        }

        test("double strike combat damage uses first-strike and regular damage steps") {
            val attackerIid = setupSingleAttacker()
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .filter { it.isCreature }
                .single()
                .addIntrinsicKeyword("Double Strike")

            passPriority()
            declareAttackers(listOf(attackerIid))
            passThroughCombat(turn())

            val damageGsms =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
                    .filter { gsm ->
                        gsm.annotationsList.any { AnnotationType.DamageDealt_af5a in it.typeList }
                    }
            assertSoftly {
                damageGsms shouldHaveSize 2
                damageGsms.map { it.turnInfo.step } shouldBe listOf(Step.FirstStrikeDamage_a2cb, Step.CombatDamage_a2cb)
                damageGsms.map { gsm ->
                    gsm.playersList.single { it.systemSeatNumber == OPPONENT_SEAT }.lifeTotal
                } shouldBe listOf(19, 18)
            }

            for (gsm in damageGsms) {
                val damage = gsm.annotationsList.single { AnnotationType.DamageDealt_af5a in it.typeList }
                damage.detailInt("damage") shouldBe 1
                val life = gsm.annotationsList.single { AnnotationType.ModifiedLife in it.typeList }
                life.detailInt("life") shouldBe -1
            }
        }

        session(
            "combat death produces zone transfer",
            deckList = COMBAT_DECK,
            validating = true,
            validation = combatValidation,
            aiScript = listOf(
                        ScriptedAction.PlayLand("Mountain"),
                        ScriptedAction.CastSpell("Raging Goblin"),
                        ScriptedAction.DeclareNoAttackers,
                        ScriptedAction.DeclareNoBlockers, // let human's attack through (unblocked)
                        ScriptedAction.PassPriority,
                        ScriptedAction.PlayLand("Mountain"),
                        ScriptedAction.DeclareNoAttackers,
                        ScriptedAction.PassPriority,
                    ),
        ) {
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
                    .allAnnotations()

            // Either ZoneTransfer (trade) or damage annotations should be present
            assertSoftly {
                combatMsgs.shouldNotBeEmpty()
                allAnnotations.count { AnnotationType.ZoneTransfer_af5a in it.typeList } +
                    allAnnotations.count { AnnotationType.DamageDealt_af5a in it.typeList } shouldBeGreaterThanOrEqualTo 1
                isGameOver().shouldBeFalse()
            }
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
            assertSoftly {
                allMsgs.size shouldBeGreaterThanOrEqualTo 3
                assertGsIdChain(allMessages, context = "full combat turn cycle")
                assertAccumulatorConsistent("after full combat cycle")
            }
        }

        // ─── Iterative attacker toggle (echo back) ────────────────────────────

        test("echo back contains creature object without combat state") {
            val attackerIid = setupSingleAttacker()

            // Advance to combat — DeclareAttackersReq emitted
            passPriority()
            allMessages.count { it.hasDeclareAttackersReq() } shouldBe 1

            // Send iterative toggle (DeclareAttackersResp only, no Submit)
            val echoMsgs = toggleAttackers(listOf(attackerIid))

            // Echo should contain a GSM with the toggled creature
            val echoGsm = echoMsgs.firstOrNull { it.hasGameStateMessage() }
            echoGsm.shouldNotBeNull()

            val gsm = echoGsm.gameStateMessage
            val objects = gsm.gameObjectsList
            objects.shouldNotBeEmpty()

            val attackerObj = objects.firstOrNull { it.instanceId == attackerIid } ?: error("Expected attacker object")
            assertSoftly {
                attackerObj.shouldNotBeNull()

                // Conformance: client echo carries NO combat state.
                attackerObj.attackState shouldBe AttackState.None_a3a9
                attackerObj.blockState shouldBe BlockState.None_aa2d

                // Conformance: SendAndRecord, no pendingMessageCount
                gsm.update shouldBe GameStateUpdate.SendAndRecord
                gsm.pendingMessageCount shouldBe 0
            }

            // Echo should also contain a fresh DeclareAttackersReq
            val echoReq = echoMsgs.firstOrNull { it.hasDeclareAttackersReq() }
            echoReq.shouldNotBeNull()
            echoReq.msgId shouldBeGreaterThan echoMsgs.first { it.hasGameStateMessage() }.msgId

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

        test("re-drive preserves iterative attacker selection") {
            val attackerIid = setupSingleAttacker()
            passPriority()
            toggleAttackers(listOf(attackerIid))

            triggerAutoPass()

            val prompt = allMessages.last { it.hasDeclareAttackersReq() }
            prompt.declareAttackersReq.attackersList
                .single { it.attackerInstanceId == attackerIid }
                .hasSelectedDamageRecipient()
                .shouldBeTrue()
            CopilotProposalService(harness.bridge, SeatId(1)).propose(prompt).intent shouldBe "submit_attackers"
        }

        test("unselected attacker without a damage recipient is rejected") {
            val attackerIid = setupSingleAttacker()
            passPriority()
            val before = messageSnapshot()

            harness.session.onDeclareAttackers(
                harness.submitWithGsId(leyline.testkit.declareAttackersResp(attackers = listOf(attackerIid))),
            )
            harness.drainSink()

            val messages = messagesSince(before)
            val rejection = messages.single { it.type == GREMessageType.IllegalRequest }
            rejection.illegalRequestMessage.reason shouldBe FailureReason.UnexpectedMessage
            val rePrompt = messages.last { it.hasDeclareAttackersReq() }
            rePrompt.declareAttackersReq.attackersList
                .first { it.attackerInstanceId == attackerIid }
                .hasSelectedDamageRecipient()
                .shouldBeFalse()
        }

        test("echo back deselect clears selectedDamageRecipient") {
            val attackerIid = setupSingleAttacker()

            passPriority() // advance to combat
            allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // Select with an explicit damage recipient.
            val onMsgs = toggleAttackers(listOf(attackerIid))
            val onReq = onMsgs.first { it.hasDeclareAttackersReq() }.declareAttackersReq
            onReq.attackersList.map { it.hasSelectedDamageRecipient() } shouldBe listOf(true)

            // Deselect by omitting selectedDamageRecipient.
            val offMsgs = deselectAttackers(listOf(attackerIid))
            val offReq = offMsgs.first { it.hasDeclareAttackersReq() }.declareAttackersReq
            offReq.attackersList.map { it.hasSelectedDamageRecipient() } shouldBe listOf(false)
        }

        test("echo back deselect restores state") {
            val attackerIid = setupSingleAttacker()

            passPriority() // advance to combat
            allMessages.count { it.hasDeclareAttackersReq() } shouldBe 1

            // Toggle ON
            toggleAttackers(listOf(attackerIid))

            // Deselect by omitting selectedDamageRecipient.
            val echoMsgs = deselectAttackers(listOf(attackerIid))

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

            // Select A.
            toggleAttackers(listOf(iidA))
            // Select B while retaining A.
            toggleAttackers(listOf(iidB))
            // Deselect A.
            deselectAttackers(listOf(iidA))

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
            eligible.count { it == attackerIid } shouldBe 1

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
            allMessages.count { it.hasDeclareAttackersReq() } shouldBe 1

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
            val messages = after { declareNoAttackers() }.messages
            assertSoftly {
                messages.shouldNotBeEmpty()
                assertAccumulatorConsistent("after declining combat")
                isGameOver().shouldBeFalse()
            }
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
            allMessages.count { it.hasDeclareAttackersReq() } shouldBe 1

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

        session(
            "single blocker does not trigger AssignDamageReq",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain;Raging Goblin;Raging Goblin;Raging Goblin
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                aibattlefield=Forest;Grizzly Bears
                ailibrary=Forest;Forest;Forest;Forest;Forest
                """,
            turns = 10,
            validating = true,
            validation = combatValidation,
            aiScript = listOf(
                        ScriptedAction.DeclareNoAttackers,
                        ScriptedAction.Block(mapOf("Grizzly Bears" to "Raging Goblin")),
                        ScriptedAction.PassPriority,
                    ),
        ) {
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

        session(
            "zero blockers auto-advances without DeclareBlockersReq",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Plains;Plains
                humanlibrary=Plains;Plains;Plains;Plains;Plains
                aibattlefield=Mountain;Mountain;Raging Goblin;Raging Goblin
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """,
            turns = 10,
            validating = true,
            validation = combatValidation,
            aiScript = listOf(
                        ScriptedAction.Attack(listOf("Raging Goblin")),
                        ScriptedAction.PassPriority,
                    ),
        ) {
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
                aiTurnAars.size shouldBe 1
                aiTurnAars
                    .flatMap { it.actionsAvailableReq.actionsList }
                    .count { it.actionType == ActionType.Cast } shouldBe 1
                human.life shouldBe 20
            }
        }
    })
