package leyline.session.turns

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.session.combat.COMBAT_DECK
import leyline.testkit.AI_FIRST_SEED
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.detail
import leyline.testkit.detailInt
import leyline.testkit.firstWithTransferCategory
import leyline.testkit.gsm
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Phase

/**
 * Session-tier AI-turn tests — MatchSession behavior during opponent turns.
 *
 * Absorbs AiFirstTurnShapeTest, AiTurnNoAarTest, AiLandPlayOrderTest. Organized
 * by concern: boot-time wire conformance (seed-based full game start), AI-turn
 * message properties (puzzle-based for determinism), and AI land-play diff
 * discipline (scripted AI).
 *
 * AiTurnConformanceTest is NOT absorbed here — it runs below MatchSession.
 * Tracked separately for board-tier migration.
 */
class AiTurnInteractionTest :
    SessionTest({

        fun aiTurnActionsAvailableReqs(messages: List<GREToClientMessage>): List<ActionsAvailableReq> {
            val aars = mutableListOf<ActionsAvailableReq>()
            var lastActivePlayer = OPPONENT_SEAT
            for (msg in messages) {
                if (msg.hasGameStateMessage() && msg.gameStateMessage.hasTurnInfo()) {
                    lastActivePlayer = msg.gameStateMessage.turnInfo.activePlayer
                }
                if (msg.type == GREMessageType.ActionsAvailableReq_695e && lastActivePlayer == OPPONENT_SEAT) {
                    aars.add(msg.actionsAvailableReq)
                }
            }
            return aars
        }

        // ─── Boot wire conformance (seed-based, real game start) ────────────────

        test("AI-first boot — ≤1 Full post-handshake + phaseTransitionDiff pattern") {
            startGame(seed = AI_FIRST_SEED)

            val gsms =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }

            // ≤1 Full GSM with zones — no heavyweight Full spam
            val fullWithZones =
                gsms
                    .filter { it.type == GameStateType.Full && it.zonesCount > 0 }

            // Find the phaseTransitionDiff start: first Diff GSM with gameInfo
            val ptStart = gsms.indexOfFirst { it.type == GameStateType.Diff && it.hasGameInfo() }

            assertSoftly {
                fullWithZones.size shouldBeLessThanOrEqual 1

                ptStart shouldBeGreaterThanOrEqual 0
                gsms.size shouldBeGreaterThanOrEqual (ptStart + 3)
            }

            // GSM N+0: SendHiFi with 2+ PhaseOrStepModified + gameInfo
            val gsm0 = gsms[ptStart]
            val phaseAnns0 =
                gsm0.annotationsList
                    .flatMap { it.typeList }
                    .count { it == AnnotationType.PhaseOrStepModified }

            // GSM N+1: SendHiFi echo with turnInfo
            val gsm1 = gsms[ptStart + 1]

            // GSM N+2: SendAndRecord with 1 PhaseOrStepModified
            val gsm2 = gsms[ptStart + 2]
            val phaseAnns2 =
                gsm2.annotationsList
                    .flatMap { it.typeList }
                    .count { it == AnnotationType.PhaseOrStepModified }

            assertSoftly {
                gsm0.type shouldBe GameStateType.Diff
                gsm0.update shouldBe GameStateUpdate.SendHiFi
                gsm0.hasGameInfo().shouldBeTrue()
                phaseAnns0 shouldBeGreaterThanOrEqual 2

                gsm1.type shouldBe GameStateType.Diff
                gsm1.update shouldBe GameStateUpdate.SendHiFi
                gsm1.hasTurnInfo().shouldBeTrue()

                gsm2.type shouldBe GameStateType.Diff
                gsm2.update shouldBe GameStateUpdate.SendAndRecord
                phaseAnns2 shouldBe 1
            }
        }

        // ─── AI-turn message properties (puzzle-based, deterministic) ───────────

        test("AI turn — every phase transition annotated with PhaseOrStepModified") {
            // Dropped facet from the original AiFirstTurnShape test:
            // "AI-turn GSMs embed human actions (seat=1), not AI's (seat=2)".
            // This puzzle has no legal instant-speed human actions, so AI-turn
            // GSMs remain state-only; action ownership belongs in action-emission
            // tests. Keep only the phase-transition annotation check.
            //
            // Puzzle: AI turn with Raging Goblin (haste) → walks through
            // Main1 → Combat → Main2 → End deterministically. No castable in
            // human hand — avoids deep Forge AI threat evaluation that hangs.
            startPuzzle(
                """
                ActivePlayer=AI
                ActivePhase=MAIN1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
                name = "AI Turn Phase Transitions",
                turns = 3,
            )

            val startTurn = turn()
            passUntil(maxPasses = 40) { isGameOver() || turn() > startTurn }

            val gsmsWithTurnInfo =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
                    .filter { it.hasTurnInfo() }

            val phaseChanges = mutableListOf<Int>()
            for (i in 1 until gsmsWithTurnInfo.size) {
                val prev = gsmsWithTurnInfo[i - 1].turnInfo
                val curr = gsmsWithTurnInfo[i].turnInfo
                if (curr.phase != prev.phase || curr.step != prev.step) {
                    phaseChanges.add(i)
                }
            }
            phaseChanges.map { gsmsWithTurnInfo[it].turnInfo.phase } shouldContain Phase.Combat_a549

            val missing =
                phaseChanges.filter { i ->
                    gsmsWithTurnInfo[i].annotationsList.none { ann ->
                        AnnotationType.PhaseOrStepModified in ann.typeList
                    }
                }
            if (missing.isNotEmpty()) {
                val report =
                    buildString {
                        appendLine("${missing.size}/${phaseChanges.size} phase transitions missing PhaseOrStepModified:")
                        missing.take(5).forEach { i ->
                            val gsm = gsmsWithTurnInfo[i]
                            appendLine(
                                "  gsId=${gsm.gameStateId} " +
                                    "phase=${gsm.turnInfo.phase}/${gsm.turnInfo.step} update=${gsm.update}",
                            )
                        }
                    }
                withClue(report) {
                    missing shouldBe emptyList()
                }
            }
        }

        test("AI turn with no legal instant-speed actions emits no ActionsAvailableReq") {
            startPuzzle(
                """
                ActivePlayer=AI
                ActivePhase=MAIN1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
                name = "AI Turn No AAR",
                turns = 3,
            )

            val startTurn = turn()
            val turnSlice = after { passUntil(maxPasses = 30) { isGameOver() || turn() > startTurn } }

            aiTurnActionsAvailableReqs(turnSlice.messages).shouldBeEmpty()
        }

        test("AI turn with legal instant-speed cast emits ActionsAvailableReq") {
            startPuzzle(
                """
                ActivePlayer=AI
                ActivePhase=MAIN1
                HumanLife=20
                AILife=20

                humanhand=Burst Lightning
                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
                name = "AI Turn Instant Stop",
                turns = 3,
            )

            val aiTurnAars = aiTurnActionsAvailableReqs(allMessages)
            aiTurnAars shouldHaveSize 1
            aiTurnAars.single().actionsList.map { it.actionType } shouldContain ActionType.Cast
        }

        test("turnInfo phase never stale during AI combat") {
            // AI attacks with Raging Goblin, human has no creatures.
            // Zero-blocker auto-skip resolves combat during onPuzzleStart.
            startPuzzle(
                """
                ActivePlayer=AI
                ActivePhase=COMBAT_DECLARE_ATTACKERS
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin|Attacking|Tapped;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
                name = "AI Combat Phase Check",
                turns = 3,
            )

            // Use full message history — combat resolved during onPuzzleStart
            val gsms =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
                    .filter { it.hasTurnInfo() && it.turnInfo.activePlayer == OPPONENT_SEAT }

            gsms.map { it.turnInfo.phase } shouldContain Phase.Combat_a549

            // Filter to the combat turn only
            val combatTurn = gsms.first().turnInfo.turnNumber
            val sameTurnGsms = gsms.filter { it.turnInfo.turnNumber == combatTurn }
            val stalePhases =
                sameTurnGsms
                    .map { it.turnInfo.phase }
                    .filter { it == Phase.Beginning_a549 || it == Phase.Main1_a549 }
            stalePhases shouldBe emptyList()
        }

        // ─── AI land-play diff discipline (scripted AI) ─────────────────────────

        val scriptedLandThenGoblin =
            listOf(
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.CastSpell("Raging Goblin"),
                ScriptedAction.PassPriority,
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
            )

        test("AI land play precedes CastSpell in one completed-step frame") {
            startGame(seed = 42L, deckList = COMBAT_DECK, validating = true)
            installScriptedAi(scriptedLandThenGoblin)
            passUntilTurn(3)

            val gsMessages = allMessages.filter { it.hasGameStateMessage() }

            val playLandMsg = gsMessages.firstWithTransferCategory("PlayLand")
            val castSpellMsg = gsMessages.firstWithTransferCategory("CastSpell")
            playLandMsg.shouldNotBeNull()
            castSpellMsg.shouldNotBeNull()

            val playLandGsm = playLandMsg.gameStateMessage
            val castSpellGsm = castSpellMsg.gameStateMessage

            // --- PlayLand diff facets ---
            val annTypes = playLandGsm.annotationsList.map { it.typeList.firstOrNull() }
            val userAction =
                playLandGsm.annotationsList.first {
                    it.typeList.contains(AnnotationType.UserActionTaken)
                }
            val landObj =
                playLandGsm.gameObjectsList.firstOrNull { obj ->
                    obj.cardTypesList.contains(CardType.Land_a80b) && obj.zoneId == 28
                }
            val creatureOnStack =
                playLandGsm.gameObjectsList.firstOrNull { obj ->
                    obj.cardTypesList.contains(CardType.Creature) && obj.zoneId == 27
                }

            // --- CastSpell diff facets ---
            val transferCategories =
                playLandGsm.annotationsList
                    .filter { AnnotationType.ZoneTransfer_af5a in it.typeList }
                    .mapNotNull { it.detail("category")?.valueStringList?.firstOrNull() }
            val combinedFrames =
                gsMessages.count { message ->
                    message.gameStateMessage.annotationsList.any { ann ->
                        AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                            ann.detail("category")?.valueStringList?.firstOrNull() in setOf("PlayLand", "CastSpell")
                    }
                }

            assertSoftly {
                playLandGsm.gameStateId shouldBe castSpellGsm.gameStateId
                combinedFrames shouldBe 1
                transferCategories.indexOf("PlayLand") shouldBeLessThan transferCategories.indexOf("CastSpell")

                // PlayLand annotation triple
                annTypes shouldContain AnnotationType.ObjectIdChanged
                annTypes shouldContain AnnotationType.ZoneTransfer_af5a
                annTypes shouldContain AnnotationType.UserActionTaken
                userAction.detailInt("actionType") shouldBe 3

                // The completed-step cut includes both final objects.
                landObj.shouldNotBeNull()
                creatureOnStack.shouldNotBeNull()
            }
        }

        test("AI-first land play not discarded (default AI, no script)") {
            startGame(seed = 2L, deckList = COMBAT_DECK, validating = true)
            passUntilTurn(2)

            val playLandMessages =
                allMessages.filter { it.hasGameStateMessage() }.filter { gre ->
                    val gsm = gre.gameStateMessage
                    gsm.annotationsList.any { ann ->
                        AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                            ann.detail("category")?.getValueString(0) == "PlayLand"
                    }
                }
            playLandMessages.size shouldBe 1
        }
    })
