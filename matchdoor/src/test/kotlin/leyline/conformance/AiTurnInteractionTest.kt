package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.fail
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
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
 * AiTurnConformanceTest (bridge-tier, ConformanceTag) is NOT absorbed here —
 * it runs below MatchSession. Tracked separately for SubsystemTest migration.
 */
class AiTurnInteractionTest :
    InteractionTest({

        // ─── Boot wire conformance (seed-based, real game start) ────────────────

        test("AI-first boot — ≤1 Full post-handshake + phaseTransitionDiff pattern") {
            startGame(seed = AI_FIRST_SEED)

            val gsms = allMessages
                .filter { it.hasGameStateMessage() }
                .map { it.gameStateMessage }

            // ≤1 Full GSM with zones — no heavyweight Full spam
            val fullWithZones = gsms
                .filter { it.type == GameStateType.Full && it.zonesCount > 0 }

            // Find the phaseTransitionDiff start: first Diff GSM with gameInfo
            val ptStart = gsms.indexOfFirst { it.type == GameStateType.Diff && it.hasGameInfo() }

            assertSoftly {
                fullWithZones.size shouldBeGreaterThanOrEqual 0
                (fullWithZones.size <= 1).shouldBeTrue()

                ptStart shouldBeGreaterThanOrEqual 0
                gsms.size shouldBeGreaterThanOrEqual (ptStart + 3)
            }

            // GSM N+0: SendHiFi with 2+ PhaseOrStepModified + gameInfo
            val gsm0 = gsms[ptStart]
            val phaseAnns0 = gsm0.annotationsList.flatMap { it.typeList }
                .count { it == AnnotationType.PhaseOrStepModified }

            // GSM N+1: SendHiFi echo with turnInfo
            val gsm1 = gsms[ptStart + 1]

            // GSM N+2: SendAndRecord with 1 PhaseOrStepModified
            val gsm2 = gsms[ptStart + 2]
            val phaseAnns2 = gsm2.annotationsList.flatMap { it.typeList }
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
            // AutoPassEngine.checkHumanActions unconditionally returns
            // Skip(OnlyPassActions) on AI turn (line 197 at time of writing),
            // so no code path can produce AI-owned actions in AI-turn GSMs —
            // the assertion is tautological. Keeping only the phase-transition
            // annotation check, which verifies StateMapper emits on every
            // transition.
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

            val gsmsWithTurnInfo = allMessages
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
            phaseChanges.shouldNotBeEmpty()

            val missing = phaseChanges.filter { i ->
                gsmsWithTurnInfo[i].annotationsList.none { ann ->
                    AnnotationType.PhaseOrStepModified in ann.typeList
                }
            }
            if (missing.isNotEmpty()) {
                val report = buildString {
                    appendLine("${missing.size}/${phaseChanges.size} phase transitions missing PhaseOrStepModified:")
                    missing.take(5).forEach { i ->
                        val gsm = gsmsWithTurnInfo[i]
                        appendLine(
                            "  gsId=${gsm.gameStateId} " +
                                "phase=${gsm.turnInfo.phase}/${gsm.turnInfo.step} update=${gsm.update}",
                        )
                    }
                }
                fail("Phase transitions must have PhaseOrStepModified annotations:\n$report")
            }
        }

        test("AI turn emits no ActionsAvailableReq") {
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

            val startSnap = messageSnapshot()
            val startTurn = turn()
            passUntil(maxPasses = 30) { isGameOver() || turn() > startTurn }

            // Filter AARs sent while AI was the active player
            val aiTurnAars = mutableListOf<Int>()
            var lastActivePlayer = OPPONENT_SEAT
            for (msg in messagesSince(startSnap)) {
                if (msg.hasGameStateMessage() && msg.gameStateMessage.hasTurnInfo()) {
                    lastActivePlayer = msg.gameStateMessage.turnInfo.activePlayer
                }
                if (msg.type == GREMessageType.ActionsAvailableReq_695e && lastActivePlayer == OPPONENT_SEAT) {
                    aiTurnAars.add(msg.msgId)
                }
            }
            aiTurnAars.shouldBeEmpty()
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
            val gsms = allMessages.filter { it.hasGameStateMessage() }
                .map { it.gameStateMessage }
                .filter { it.hasTurnInfo() && it.turnInfo.activePlayer == OPPONENT_SEAT }

            gsms.shouldNotBeEmpty()

            // Filter to the combat turn only
            val combatTurn = gsms.first().turnInfo.turnNumber
            val sameTurnGsms = gsms.filter { it.turnInfo.turnNumber == combatTurn }
            for (gsm in sameTurnGsms) {
                val phase = gsm.turnInfo.phase
                if (phase == Phase.Beginning_a549 || phase == Phase.Main1_a549) {
                    fail("Stale phase during AI combat turn $combatTurn: $phase")
                }
            }
        }

        // ─── AI land-play diff discipline (scripted AI) ─────────────────────────

        val scriptedLandThenGoblin = listOf(
            ScriptedAction.PlayLand("Mountain"),
            ScriptedAction.CastSpell("Raging Goblin"),
            ScriptedAction.PassPriority,
            ScriptedAction.DeclareNoAttackers,
            ScriptedAction.PassPriority,
        )

        test("AI land play — dedicated gsId + annotations + precedes CastSpell, CastSpell clean") {
            startGame(seed = 42L, deckList = COMBAT_DECK, validating = false)
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
            val userAction = playLandGsm.annotationsList.first {
                it.typeList.contains(AnnotationType.UserActionTaken)
            }
            val landObj = playLandGsm.gameObjectsList.firstOrNull { obj ->
                obj.cardTypesList.contains(CardType.Land_a80b) && obj.zoneId == 28
            }
            val creatureOnStack = playLandGsm.gameObjectsList.firstOrNull { obj ->
                obj.cardTypesList.contains(CardType.Creature) && obj.zoneId == 27
            }

            // --- CastSpell diff facets ---
            val castSpellHasPlayLandAnn = castSpellGsm.annotationsList.any { ann ->
                AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                    ann.detail("category")?.getValueString(0) == "PlayLand"
            }

            assertSoftly {
                // PlayLand has own gsId, precedes CastSpell
                playLandGsm.gameStateId shouldNotBe castSpellGsm.gameStateId
                (playLandGsm.gameStateId < castSpellGsm.gameStateId).shouldBeTrue()

                // PlayLand annotation triple
                annTypes shouldContain AnnotationType.ObjectIdChanged
                annTypes shouldContain AnnotationType.ZoneTransfer_af5a
                annTypes shouldContain AnnotationType.UserActionTaken
                userAction.detailInt("actionType") shouldBe 3

                // PlayLand diff contains land on battlefield, no creature on stack
                landObj.shouldNotBeNull()
                creatureOnStack.shouldBeNull()

                // CastSpell diff stays clean — no PlayLand annotation bleed
                castSpellHasPlayLandAnn.shouldBe(false)
            }
        }

        test("AI-first land play not discarded (default AI, no script)") {
            startGame(seed = 2L, deckList = COMBAT_DECK, validating = false)
            passUntilTurn(2)

            val turn1PlayLand = allMessages.filter { it.hasGameStateMessage() }.filter { gre ->
                val gsm = gre.gameStateMessage
                gsm.turnInfo.turnNumber == 1 &&
                    gsm.annotationsList.any { ann ->
                        AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                            ann.detail("category")?.getValueString(0) == "PlayLand"
                    }
            }
            turn1PlayLand.shouldNotBeEmpty()
        }
    })
