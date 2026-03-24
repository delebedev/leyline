package leyline.conformance

import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Regression test for #93: AI turn must never send ActionsAvailableReq.
 *
 * The existing [AiTurnConformanceTest] checks GamePlayback diffs (the
 * EventBus-driven animation pipeline). This test checks the full message
 * sink — including fallback paths in [AutoPassEngine] (max-iterations,
 * timeout) that bypass checkHumanActions and previously called
 * sendRealGameState unconditionally.
 *
 * Uses [MatchFlowHarness] to capture all messages sent to the client,
 * not just playback diffs.
 */
class AiTurnNoAarTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("AI turn sends no ActionsAvailableReq to client") {
            // AI's turn at Main1, AI has a creature + land.
            // Human has nothing castable — autoPass loops through
            // AI's full turn without offering actions.
            val puzzleText = """
                [metadata]
                Name:AI Turn No AAR
                Goal:Win
                Turns:3
                Difficulty:Easy
                Description:AI turn should not produce ActionsAvailableReq

                [state]
                ActivePlayer=AI
                ActivePhase=MAIN1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin;Mountain
                ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)

            val snapshotBefore = h.messageSnapshot()

            // Pass through AI's full turn until human gets priority
            val startTurn = h.turn()
            repeat(30) {
                if (h.isGameOver() || h.turn() > startTurn) return@repeat
                h.passPriority()
            }
            (h.isGameOver() || h.turn() > startTurn).shouldBeTrue()

            // Filter: only AARs sent while activePlayer was AI (seat 2).
            // The turn eventually transitions to the human — AARs for the
            // human's turn are legitimate and expected.
            val aiTurnMessages = h.messagesSince(snapshotBefore)
            val aiTurnAars = mutableListOf<Int>()
            var lastActivePlayer = 2 // AI is always seat 2 in 1vAI matches; puzzle starts on AI turn
            for (msg in aiTurnMessages) {
                if (msg.hasGameStateMessage() && msg.gameStateMessage.hasTurnInfo()) {
                    lastActivePlayer = msg.gameStateMessage.turnInfo.activePlayer
                }
                if (msg.type == GREMessageType.ActionsAvailableReq_695e && lastActivePlayer == 2) {
                    aiTurnAars.add(msg.msgId)
                }
            }
            aiTurnAars.shouldBeEmpty()
        }

        test("turnInfo phase correct at AAR during AI combat") {
            // AI attacks with Raging Goblin, human has no creatures.
            // Zero-blocker auto-skip resolves combat during onPuzzleStart.
            // Verify turnInfo in all GSMs from the full session never shows
            // a stale phase during the AI's combat turn.
            val puzzleText = """
                [metadata]
                Name:AI Combat Phase Check
                Goal:Win
                Turns:3
                Difficulty:Easy
                Description:Verify turnInfo phase during AI combat

                [state]
                ActivePlayer=AI
                ActivePhase=COMBAT_DECLARE_ATTACKERS
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin|Attacking|Tapped;Mountain
                ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)

            // With zero-blocker auto-skip, combat resolves during onPuzzleStart.
            // All combat GSMs are already captured. Use the full message history.
            val msgs = h.messagesSince(0)

            // All GSMs during AI combat should have combat phase or later — never
            // Beginning or Main1.
            val gsms = msgs.filter { it.hasGameStateMessage() }
                .map { it.gameStateMessage }
                .filter { it.hasTurnInfo() && it.turnInfo.activePlayer == 2 }

            // Filter to the combat turn only. Beginning in a LATER turn is expected.
            gsms.shouldNotBeEmpty()
            val combatTurn = gsms.first().turnInfo.turnNumber
            val sameTurnGsms = gsms.filter { it.turnInfo.turnNumber == combatTurn }
            for (gsm in sameTurnGsms) {
                val phase = gsm.turnInfo.phase
                if (phase == Phase.Beginning_a549 || phase == Phase.Main1_a549) {
                    fail("Stale phase during AI combat turn $combatTurn: $phase")
                }
            }
        }
    })
