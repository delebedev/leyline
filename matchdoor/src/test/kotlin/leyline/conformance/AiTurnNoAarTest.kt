package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

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

            // Filter: only AARs sent while activePlayer was AI (seat 2).
            // The turn eventually transitions to the human — AARs for the
            // human's turn are legitimate and expected.
            val aiTurnMessages = h.messagesSince(snapshotBefore)
            val aiTurnAars = mutableListOf<Int>()
            var lastActivePlayer = 2 // puzzle starts on AI turn
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
    })
